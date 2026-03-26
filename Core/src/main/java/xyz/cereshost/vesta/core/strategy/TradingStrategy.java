package xyz.cereshost.vesta.core.strategy;

import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.core.trading.TradingManager;

import java.util.List;

/**
 * Interfaz para definir estrategias de trading personalizadas
 */
public interface TradingStrategy {

   void executeStrategy(@Nullable PredictionEngine.PredictionResult prediction, List<Candle> visibleCandles, TradingManager operations);

   void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations);
}

