package xyz.cereshost.vesta.core.strategys;

import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KappaStrategy implements TradingStrategy {

    @Override
    public void executeStrategy(PredictionEngine.PredictionResult pred, List<Candle> visibleCandles, TradingManager operations) {

    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {

    }
}
