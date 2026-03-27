package xyz.cereshost.vesta.core.strategy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

/**
 * Interfaz para definir estrategias de trading personalizadas
 */
public interface TradingStrategy {

   void executeStrategy(@Nullable PredictionEngine.SequenceCandlesPrediction prediction, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations);

   void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations);

   default CandlesBuilder getBuilder(){
      return new CandlesBuilder();
   };
}

