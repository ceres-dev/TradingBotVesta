package xyz.cereshost.vesta.core.strategy;

import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;

import java.util.List;

public class TestStrategy implements TradingStrategy {

    private boolean isPeekClose = false;
    @Override
    public void executeStrategy(PredictionEngine.PredictionResult prediction, List<Candle> visibleCandles, TradingManager openOperations) {
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
