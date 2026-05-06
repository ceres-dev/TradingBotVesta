//package xyz.cereshost.vesta.core.strategy.strategis;
//
//import org.jetbrains.annotations.NotNull;
//import org.jetbrains.annotations.Nullable;
//import xyz.cereshost.vesta.core.ia.PredictionEngine;
//import xyz.cereshost.vesta.core.strategy.TradingStrategy;
//import xyz.cereshost.vesta.core.symbols.DireccionOperation;
//import xyz.cereshost.vesta.core.trading.TradingManager;
//import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
//import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;
//
//import java.util.Random;
//
//public class EtaStrategy implements TradingStrategy {
//
//    private static final int LEVERAGE = 4;
//    private boolean isUp = false;
//    private static final Random random = new Random();
//
//    @Override
//    public void executeStrategy(PredictionEngine.@Nullable SequenceCandlesPrediction pred, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations) {
//        double diff = ((visibleCandles.getCandleLast(1).get("ema") - visibleCandles.getCandleLast().get("ema"))/visibleCandles.getCandleLast().get("ema"))*100;
//        if(!operations.hasOpenOperation()){
//            operations.open(new TradingManager.RiskLimitsPercent(null, 0.4),
//                    random.nextBoolean() ? DireccionOperation.LONG : DireccionOperation.SHORT,
//                    operations.getAvailableBalance(),
//                    LEVERAGE
//            );
//        }else {
//            double sl = operations.getOpenPosition().getSlPercent();
//            double mul = sl < 0 ? 1.1 : 0.6;
//            operations.getOpenPosition().setSlPercent(Math.min(sl, sl - (diff*mul)));
//        }
//    }
//
//    @Override
//    public void closeOperation(TradingManager.ClosePosition closeOperation, TradingManager operations) {
//        isUp = closeOperation.isProfit() == closeOperation.getDireccion().isLong();
//    }
//
//    @Override
//    public @NotNull CandlesBuilder getBuilder(){
//        return new CandlesBuilder().addATRIndicator("atr", 14).addEMAIndicator("ema", 16);
//    }
//}
