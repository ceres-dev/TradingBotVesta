//package xyz.cereshost.vesta.core.strategy.strategis;
//
//import org.jetbrains.annotations.NotNull;
//import org.jetbrains.annotations.Nullable;
//import xyz.cereshost.vesta.core.ia.PredictionEngine;
//import xyz.cereshost.vesta.core.strategy.TradingStrategy;
//import xyz.cereshost.vesta.core.trading.DireccionOperation;
//import xyz.cereshost.vesta.core.trading.TradingManager;
//import xyz.cereshost.vesta.core.utils.candle.CandleIndicators;
//import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
//import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;
//
//public class GammaStrategy implements TradingStrategy {
//
//    private int timeOut = 0;
//
//    @Override
//    public void executeStrategy(PredictionEngine.@Nullable SequenceCandlesPrediction prediction, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager manager) {
//
//        if (timeOut > 0) {
//            timeOut--;
//            return;
//        }
//        CandleIndicators current = visibleCandles.getCandleLast();
//        double currFast = current.get("fast");
//        double currSlow =  current.get("slow");
//        CandleIndicators prev = visibleCandles.getCandleLast(1);
//        double prevFast = prev.get("fast");
//        double prevSlow =  prev.get("slow");
//
//        boolean bullishCross = prevFast <= prevSlow && currFast > currSlow;
//        boolean bearishCross = prevFast >= prevSlow && currFast < currSlow;
//
//        TradingManager.RiskLimits riskLimits = new TradingManager.RiskLimitsAbsolute(null, null);
//        if (manager.hasOpenOperation()){
//            if (bullishCross && manager.getOpenPosition().getDireccion().isShort()) {
//                manager.close(TradingManager.ExitReason.STRATEGY);
//            }
//            if (bearishCross && manager.getOpenPosition().getDireccion().isLong()) {
//                manager.close(TradingManager.ExitReason.STRATEGY);
//            }
//        }
//        if (bullishCross) {
//            manager.open(riskLimits, DireccionOperation.LONG, manager.getAvailableBalance()/2, 3);
//            return;
//        }
//        if (bearishCross) {
//            manager.open(riskLimits, DireccionOperation.SHORT, manager.getAvailableBalance()/2, 3);
//        }
//
//    }
//
//    @Override
//    public void closeOperation(TradingManager.ClosePosition closeOperation, TradingManager manager) {
////        if (!closeOperation.isProfit()) timeOut = 60;
//    }
//
//    @Override
//    public @NotNull CandlesBuilder getBuilder() {
//        return new CandlesBuilder().addEMAIndicator("fast", 35).addWMAIndicator("slow", 15);
//    }
//}
