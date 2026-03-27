package xyz.cereshost.vesta.core.strategy.strategys;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.StrategyConfig;
import xyz.cereshost.vesta.core.strategy.TradingStrategyConfigurable;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.BuilderData;
import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

public class AlfaStrategy implements TradingStrategyConfigurable {
    @Override
    public void executeStrategy(PredictionEngine.@Nullable SequenceCandlesPrediction pred, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations) {
        if (pred == null) return;
        if (operations.hasOpenOperation()){
            for (TradingManager.OpenOperation o : operations.getOpens()){
                if (o.getMinutesOpen() >= 60){
                    operations.close(TradingManager.ExitReason.TIMEOUT, o);
                }
            }
        }else {
            double tp = pred.tpPercent();
            if (tp > 0.2 && tp < 1) operations.open(tp, tp/2, pred.getDireccion(), operations.getAvailableBalance()/2, 4);
        }
    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {

    }

    @Override
    public StrategyConfig getStrategyConfig(TradingManager tradingManager) {
        return StrategyConfig.builder().condicionUseModelIA(() -> !tradingManager.hasOpenOperation()).build();
    }

    @Override
    public CandlesBuilder getBuilder(){
        return BuilderData.getProfierCandlesBuilder();
    }
}
