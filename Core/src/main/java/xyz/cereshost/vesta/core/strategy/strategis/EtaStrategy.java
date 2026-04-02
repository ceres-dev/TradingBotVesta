package xyz.cereshost.vesta.core.strategy.strategys;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

public class EtaStrategy implements TradingStrategy {

    private static final int LEVERAGE = 4;

    @Override
    public void executeStrategy(PredictionEngine.@Nullable SequenceCandlesPrediction pred, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations) {

        boolean longSign = visibleCandles.getCandleLast().get("st") < visibleCandles.getLast().getClose() &&
                visibleCandles.getCandleLast(1).get("st") > visibleCandles.getLast(1).getClose();
        boolean shortSign = visibleCandles.getCandleLast().get("st") > visibleCandles.getLast().getClose() &&
                visibleCandles.getCandleLast(1).get("st") < visibleCandles.getLast(1).getClose();
        TradingManager.RiskLimits riskLimits = new TradingManager.RiskLimitsPercent(null, null);
        double usd = operations.getAvailableBalance()/2;
        operations.computeHasOpenOperation(operation -> {
            if (longSign && operation.getDireccion().isShort()) {
                operation.close();
            }
            if (shortSign && operation.getDireccion().isLong()) {
                operation.close();
            }

        });
        if (operations.hasOpenOperation()) return;
        if (shortSign) {
            operations.open(riskLimits,
                    DireccionOperation.SHORT,
                    usd,
                    LEVERAGE
            );
        }
        if (longSign) {
            operations.open(riskLimits,
                    DireccionOperation.LONG,
                    usd,
                    LEVERAGE
            );
        }

    }

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {
        TradingManager.RiskLimits riskLimits = new TradingManager.RiskLimitsPercent(null, null);
        operations.open(riskLimits,
                closeOperation.getDireccion().inverse(),
                operations.getAvailableBalance()/2,
                LEVERAGE
        );
    }

    @Override
    public @NotNull CandlesBuilder getBuilder(){
        return new CandlesBuilder().addSuperTrendIndicator("st", 15, 4);
    }
}
