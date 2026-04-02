package xyz.cereshost.vesta.core.strategy.strategis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

public class GammaStrategy implements TradingStrategy {

    @Override
    public void executeStrategy(PredictionEngine.@Nullable SequenceCandlesPrediction prediction, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations) {

    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {
    }
}
