//package xyz.cereshost.vesta.core.strategy.strategis;
//
//import org.jetbrains.annotations.NotNull;
//import org.jetbrains.annotations.Nullable;
//import xyz.cereshost.vesta.common.market.Candle;
//import xyz.cereshost.vesta.core.ia.PredictionEngine;
//import xyz.cereshost.vesta.core.strategy.TradingStrategy;
//import xyz.cereshost.vesta.core.trading.DireccionOperation;
//import xyz.cereshost.vesta.core.trading.TradingManager;
//import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
//import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;
//
//import java.util.Optional;
//
//public class BetaStrategy implements TradingStrategy {
//
//    private static final MaType MA_TYPE = MaType.WMA;
//    private static final int MA_LEN = 60;
//    private static final Source MA_SOURCE = Source.CLOSE;
//
//    private static final MaType ATR_TYPE = MaType.SMA;
//    private static final double ATR_MULT = 3;
//    private static final int ATR_LEN = 15;
//
//    private static final int CONFIRM_BARS = 3;
//    private static final ConfirmSource CONFIRM_SOURCE = ConfirmSource.LOW_HIGH;
//
//    private static final EntryMethod ENTRY_METHOD = EntryMethod.MA_CROSS;
//    private static final double TP_PERCENT = 0.5;
//    private static final double SL_PERCENT = 0.1;
//
//    private static final int LEVERAGE = 4;
////    private static final double ORDER_BALANCE_FRACTION = 0.2;
////    private static final double MIN_ORDER_NOTIONAL = 5.2;
//
//    private int trend = 0;
//    private int breakPoint = 0;
//    private boolean crossUsed = false;
//    private int uptrendBars = 0;
//    private int downtrendBars = 0;
//    boolean isPeekClose = false;
//
//    @Override
//    public @NotNull CandlesBuilder getBuilder(){
//        return new CandlesBuilder().addWMAIndicator("ma", MA_LEN).addATRIndicator("atrVal", ATR_LEN);
//    }
//
//    @Override
//    public void executeStrategy(Optional<PredictionEngine.SequenceCandlesPrediction> pred, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations) {
//        if (visibleCandles.size() < Math.max(MA_LEN, ATR_LEN) + 3) {
//            return;
//        }
//        if (couldDown > 0) couldDown--;
//
//        int lastIndex = visibleCandles.size() - 1;
//        int prevIndex = lastIndex - 1;
//
//        Candle curr = visibleCandles.get(lastIndex);
//        Candle prev = visibleCandles.get(prevIndex);
//
//        double maVal = visibleCandles.getCandleLast().get("ma");
//        double atrVal =visibleCandles.getCandleLast().get("atrVal");
//        if (!Double.isFinite(maVal) || !Double.isFinite(atrVal)) {
//            return;
//        }
//
//        double atrUpper = maVal + (atrVal * ATR_MULT);
//        double atrLower = maVal - (atrVal * ATR_MULT);
//
//        double uptrendPrice = CONFIRM_SOURCE == ConfirmSource.CLOSE ? curr.getClose() : curr.getHigh();
//        double downtrendPrice = CONFIRM_SOURCE == ConfirmSource.CLOSE ? curr.getClose() : curr.getLow();
//
//        boolean isUptrendRaw = uptrendPrice > atrUpper;
//        boolean isDowntrendRaw = downtrendPrice < atrLower;
//
//        uptrendBars = isUptrendRaw ? uptrendBars + 1 : 0;
//        downtrendBars = isDowntrendRaw ? downtrendBars + 1 : 0;
//
//        boolean isUptrendConfirmed = uptrendBars >= CONFIRM_BARS;
//        boolean isDowntrendConfirmed = downtrendBars >= CONFIRM_BARS;
//
//        if (isUptrendConfirmed && trend != 1) {
//            trend = 1;
//            breakPoint = 1;
//            crossUsed = false;
//        }
//        if (isDowntrendConfirmed && trend != -1) {
//            trend = -1;
//            breakPoint = -1;
//            crossUsed = false;
//        }
//
//        if (ENTRY_METHOD == EntryMethod.BREAKOUT) {
//            if (breakPoint == 1) {
//                open(operations, DireccionOperation.LONG);
//            } else if (breakPoint == -1) {
//                open(operations, DireccionOperation.SHORT);
//            }
//        } else {
//            if (crossUsed || prevIndex - 1 < 0) {
//                return;
//            }
//
//            double maPrev = visibleCandles.getCandle(prevIndex - 1).get("ma");
//            if (!Double.isFinite(maPrev)) {
//                return;
//            }
//
//            boolean crossUnder = prev.getClose() >= maPrev && curr.getClose() < maVal;
//            boolean crossOver = prev.getClose() <= maPrev && curr.getClose() > maVal;
//
//            if (trend == 1 && crossUnder) {
//                open(operations, DireccionOperation.LONG);
//                crossUsed = true;
//            } else if (trend == -1 && crossOver) {
//                open(operations, DireccionOperation.SHORT);
//                crossUsed = true;
//            }
//        }
//
//    }
//
//    private short strikeLosses = 0;
//    private int couldDown = 0;
//
//    @Override
//    public void closeOperation(TradingManager.ClosePosition closeOperation, TradingManager operations) {
//        if (closeOperation.getReason().isTakeProfit()) {
//            strikeLosses = 0;
//        }else {
//            strikeLosses++;
//        }
//        if (strikeLosses >= 3) {
//            couldDown = 60*12;
//        }
//    }
//
//    private void open(TradingManager operations, DireccionOperation direction) {
//        double availableBalance = operations.getAvailableBalance();
//        if (!Double.isFinite(availableBalance) || availableBalance <= 0.0) {
//            return;
//        }
//
//        TradingManager.OpenPosition open = operations.open(direction, availableBalance/2, LEVERAGE);
//        if (open != null) open.getFlags().add("Beta");
//    }
//
//    private enum MaType {
//        EMA,
//        SMA,
//        WMA,
//        HMA,
//        VWMA,
//        RMA,
//        TEMA
//    }
//
//    private enum EntryMethod {
//        BREAKOUT,
//        MA_CROSS
//    }
//
//    private enum ConfirmSource {
//        CLOSE,
//        LOW_HIGH
//    }
//
//    private enum Source {
//        CLOSE
//    }
//}
