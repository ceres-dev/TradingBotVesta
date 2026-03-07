package xyz.cereshost.strategy;

import xyz.cereshost.common.market.Candle;
import xyz.cereshost.engine.PredictionEngine;
import xyz.cereshost.trading.Trading;
import xyz.cereshost.utils.StrategyUtils;

import java.util.List;

import static xyz.cereshost.utils.StrategyUtils.isHigh;
import static xyz.cereshost.utils.StrategyUtils.isLow;

public class TestStrategy implements TradingStrategy {

    private boolean isPeekClose = false;
    @Override
    public void executeStrategy(PredictionEngine.PredictionResult prediction, List<Candle> visibleCandles, Trading openOperations) {
        if (openOperations.hasOpenOperation()){

        }else {
            boolean b = isHigh(visibleCandles, 60);
            if (!isPeekClose) isPeekClose = b;
            if (isPeekClose && !b)
                openOperations.open(0.4, 0.2, Trading.DireccionOperation.SHORT, openOperations.getAvailableBalance()/2, 1);
        }
    }

    @Override
    public void closeOperation(Trading.CloseOperation closeOperation, Trading operations) {
        isPeekClose = false;
    }
}
