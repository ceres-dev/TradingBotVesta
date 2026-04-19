package xyz.cereshost.vesta.core.strategy;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.common.market.TypeMarket;
import xyz.cereshost.vesta.core.Main;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.util.List;
import java.util.Optional;

public interface TradingStrategy {

    void executeStrategy(@NotNull Optional<PredictionEngine.SequenceCandlesPrediction> prediction, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations);

    void closeOperation(TradingManager.ClosePosition closeOperation, TradingManager operations);

    default @NotNull CandlesBuilder getBuilder(){
        return new CandlesBuilder();
    };

    default List<TypeMarket> getMarketUse(){
        return Main.SYMBOLS_TRAINING;
    }

    default TypeMarket getMarketMaster(){
        return Main.TYPE_MARKET;
    }
}
