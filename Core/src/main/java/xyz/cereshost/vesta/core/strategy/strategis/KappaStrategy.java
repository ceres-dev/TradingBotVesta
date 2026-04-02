package xyz.cereshost.vesta.core.strategy.strategis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ta4j.core.indicators.volume.VWAPIndicator;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.candle.CandleIndicators;
import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class KappaStrategy implements TradingStrategy {

    // Terminator2
    private static final int ATR_LEN = 14;
    private static final double IMPULSE_MULT = 1.1;
    private static final double COMPRESS_PCT = 0.7;
    private static final int STALL_BARS = 2;
    private static final double MIN_BODY_FRAC = 0.20;
    private static final int MAX_STALL_BARS = 15;

    // Risk / exits (adaptado al motor actual)
    private static final boolean USE_SL = false;
    private static final double SL_POINTS = 60.0;
    private static final double TP1_POINTS = 10.0;
    private static final double TP2_POINTS = 25.0;
    private static final double TRAIL_POINTS = 20.0;
    private static final double TRAIL_ACTIVATION_MULT = 4.0;
    private static final double RUNNER_TP_MULT = 6.0;

    private static final int LEVERAGE = 4;
    private static final double ORDER_BALANCE_FRACTION = 0.50;
    private static final double NO_SL_PERCENT = 99.0;
    private static final double MIN_NOTIONAL_USDT = 2;

    private static final String FLAG_KAPPA = "Kappa";
    private static final String FLAG_TP1 = "KappaTP1";
    private static final String FLAG_TP2 = "KappaTP2";
    private static final String FLAG_RUNNER = "KappaRunner";

    private int state = 0;
    private double compHigh = Double.NaN;
    private double compLow = Double.NaN;
    private int stallCount = 0;

    private long confirmAtOpenTime = -1L;
    private boolean pendingFireLong = false;
    private boolean pendingFireShort = false;

    private final HashMap<UUID, TrailingState> runnerStateByOperation = new HashMap<>();

    @Override
    public void executeStrategy(PredictionEngine.@Nullable SequenceCandlesPrediction pred, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations) {
        if (visibleCandles.size() < ATR_LEN + 2) {
            return;
        }

        CandleIndicators current = visibleCandles.getCandleLast();
        if (!isFinite(current.getOpen()) || !isFinite(current.getHigh()) || !isFinite(current.getLow()) || !isFinite(current.getClose())) {
            return;
        }

        updateRunnerTrailingStops(operations, current);

        boolean confirmLong = false;
        boolean confirmShort = false;
        long currentOpenTime = current.getOpenTime();

        if (confirmAtOpenTime > 0) {
            if (currentOpenTime == confirmAtOpenTime) {
                confirmLong = pendingFireLong && current.getClose() > current.getOpen();
                confirmShort = pendingFireShort && current.getClose() < current.getOpen();
                clearPendingConfirmation();
            } else if (currentOpenTime > confirmAtOpenTime) {
                clearPendingConfirmation();
            }
        }

        double atr = current.get("atr");
        double emaSlow = current.get("emaSlow");
        double vwap = current.get("vwap");
        if (!isFinite(atr) || atr <= 0 || !isFinite(emaSlow) || !isFinite(vwap)) {
            return;
        }

        double barRange = current.getHigh() - current.getLow();
        double body = Math.abs(current.getClose() - current.getOpen());
        double bodyFrac = barRange > 0 ? body / barRange : 0.0;

        boolean strongUp = current.getClose() > current.getOpen() && barRange > atr * IMPULSE_MULT;
        boolean strongDown = current.getClose() < current.getOpen() && barRange > atr * IMPULSE_MULT;

        if (state == 0 && (strongUp || strongDown)) {
            state = 1;
            stallCount = 0;
            compHigh = Double.NaN;
            compLow = Double.NaN;
        }

        boolean smallCandle = barRange < atr * COMPRESS_PCT;
        if (state == 1 && smallCandle) {
            state = 2;
            stallCount = 1;
            compHigh = current.getHigh();
            compLow = current.getLow();
        } else if (state == 2 && smallCandle) {
            stallCount++;
            compHigh = Math.max(compHigh, current.getHigh());
            compLow = Math.min(compLow, current.getLow());
        }

        boolean validComp = state == 2 && stallCount >= STALL_BARS && isFinite(compHigh) && isFinite(compLow);
        boolean bullBreak = validComp && current.getClose() > compHigh;
        boolean bearBreak = validComp && current.getClose() < compLow;
        boolean breakClean = bodyFrac >= MIN_BODY_FRAC;
        boolean fire = (bullBreak || bearBreak) && breakClean;

        if (fire) {
            pendingFireLong = bullBreak;
            pendingFireShort = bearBreak;
            confirmAtOpenTime = currentOpenTime + current.getTimeUnit().getMilliseconds();
            resetCompressionState();
        }

        if (state != 0 && stallCount > MAX_STALL_BARS) {
            resetCompressionState();
        }

        boolean filterLong = current.getClose() > emaSlow && current.getClose() > vwap;
        boolean filterShort = current.getClose() < emaSlow && current.getClose() < vwap;

        boolean goLong = confirmLong && filterLong && !operations.hasOpenOperation();
        boolean goShort = confirmShort && filterShort && !operations.hasOpenOperation();

        if (goLong) {
            openTieredPosition(operations, DireccionOperation.LONG, current.getClose());
        } else if (goShort) {
            openTieredPosition(operations, DireccionOperation.SHORT, current.getClose());
        }
    }

    private void openTieredPosition(@NotNull TradingManager operations, @NotNull DireccionOperation direction, double referencePrice) {
        if (!isFinite(referencePrice) || referencePrice <= 0) {
            return;
        }

        double availableBalance = operations.getAvailableBalance();
        if (!isFinite(availableBalance) || availableBalance <= 0) {
            return;
        }

        double totalAmount = availableBalance * ORDER_BALANCE_FRACTION;
        if (!isFinite(totalAmount) || totalAmount <= 0) {
            return;
        }

        double slPercent = USE_SL ? pointsToPercent(SL_POINTS, referencePrice) : NO_SL_PERCENT;
        double tp1Percent = pointsToPercent(TP1_POINTS, referencePrice);
        double tp2Percent = pointsToPercent(TP2_POINTS, referencePrice);
        double runnerTpPercent = pointsToPercent(Math.max(TP2_POINTS * 3.0, TRAIL_POINTS * RUNNER_TP_MULT), referencePrice);

        if (!isFinite(slPercent) || !isFinite(tp1Percent) || !isFinite(tp2Percent) || !isFinite(runnerTpPercent)) {
            return;
        }
        double perOrderAmount = totalAmount / 3.0;
        if (perOrderAmount * LEVERAGE < MIN_NOTIONAL_USDT) {
            TradingManager.OpenOperation fallback = operations.open(tp2Percent, slPercent, direction, totalAmount, LEVERAGE);
            if (fallback != null) {
                fallback.getFlags().add(FLAG_KAPPA);
                fallback.getFlags().add(FLAG_TP2);
            }
            return;
        }

        TradingManager.OpenOperation tp1 = operations.open(tp1Percent, slPercent, direction, perOrderAmount, LEVERAGE);
        if (tp1 != null) {
            tp1.getFlags().add(FLAG_KAPPA);
            tp1.getFlags().add(FLAG_TP1);
        }

        TradingManager.OpenOperation tp2 = operations.open(tp2Percent, slPercent, direction, perOrderAmount, LEVERAGE);
        if (tp2 != null) {
            tp2.getFlags().add(FLAG_KAPPA);
            tp2.getFlags().add(FLAG_TP2);
        }

        TradingManager.OpenOperation runner = operations.open(runnerTpPercent, slPercent, direction, perOrderAmount, LEVERAGE);
        if (runner != null) {
            runner.getFlags().add(FLAG_KAPPA);
            runner.getFlags().add(FLAG_RUNNER);
            runnerStateByOperation.put(runner.getUuid(), new TrailingState(runner.getEntryPrice()));
        }
    }

    private void updateRunnerTrailingStops(@NotNull TradingManager operations, @NotNull CandleIndicators current) {
        Set<UUID> activeRunnerIds = new HashSet<>();
        double activationDistance = TRAIL_POINTS * TRAIL_ACTIVATION_MULT;
        operations.computeHasOpenOperation(open -> {
            if (!open.getFlags().contains(FLAG_RUNNER)) {
                return;
            }
            if (!isFinite(open.getEntryPrice()) || open.getEntryPrice() <= 0) {
                return;
            }

            activeRunnerIds.add(open.getUuid());
            TrailingState state = runnerStateByOperation.computeIfAbsent(open.getUuid(), id -> new TrailingState(open.getEntryPrice()));

            if (open.isUpDireccion()) {
                state.highestPrice = Math.max(state.highestPrice, current.getHigh());
                if (!state.activated && state.highestPrice >= open.getEntryPrice() + activationDistance) {
                    state.activated = true;
                }
                if (!state.activated) {
                    return;
                }

                double trailStopPrice = state.highestPrice - activationDistance;
                if (trailStopPrice > open.getSlPrice()) {
                    double newSlPercent = longStopPriceToPercent(open.getEntryPrice(), trailStopPrice);
                    if (isFinite(newSlPercent) && newSlPercent > 0) {
                        open.setSlPercent(newSlPercent);
                    }
                }
            } else {
                state.lowestPrice = Math.min(state.lowestPrice, current.getLow());
                if (!state.activated && state.lowestPrice <= open.getEntryPrice() - activationDistance) {
                    state.activated = true;
                }
                if (!state.activated) {
                    return;
                }

                double trailStopPrice = state.lowestPrice + activationDistance;
                if (trailStopPrice < open.getSlPrice()) {
                    double newSlPercent = shortStopPriceToPercent(open.getEntryPrice(), trailStopPrice);
                    if (isFinite(newSlPercent) && newSlPercent > 0) {
                        open.setSlPercent(newSlPercent);
                    }
                }
            }
        });

        runnerStateByOperation.keySet().removeIf(uuid -> !activeRunnerIds.contains(uuid));
    }

    private static double longStopPriceToPercent(double entryPrice, double stopPrice) {
        return ((entryPrice - stopPrice) / entryPrice) * 100.0;
    }

    private static double shortStopPriceToPercent(double entryPrice, double stopPrice) {
        return ((stopPrice - entryPrice) / entryPrice) * 100.0;
    }

    private static double pointsToPercent(double points, double referencePrice) {
        if (!isFinite(points) || !isFinite(referencePrice) || points <= 0 || referencePrice <= 0) {
            return Double.NaN;
        }
        return (points / referencePrice) * 100.0;
    }

    private void clearPendingConfirmation() {
        confirmAtOpenTime = -1L;
        pendingFireLong = false;
        pendingFireShort = false;
    }

    private void resetCompressionState() {
        state = 0;
        stallCount = 0;
        compHigh = Double.NaN;
        compLow = Double.NaN;
    }

    private static boolean isFinite(double value) {
        return Double.isFinite(value);
    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {
        runnerStateByOperation.remove(closeOperation.getUuid());
    }

    @Override
    public @NotNull CandlesBuilder getBuilder() {
        return new CandlesBuilder()
                .addATRIndicator("atr", ATR_LEN)
                .addEMAIndicator("emaFast", 8)
                .addEMAIndicator("emaSlow", 55)
                .addIndicator("vwap", (data, indicators) -> {
                    int barsPerDay = 1;
                    if (data.series().getBarCount() > 0) {
                        Duration barDuration = data.series().getBar(0).getTimePeriod();
                        long barMs = Math.max(1L, barDuration.toMillis());
                        barsPerDay = (int) Math.max(1L, Duration.ofDays(1).toMillis() / barMs);
                    }
                    return new VWAPIndicator(data.series(), barsPerDay);
                });
    }

    private static final class TrailingState {
        private double highestPrice;
        private double lowestPrice;
        private boolean activated;

        private TrailingState(double entryPrice) {
            this.highestPrice = entryPrice;
            this.lowestPrice = entryPrice;
            this.activated = false;
        }
    }
}
