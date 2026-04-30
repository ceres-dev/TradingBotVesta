package xyz.cereshost.vesta.core.strategy.strategis;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.util.Optional;

public class BetaStrategy implements TradingStrategy {

    @Override
    public void executeStrategy(@NotNull Optional<PredictionEngine.SequenceCandlesPrediction> pred, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations) {
        DireccionOperation direccionOperation = visibleCandles.getCandleLast().isBullish() ? DireccionOperation.LONG : DireccionOperation.SHORT;
        if (operations.getOpenPosition().isPresent()) {
            operations.close(TradingManager.ExitReason.STRATEGY);
        }
        operations.open(direccionOperation, operations.getAvailableBalance()/2, 4);
    }


    @Override
    public void closeOperation(TradingManager.ClosePosition closeOperation, TradingManager operations) {

    }

    private void open(TradingManager operations, DireccionOperation direction) {

    }
}
