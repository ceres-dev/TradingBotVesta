package xyz.cereshost.vesta.core.strategy.strategis;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.StrategyConfig;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.strategy.TradingStrategyConfigurable;
import xyz.cereshost.vesta.core.market.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.trading.TypeOrder;
import xyz.cereshost.vesta.core.utils.StrategyUtils;
import xyz.cereshost.vesta.core.utils.candle.CandleIndicators;
import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
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
                if (position.isProfit()){
                    if (orderAlgo.getDireccion().isLong()) {
                        orderAlgo.addTriggerPriceByPercent(diffPercent > 0 ?
                                diffPercent* 0.6 :
                                diffPercent* 1.5
                        );
                    }else {
                        orderAlgo.addTriggerPriceByPercent(diffPercent < 0 ?
                                diffPercent* 0.6 :
                                diffPercent* 1.5
                        );
                    }
                }else {
                    if (orderAlgo.getDireccion().isLong()) {
                        orderAlgo.addTriggerPriceByPercent(diffPercent > 0 ?
                                diffPercent* 0.4 :
                                diffPercent* 0.8
                        );
                    }else {
                        orderAlgo.addTriggerPriceByPercent(diffPercent < 0 ?
                                diffPercent* 0.4 :
                                diffPercent* 0.8
                        );
                    }
                }

            }
        });
        if (manager.getOpenPosition().isEmpty() && pred.isPresent() && Math.abs(pred.get().getLast().get(0)) > 0.7){
            Double quanty = manager.getAvailableBalance()/2;
            DireccionOperation direccionOperation = DireccionOperation.parse(pred.get().getLast().get(0));
            manager.open(direccionOperation,
                        quanty,
                        4
                );
                manager.limitAlgo(direccionOperation.inverse(), TypeOrder.STOP,
                        StrategyUtils.getPricePercent(manager, direccionOperation.inverse().isLong() ? .4d : -.4d),
                        4,
                        quanty
                );
        }
    }

    @Override
    public StrategyConfig getStrategyConfig(TradingManager tradingManager) {
        return StrategyConfig.builder().condicionUseModelIA(() -> tradingManager.getOpenPosition().isEmpty()).futurePredict(1).build();
    }

    @Override
    public @NotNull CandlesBuilder getBuilder(){
        return new CandlesBuilder().addRSIIndicator("rsi", 14);
    }


    @Override
    public void closeOperation(TradingManager.ClosePosition closeOperation, TradingManager operations) {

    }

    private void open(TradingManager operations, DireccionOperation direction) {

    }
}
