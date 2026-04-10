package xyz.cereshost.vesta.core.strategy.strategis;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

/**
 * Estrategia por defecto: Usa tal cual la predicción de la IA.
 */
public class DefaultStrategy implements TradingStrategy {

    boolean b = false;
    @Override
    public void executeStrategy(PredictionEngine.SequenceCandlesPrediction pred, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager openOperations) {
        openOperations.computeHasOpenOperation(op -> {
            if (op.getMinutesOpen() >= 3) openOperations.close(TradingManager.ExitReason.STRATEGY);
            op.setSlPercent(2);
            op.addTpPercent(1);
        });
        if (b) return;
        b = true;
        openOperations.open(
                new TradingManager.RiskLimitsPercent(1d, 1d).setLimit(true),
                DireccionOperation.LONG,
                openOperations.getAvailableBalance()/2,
                4
        );
    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {

    }
}
