package xyz.cereshost.vesta.core.strategys;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.core.trading.TradingManager;

import java.util.List;

public class BetaStrategy implements TradingStrategy {
    @Override
    public void executeStrategy(PredictionEngine.@NotNull PredictionResult pred, List<Candle> visibleCandles, TradingManager operations) {
        for (TradingManager.OpenOperation o : operations.getOpens()){
            if (o.getMinutesOpen() >= 60) operations.close(TradingManager.ExitReason.TIMEOUT, o);
        }
        if (operations.openSize() == 0 && pred.directionOperation() != TradingManager.DireccionOperation.NEUTRAL) {
            operations.open(pred.getTpPercent(), pred.getSlPercent(), pred.directionOperation(), operations.getAvailableBalance()/2, 1);
        }
    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {

    }
}
