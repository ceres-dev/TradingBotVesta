package xyz.cereshost.vesta.core.strategys;

import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.core.trading.TradingManager;

import java.util.List;

/**
 * Interfaz para definir estrategias de trading personalizadas
 */
public interface TradingStrategy {

   void executeStrategy(PredictionEngine.PredictionResult prediction, List<Candle> visibleCandles, TradingManager operations);

   void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations);
}

