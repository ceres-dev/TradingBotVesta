package xyz.cereshost.vesta.core.strategy;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

public class TestStrategy implements TradingStrategy {

    private boolean isPeekClose = false;
    @Override
    public void executeStrategy(PredictionEngine.SequenceCandlesPrediction prediction, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager openOperations) {
        if (openOperations.hasOpenOperation()) {
            openOperations.getOpens().getFirst().close();
        }else {
            openOperations.open(0.4, 0.2, DireccionOperation.SHORT, openOperations.getAvailableBalance()/2, 1);

        }
    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {
        isPeekClose = false;
    }
}
