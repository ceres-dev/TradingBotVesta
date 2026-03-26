package xyz.cereshost.vesta.core.strategy.strategys;

import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.trading.TradingManager;

import java.util.List;

public class LambdaStrategy implements TradingStrategy {

    private final BetaStrategy betaStrategy = new BetaStrategy();
    private final ZetaStrategy zetaStrategy = new ZetaStrategy();

    private double lossesStrike;

    @Override
    public void executeStrategy(PredictionEngine.PredictionResult pred, List<Candle> visibleCandles, TradingManager openOperations) {
        zetaStrategy.executeStrategy(pred, visibleCandles, openOperations);
        betaStrategy.executeStrategy(pred, visibleCandles, openOperations);

    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {
        zetaStrategy.closeOperation(closeOperation, operations);
        betaStrategy.closeOperation(closeOperation, operations);
    }
}
