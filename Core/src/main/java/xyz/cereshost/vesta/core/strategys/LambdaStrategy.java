package xyz.cereshost.vesta.core.strategys;

import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.trading.TradingManager;

import java.util.List;

public class LambdaStrategy implements TradingStrategy {

    private final KappaStrategy kappaStrategy = new KappaStrategy();
    private final ZetaStrategy zetaStrategy = new ZetaStrategy();

    private double lossesStrike;

    @Override
    public void executeStrategy(PredictionEngine.PredictionResult pred, List<Candle> visibleCandles, TradingManager openOperations) {
        if (openOperations.hasOpenOperation()) return;
        kappaStrategy.executeStrategy(pred, visibleCandles, openOperations);
        if (openOperations.hasOpenOperation()) return;
        if (lossesStrike <= 3){
            zetaStrategy.executeStrategy(pred, visibleCandles, openOperations);
        }
    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {
        if (!closeOperation.isProfit()){
            lossesStrike += 1;
        }else {
            lossesStrike = Math.max(0, lossesStrike - 2);
        }
        kappaStrategy.closeOperation(closeOperation, operations);
        zetaStrategy.closeOperation(closeOperation, operations);
    }
}
