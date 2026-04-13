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
        if (operations.hasOpenOperation()) {
            operations.close(TradingManager.ExitReason.STRATEGY);
        }
        if (pred == null) return;
        if (Math.abs(pred.getFirst().getClose()) > 0.6f)
            operations.open(new TradingManager.RiskLimitsPercent(null, null), DireccionOperation.parse(pred.getFirst().getClose()), operations.getAvailableBalance() / 2, 4);
    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {

    }

    @Override
    public StrategyConfig getStrategyConfig(TradingManager tradingManager) {
        return StrategyConfig.builder().condicionUseModelIA(() ->tradingManager.getAvailableBalance() > 2.5).futurePredict(1).build();
    }

    @Override
    public @NotNull CandlesBuilder getBuilder(){
        return BuilderData.getProfierCandlesBuilder();
    }
}
