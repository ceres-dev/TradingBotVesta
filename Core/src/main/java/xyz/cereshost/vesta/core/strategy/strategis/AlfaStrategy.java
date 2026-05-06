package xyz.cereshost.vesta.core.strategy.strategis;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.StrategyConfig;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.strategy.TradingStrategyConfigurable;
import xyz.cereshost.vesta.core.strategy.candles.ExecutorCandles;
import xyz.cereshost.vesta.core.strategy.candles.ExecutorCandlesBackTest;
import xyz.cereshost.vesta.core.market.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.BuilderData;
import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class AlfaStrategy implements TradingStrategy, TradingStrategyConfigurable {

    @Override
    public void executeStrategy(@NotNull Optional<PredictionEngine.SequenceCandlesPrediction> optional,
                                @NotNull SequenceCandles visibleCandles,
                                @NotNull TradingManager manager
    ) {
        if (manager.getOpenPosition().isPresent()) {
            manager.close(TradingManager.ExitReason.STRATEGY);
        }
        optional.ifPresent(predictedCandles -> manager.open(DireccionOperation.parse(predictedCandles.getLast().get(0)),
                        manager.getAvailableBalance() / 2,
                        4
                )
        );
    }

    @Override
    public void closeOperation(TradingManager.ClosePosition closeOperation, TradingManager operations) {

    }

    @Override
    public StrategyConfig getStrategyConfig(TradingManager tradingManager) {
        return StrategyConfig.builder().condicionUseModelIA(() -> tradingManager.getOpenPosition().isEmpty()).futurePredict(1).build();
    }

    @Override
    public @NotNull CandlesBuilder getBuilder(){
        return BuilderData.getProfierCandlesBuilder().addATRIndicator("atr", 14).addEMAIndicator("ema", 16);
    }


    public @NotNull ExecutorCandles getExecutorCandles(@NotNull TradingManager tradingManager) {
        return new ExecutorCandlesBackTest(tradingManager).setStep("init")
                .pause(TimeUnit.MINUTES.toMillis(1))
                .executeReturnStep((manager) -> {
            if (manager.getOpenPosition().isPresent()){
                return Optional.of("init");
            }else {
                return Optional.of("died");
            }
        }).setStep("died").died();
    }
}
