package xyz.cereshost.vesta.core.strategy.strategis;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.Main;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.StrategyConfig;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.strategy.TradingStrategyConfigurable;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.BuilderData;
import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.util.Optional;

public class DeltaStrategy implements TradingStrategyConfigurable, TradingStrategy {


    @Override
    public void executeStrategy(@NotNull Optional<PredictionEngine.SequenceCandlesPrediction> optional,
                                @NotNull SequenceCandles visibleCandles,
                                @NotNull TradingManager manager
    ) {
        if (manager.getOpenPosition().isPresent()) {
            manager.close(TradingManager.ExitReason.STRATEGY);
        }
        optional.ifPresent(predictedCandles ->
                {
                    double value = predictedCandles.getLast().getClose();
                    System.out.println(value);
                    if (Math.abs(value) > 0.5) {
                        manager.open(DireccionOperation.parse(value),
                                (manager.getAvailableBalance() / 2)*Math.abs(value),
                                4
                        );
                    }
                }
        );
    }

    @Override
    public void closeOperation(TradingManager.ClosePosition closeOperation, TradingManager manager) {

    }

    @Override
    public @NotNull CandlesBuilder getBuilder(){
        return BuilderData.getProfierCandlesBuilder().addATRIndicator("atr", 14).addEMAIndicator("ema", 16);
    }

    @Override
    public StrategyConfig getStrategyConfig(TradingManager tradingManager) {
        return StrategyConfig.builder().condicionUseModelIA(() -> tradingManager.getOpenPosition().isEmpty()).futurePredict(1).build();
    }
}
