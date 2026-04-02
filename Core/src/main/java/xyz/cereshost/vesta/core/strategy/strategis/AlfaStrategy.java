package xyz.cereshost.vesta.core.strategy.strategis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.StrategyConfig;
import xyz.cereshost.vesta.core.strategy.TradingStrategyConfigurable;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.BuilderData;
import xyz.cereshost.vesta.core.utils.candle.CandleIndicators;
import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

public class AlfaStrategy implements TradingStrategyConfigurable {
    @Override
    public void executeStrategy(PredictionEngine.@Nullable SequenceCandlesPrediction pred, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations) {
        if (pred == null) return;
        CandleIndicators curr = visibleCandles.getCandleLast();
        if (operations.hasOpenOperation()){
            operations.computeHasOpenOperation(o -> {
                if (o.getMinutesOpen() >= 60){
                    operations.close(TradingManager.ExitReason.TIMEOUT);
                }
            });
        }else {
            double tp = pred.tpPercent();
            double sl = pred.slPercent();
            DireccionOperation direccionPred = pred.getDireccion();
            DireccionOperation direccion = curr.get("superTrend") > curr.getClose() ? DireccionOperation.SHORT : DireccionOperation.LONG;
            if (tp > 0.2 && tp < 1) operations.open(tp, sl, direccionPred, operations.getAvailableBalance()/2, 4);

        }
    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {

    }

    @Override
    public StrategyConfig getStrategyConfig(TradingManager tradingManager) {
        return StrategyConfig.builder().condicionUseModelIA(() -> !tradingManager.hasOpenOperation()).futurePredict(15).build();
    }

    @Override
    public @NotNull CandlesBuilder getBuilder(){
        return BuilderData.getProfierCandlesBuilder().addSuperTrendIndicator("superTrend", 10, 2);
    }
}
