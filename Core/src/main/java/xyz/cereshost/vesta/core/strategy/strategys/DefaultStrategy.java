package xyz.cereshost.vesta.core.strategy.strategys;

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
    @Override
    public void executeStrategy(PredictionEngine.SequenceCandlesPrediction pred, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager openOperations) {
        for (TradingManager.OpenOperation op : openOperations.getOpens()) if (op.getMinutesOpen() >= 30) openOperations.close(TradingManager.ExitReason.STRATEGY, op);
        if (!openOperations.hasOpenOperation() && pred != null) {
            openOperations.open(
                    1,
                    1,
                    DireccionOperation.LONG,
                    openOperations.getAvailableBalance()/2,
                    4
            );
        }
    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {

    }
}
