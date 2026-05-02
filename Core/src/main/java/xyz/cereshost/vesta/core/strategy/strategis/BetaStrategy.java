package xyz.cereshost.vesta.core.strategy.strategis;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.StrategyConfig;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.strategy.TradingStrategyConfigurable;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.trading.TypeOrder;
import xyz.cereshost.vesta.core.utils.StrategyUtils;
import xyz.cereshost.vesta.core.utils.candle.CandleIndicators;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.util.Optional;
import java.util.Random;

public class BetaStrategy implements TradingStrategy, TradingStrategyConfigurable {
    private final Random random = new Random(1);

    @Override
    public void executeStrategy(@NotNull Optional<PredictionEngine.SequenceCandlesPrediction> pred, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager manager) {
        CandleIndicators candle = visibleCandles.getCandleLast();
        manager.getOpenPosition().ifPresent(position -> {
            for (TradingManager.OrderAlgo orderAlgo : manager.getLimitAlgos()){
                double diffPercent = candle.getDiffPercent();
                if (orderAlgo.getDireccion().isLong()) {
                    orderAlgo.addTriggerPriceByPercent(diffPercent > 0 ?
                            diffPercent* 0.2 :
                            diffPercent* 0.8
                    );
                }else {
                    orderAlgo.addTriggerPriceByPercent(diffPercent < 0 ?
                            diffPercent* 0.2 :
                            diffPercent* 0.8
                    );
                }

            }
        });
        if (manager.getOpenPosition().isEmpty() && pred.isPresent()){
            Double quanty = manager.getAvailableBalance()/2;
            DireccionOperation Short = pred.get().getLast().get(0) > 0.2f ?  DireccionOperation.SHORT : null;
            DireccionOperation Long = pred.get().getLast().get(1) > 0.2f ?  DireccionOperation.LONG : null;
            if (Short != null ^ Long != null) {
                DireccionOperation direccionOperation = null;

                if (Short == DireccionOperation.SHORT) {
                    direccionOperation = DireccionOperation.SHORT;
                }

                if (Long == DireccionOperation.LONG) {
                    direccionOperation = DireccionOperation.LONG;
                }

                manager.open(direccionOperation,
                        quanty,
                        4
                );
                manager.limitAlgo(direccionOperation.inverse(), TypeOrder.STOP,
                        StrategyUtils.getPricePercent(manager, direccionOperation.inverse().isLong() ? .2d : -.2d),
                        4,
                        quanty
                );
            }

        }
    }

    @Override
    public StrategyConfig getStrategyConfig(TradingManager tradingManager) {
        return StrategyConfig.builder().condicionUseModelIA(() -> tradingManager.getOpenPosition().isEmpty()).futurePredict(1).build();
    }


    @Override
    public void closeOperation(TradingManager.ClosePosition closeOperation, TradingManager operations) {

    }

    private void open(TradingManager operations, DireccionOperation direction) {

    }
}
