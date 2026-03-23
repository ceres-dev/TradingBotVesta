package xyz.cereshost.vesta.core.strategys;

import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;

import java.util.List;

/**
 * Estrategia por defecto: Usa tal cual la predicción de la IA.
 */
public class MarketMakingStrategy implements TradingStrategy {
    private static final long COOLDOWN_MS = 0;// 5 * 60_000L;
    private static final double MIN_SPREAD_PCT = 0.0;
    private static final double BALANCE_FRACTION_PER_ORDER = 0.05;
    private static final double MIN_USD_PER_ORDER = 5.0;
    private static final int DEFAULT_LEVERAGE = 1;

    private long lastPlacedTime = 0L;

    @Override
    public void executeStrategy(PredictionEngine.PredictionResult pred, List<Candle> visibleCandles, TradingManager openOperations) {
        if (visibleCandles == null || visibleCandles.isEmpty()) return;
        if (openOperations.hasOpenOperation()) return;

        Candle last = visibleCandles.getLast();
        double mid = last.midPrice();
        double spread = last.spread();
        if (!Double.isFinite(mid) || !Double.isFinite(spread) || mid <= 0.0 || spread <= 0.0) {
            openOperations.log("Spred o mid con valores no soportados");
            return;
        }

        double spreadPct = (spread / mid) * 100.0;
        if (spreadPct < MIN_SPREAD_PCT) {
            openOperations.log(spreadPct + " < " + MIN_SPREAD_PCT);
            return;
        }

        long now = openOperations.getCurrentTime();
        if (now - lastPlacedTime < COOLDOWN_MS) {
            openOperations.log(now + " - " + lastPlacedTime + " < " + COOLDOWN_MS);
            return;
        }

        double balance = openOperations.getAvailableBalance();
        if (!Double.isFinite(balance) || balance < MIN_USD_PER_ORDER * 2.0) {
            openOperations.log(balance + " < " + MIN_USD_PER_ORDER + " * 2.0");
            return;
        }

        double bestBid = mid - (spread * 0.5);
        double bestAsk = mid + (spread * 0.5);

        double tpPercent = spreadPct;
        double slPercent = spreadPct * 2.0;

        openOperations.limit(bestBid, tpPercent, slPercent, DireccionOperation.LONG, 10, DEFAULT_LEVERAGE);
        openOperations.limit(bestAsk, tpPercent, slPercent, DireccionOperation.SHORT, 10, DEFAULT_LEVERAGE);

        openOperations.log(String.format("MarketMaking: bid %.4f ask %.4f spread %.4f (%.4f%%)", bestBid, bestAsk, spread, spreadPct));
        lastPlacedTime = now;
    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {

    }
}
