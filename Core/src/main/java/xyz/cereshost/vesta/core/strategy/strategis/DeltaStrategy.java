//package xyz.cereshost.vesta.core.strategy.strategis;
//
//import org.jetbrains.annotations.NotNull;
//import org.jetbrains.annotations.Nullable;
//import xyz.cereshost.vesta.core.ia.PredictionEngine;
//import xyz.cereshost.vesta.core.strategy.StrategyConfig;
//import xyz.cereshost.vesta.core.strategy.TradingStrategy;
//import xyz.cereshost.vesta.core.strategy.TradingStrategyConfigurable;
//import xyz.cereshost.vesta.core.trading.DireccionOperation;
//import xyz.cereshost.vesta.core.trading.TradingManager;
//import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
//import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;
//
//public class DeltaStrategy implements TradingStrategyConfigurable, TradingStrategy {
//
//    private int waitCounter = 0;
//
//    @Override
//    public void executeStrategy(PredictionEngine.@Nullable SequenceCandlesPrediction prediction, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager manager) {
//        double closeOld = visibleCandles.getCandle(0).getClose();
//        double closeNew = visibleCandles.getLast().getClose();
//        if (waitCounter != 0) {
//            waitCounter--;
//            return;
//        }
//        double delta = ((closeNew - closeOld)/closeOld);
//        if (manager.hasOpenOperation()){
//            manager.close(TradingManager.ExitReason.STRATEGY);
//        }else {
//            TradingManager.RiskLimits riskLimits = new TradingManager.RiskLimitsPercent(null, null);
//            DireccionOperation direccionOperation;
//            if (delta > 0){
//                direccionOperation = DireccionOperation.LONG;
//            }else {
//                direccionOperation = DireccionOperation.SHORT;
//            }
//            waitCounter = (int) (60*12 * (visibleCandles.getCandleLast().get("atr")*0.05));
//            manager.open(riskLimits, direccionOperation, manager.getAvailableBalance()/2, 4);
//        }
//    }
//
//    @Override
//    public void closeOperation(TradingManager.ClosePosition closeOperation, TradingManager manager) {
//
//    }
//
//    @Override
//    public @NotNull CandlesBuilder getBuilder() {
//        return new CandlesBuilder().addSuperTrendIndicator("atr", 60*4, 1);
//    }
//
//    @Override
//    public StrategyConfig getStrategyConfig(TradingManager tradingManager) {
//        return StrategyConfig.builder().lookBack(60*12).build();
//    }
//}
