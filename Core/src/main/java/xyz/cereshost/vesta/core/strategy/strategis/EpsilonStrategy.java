package xyz.cereshost.vesta.core.strategy.strategis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.candle.CandleIndicators;
import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

public class EpsilonStrategy implements TradingStrategy {

    private static final int FAST_EMA_LENGTH = 10;
    private static final int SLOW_EMA_LENGTH = 30;

    private static final double TP_PERCENT = 0.3;
    private static final double SL_PERCENT = 0.3;
    private static final int LEVERAGE = 4;
    private static final double ORDER_BALANCE_FRACTION = 1.0;
    private static final double MIN_ORDER_NOTIONAL = 5.0;

    @Override
    public void executeStrategy(PredictionEngine.@Nullable SequenceCandlesPrediction prediction, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations) {
        if (visibleCandles.size() < 2) {
            return;
        }

        int lastIndex = visibleCandles.size() - 1;
        CandleIndicators previous = visibleCandles.getCandle(lastIndex - 1);
        CandleIndicators current = visibleCandles.getCandleLast();

        double prevFast = previous.get("emaFast");
        double prevSlow = previous.get("emaSlow");
        double currFast = current.get("emaFast");
        double currSlow = current.get("emaSlow");

        if (!Double.isFinite(prevFast) || !Double.isFinite(prevSlow) || !Double.isFinite(currFast) || !Double.isFinite(currSlow)) {
            return;
        }

        boolean bullishCross = prevFast <= prevSlow && currFast > currSlow;
        boolean bearishCross = prevFast >= prevSlow && currFast < currSlow;

        if (bullishCross) {
            closeDirection(operations, DireccionOperation.SHORT);
            if (!hasOpenDirection(operations, DireccionOperation.LONG)) {
                openPosition(operations, DireccionOperation.LONG);
            }
            return;
        }

        if (bearishCross) {
            closeDirection(operations, DireccionOperation.LONG);
            if (!hasOpenDirection(operations, DireccionOperation.SHORT)) {
                openPosition(operations, DireccionOperation.SHORT);
            }
        }
    }

    private void openPosition(@NotNull TradingManager operations, @NotNull DireccionOperation direction) {
        double available = operations.getAvailableBalance();
        if (!Double.isFinite(available) || available <= 0) {
            return;
        }

        double amountUsdt = available * ORDER_BALANCE_FRACTION;
        if (!Double.isFinite(amountUsdt) || amountUsdt <= 0) {
            return;
        }

        if (amountUsdt * LEVERAGE < MIN_ORDER_NOTIONAL) {
            return;
        }

        operations.open(TP_PERCENT, SL_PERCENT, direction, amountUsdt, LEVERAGE);
    }

    private static boolean hasOpenDirection(@NotNull TradingManager operations, @NotNull DireccionOperation direction) {
        if (operations.hasOpenOperation()){
            return operations.getOpen().getDireccion() == direction;
        }
        return false;
    }

    private static void closeDirection(@NotNull TradingManager operations, @NotNull DireccionOperation direction) {
        operations.computeHasOpenOperation(open -> {
            if (open.getDireccion() == direction) {
                operations.close(TradingManager.ExitReason.STRATEGY_INVERSION);
            }
        });
    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {
        if (!closeOperation.isProfit()) operations.open(TP_PERCENT, SL_PERCENT, closeOperation.getDireccion().inverse(), 2, LEVERAGE);
    }

    @Override
    public @NotNull CandlesBuilder getBuilder() {
        return new CandlesBuilder()
                .addEMAIndicator("emaFast", FAST_EMA_LENGTH)
                .addEMAIndicator("emaSlow", SLOW_EMA_LENGTH);
    }
}
