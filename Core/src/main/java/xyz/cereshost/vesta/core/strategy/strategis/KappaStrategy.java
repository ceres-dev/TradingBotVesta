//package xyz.cereshost.vesta.core.strategy.strategis;
//
//import org.jetbrains.annotations.NotNull;
//import org.jetbrains.annotations.Nullable;
//import xyz.cereshost.vesta.core.ia.PredictionEngine;
//import xyz.cereshost.vesta.core.strategy.TradingStrategy;
//import xyz.cereshost.vesta.core.trading.DireccionOperation;
//import xyz.cereshost.vesta.core.trading.TradingManager;
//import xyz.cereshost.vesta.core.utils.candle.CandleIndicators;
//import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
//import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;
//
//import java.time.DayOfWeek;
//import java.time.Instant;
//import java.time.ZoneId;
//import java.time.ZonedDateTime;
//
//public class KappaStrategy implements TradingStrategy {
//
//    // Kevin ATR Renko Pro defaults (adapted to this engine)
//    private static final int FAST_EMA_LENGTH = 2;
//    private static final int SLOW_EMA_LENGTH = 10;
//    private static final int ATR_LENGTH = 20;
//
//    private static final double PROFIT_FACTOR = 2.5;
//    private static final int LEVERAGE = 4;
//    private static final double ORDER_BALANCE_FRACTION = 0.5;
//    private static final double MIN_ORDER_NOTIONAL = 5.0;
//
//    // Pine input.time("01 Jan 2023 00:00 +0300")
//    private static final boolean ENABLE_DATE_FILTER = true;
//    private static final long FROM_DATE_MS = 1_672_520_400_000L;
//    // Pine input.time("31 Dec 2099 00:00 +0300")
//    private static final long TO_DATE_MS = 4_102_347_600_000L;
//    // Horario de referencia para sesión de oro
//    private static final ZoneId MARKET_TIMEZONE = ZoneId.of("America/New_York");
//
//    @Override
//    public @NotNull CandlesBuilder getBuilder() {
//        return new CandlesBuilder()
//                .addEMAIndicator("kappa_ema_fast", FAST_EMA_LENGTH)
//                .addEMAIndicator("kappa_ema_slow", SLOW_EMA_LENGTH)
//                .addATRIndicator("kappa_atr", ATR_LENGTH);
//    }
//
//    @Override
//    public void executeStrategy(PredictionEngine.@Nullable SequenceCandlesPrediction prediction, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations) {
//        if (visibleCandles.size() < 3) {
//            return;
//        }
//
//        if (operations.hasOpenOperation()) return;
//
//        if (!isTradeDateAllowed(operations.getCurrentTime())) {
//            return;
//        }
//        if (!isGoldSessionOpen(operations.getCurrentTime())) {
//            return;
//        }
//
//        int lastIndex = visibleCandles.size() - 1;
//        CandleIndicators previous = visibleCandles.getCandle(lastIndex - 1);
//        CandleIndicators current = visibleCandles.getCandleLast();
//
//        double prevFast = previous.get("kappa_ema_fast");
//        double prevSlow = previous.get("kappa_ema_slow");
//        double currFast = current.get("kappa_ema_fast");
//        double currSlow = current.get("kappa_ema_slow");
//        double atr = current.get("kappa_atr");
//        double entryPrice = current.getClose();
//
//        if (!Double.isFinite(prevFast) || !Double.isFinite(prevSlow) ||
//                !Double.isFinite(currFast) || !Double.isFinite(currSlow) ||
//                !Double.isFinite(atr) || atr <= 0 ||
//                !Double.isFinite(entryPrice) || entryPrice <= 0) {
//            return;
//        }
//
//        boolean buy = prevFast <= prevSlow && currFast > currSlow;
//        boolean sell = prevFast >= prevSlow && currFast < currSlow;
//
//        if (!buy && !sell) {
//            return;
//        }
//
//        if (buy) {
//            closeDirection(operations, DireccionOperation.SHORT);
//            if (!hasOpenDirection(operations, DireccionOperation.LONG)) {
//                openPosition(operations, DireccionOperation.LONG, entryPrice, atr);
//            }
//            return;
//        }
//
//        closeDirection(operations, DireccionOperation.LONG);
//        if (!hasOpenDirection(operations, DireccionOperation.SHORT)) {
//            openPosition(operations, DireccionOperation.SHORT, entryPrice, atr);
//        }
//    }
//
//    @Override
//    public void closeOperation(TradingManager.ClosePosition closeOperation, TradingManager operations) {
//
//    }
//
//    private static boolean isTradeDateAllowed(long timeMs) {
//        return !ENABLE_DATE_FILTER || (timeMs >= FROM_DATE_MS && timeMs <= TO_DATE_MS);
//    }
//
//    /**
//     * Mercado cerrado desde viernes 16:00 hasta domingo 20:00 (hora New York).
//     */
//    private static boolean isGoldSessionOpen(long currentTimeMs) {
//        ZonedDateTime now = Instant.ofEpochMilli(currentTimeMs).atZone(MARKET_TIMEZONE);
//        DayOfWeek day = now.getDayOfWeek();
//        int hour = now.getHour();
//
//        if (day == DayOfWeek.SATURDAY) {
//            return false;
//        }
//        if (day == DayOfWeek.FRIDAY && hour >= 16) {
//            return false;
//        }
//        if (day == DayOfWeek.SUNDAY && hour < 20) {
//            return false;
//        }
//        return true;
//    }
//
//    private void openPosition(@NotNull TradingManager operations,
//                              @NotNull DireccionOperation direction,
//                              double entryPrice,
//                              double atr) {
//        double available = operations.getAvailableBalance();
//        if (!Double.isFinite(available) || available <= 0) {
//            return;
//        }
//
//        double amountUsd = available * ORDER_BALANCE_FRACTION;
//        if (!Double.isFinite(amountUsd) || amountUsd <= 0) {
//            return;
//        }
//
//        if (amountUsd * LEVERAGE < MIN_ORDER_NOTIONAL) {
//            return;
//        }
//
//        // Pine strategy has TP1/TP2/TP3 partial exits. This engine supports one TP/SL per position.
//        // We map TP to the first target (TP1) and keep the original ATR-based SL distance.
//        double targetDistance = PROFIT_FACTOR * atr;
//        double tpPrice;
//        double slPrice;
//
//        if (direction == DireccionOperation.LONG) {
//            tpPrice = entryPrice + targetDistance;
//            slPrice = entryPrice - targetDistance;
//        } else {
//            tpPrice = entryPrice - targetDistance;
//            slPrice = entryPrice + targetDistance;
//        }
//
//        TradingManager.RiskLimitsAbsolute riskLimits = new TradingManager.RiskLimitsAbsolute(tpPrice, slPrice);
//        TradingManager.OpenPosition open = operations.open(riskLimits, direction, amountUsd, LEVERAGE);
//        if (open != null) {
//            open.getFlags().add("Kappa");
//        }
//    }
//
//    private static boolean hasOpenDirection(@NotNull TradingManager operations, @NotNull DireccionOperation direction) {
//        return operations.hasOpenOperation() && operations.getOpenPosition().getDireccion() == direction;
//    }
//
//    private static void closeDirection(@NotNull TradingManager operations, @NotNull DireccionOperation direction) {
//        operations.computeHasOpenOperation(open -> {
//            if (open.getDireccion() == direction) {
////                operations.close(TradingManager.ExitReason.STRATEGY_INVERSION);
//            }
//        });
//    }
//}
