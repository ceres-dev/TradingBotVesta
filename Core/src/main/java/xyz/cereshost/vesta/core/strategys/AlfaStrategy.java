package xyz.cereshost.vesta.core.strategys;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;

import java.util.List;

public class AlfaStrategy implements TradingStrategy {
    @Override
    public void executeStrategy(PredictionEngine.@NotNull PredictionResult pred, List<Candle> visibleCandles, TradingManager operations) {
        if (operations.hasOpenOperation()){
            for (TradingManager.OpenOperation o : operations.getOpens()){
                if (o.getMinutesOpen() >= 60){
                    operations.close(TradingManager.ExitReason.TIMEOUT, o);
                }
            }
        }else {
            if (pred.ratioClose() > 2 && pred.tpPercent() > 0.2){
                operations.open(pred.tpPercent(), pred.slPercent(), pred.getDireccion(), operations.getAvailableBalance()/2, 4);
            }
        }
    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {

    }
}
