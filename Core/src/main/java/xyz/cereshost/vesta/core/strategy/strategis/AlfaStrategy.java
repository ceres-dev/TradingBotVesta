package xyz.cereshost.vesta.core.strategy.strategis;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.StrategyConfig;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.strategy.TradingStrategyConfigurable;
import xyz.cereshost.vesta.core.strategy.TradingStrategyExecutor;
import xyz.cereshost.vesta.core.strategy.candles.ExecutorCandles;
import xyz.cereshost.vesta.core.strategy.candles.ExecutorCandlesBackTest;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.BuilderData;
import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class AlfaStrategy implements TradingStrategy, TradingStrategyConfigurable, TradingStrategyExecutor {

    @Override
    public void executeStrategy(@NotNull Optional<PredictionEngine.SequenceCandlesPrediction> optional, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations) {
//        if (operations.hasOpenOperation()) {
//            operations.close(TradingManager.ExitReason.STRATEGY);
//        }
        if (optional.isPresent()) {
            PredictionEngine.SequenceCandlesPrediction pred = optional.get();
            if (Math.abs(pred.getFirst().getClose()) > 0.8f)
                operations.open(DireccionOperation.parse(pred.getFirst().getClose()), operations.getAvailableBalance() / 2, 4);
        }

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


    @Override
    public @NotNull ExecutorCandles getExecutorCandles(@NotNull TradingManager tradingManager) {
        return new ExecutorCandlesBackTest().setStep("init")
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
