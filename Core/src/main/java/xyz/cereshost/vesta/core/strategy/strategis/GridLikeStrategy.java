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
//public class GridLikeStrategy implements TradingStrategy {
//    private static final double GRID_POINT_PERCENT = 1;
//    private static final double BASE_ORDER_UNITS = 0.04;
//    private static final double BASE_ORDER_MARGIN_USDT = 10.0;
//    private static final double MARTINGALE_MULTIPLIER = 2;
//    private static final boolean ANTI_MARTINGALE = false;
//    private static final int LEVERAGE = 35;
//    private static final double MIN_ORDER_NOTIONAL = 5.0;
//
//    @Nullable
//    private Double baseline;
//    private double orderUnits = BASE_ORDER_UNITS;
//    @NotNull
//    private DireccionOperation pendingSignal = DireccionOperation.NEUTRAL;
//    private double pendingDistancePercent;
//
//    @Override
//    public void executeStrategy(PredictionEngine.@Nullable SequenceCandlesPrediction pred, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations) {
//        CandleIndicators current = visibleCandles.getCandleLast();
//        double close = current.getClose();
//        if (!Double.isFinite(close) || close <= 0) {
//            operations.log("EL cierre de la vela dio infinito");
//            return;
//        }
//
//        if (baseline == null || !Double.isFinite(baseline)) {
//            baseline = close;
//            operations.log("EL baseline dio infinito");
//            return;
//        }
//
//        double previousBaseline = baseline;
//        if (!Double.isFinite(previousBaseline) || previousBaseline <= 0) {
//            baseline = close;
//            operations.log("EL baseline dio infinito");
//            return;
//        }
//
//        double movePercent = ((close - previousBaseline) / previousBaseline) * 100.0;
//        if (!Double.isFinite(movePercent)) {
//            operations.log("EL movePercent dio infinito");
//            return;
//        }
//
//        if (Math.abs(movePercent) >= GRID_POINT_PERCENT) {
//            baseline = close;
//        }
//
//        DireccionOperation signal = getSignal(baseline, previousBaseline);
//        if (signal == DireccionOperation.NEUTRAL) {
//            operations.log("Momento no optimo para operar baseline: " + baseline + " previousBaseline: " + previousBaseline);
//            return;
//        }
//
//        double distancePercent = GRID_POINT_PERCENT;//* ((atrPercent - 0.01) * 0.1);// baselineMovePercent;
//        if (!Double.isFinite(distancePercent)) {
//            operations.log("EL distancePercent dio infinito");
//            return;
//        }
//
//        if (operations.hasOpenOperation()) {
//            operations.log("Ya hay una operacion abierta");
//            TradingManager.OpenPosition openPosition = operations.getOpenPosition();
//            if (openPosition.getFlags().contains("Beta")) {
//                openPosition.close();
//            }else {
//                return;
//            }
//        }
//
//        operations.open(
//                signal,
//                operations.getAvailableBalance()/2,
//                LEVERAGE
//        );
//    }
//
//
//    @Override
//    public void closeOperation(TradingManager.ClosePosition closeOperation, TradingManager operations) {
//        double pnlPercent = getClosedPnlPercent(closeOperation);
//        if (pnlPercent > 0) {
//            orderUnits = ANTI_MARTINGALE ? orderUnits * MARTINGALE_MULTIPLIER : BASE_ORDER_UNITS;
//        } else if (pnlPercent < 0) {
//            orderUnits = ANTI_MARTINGALE ? BASE_ORDER_UNITS : orderUnits * MARTINGALE_MULTIPLIER;
//        }
//
//        if (!Double.isFinite(orderUnits) || orderUnits <= 0) {
//            orderUnits = BASE_ORDER_UNITS;
//        }
//
//        if (closeOperation.getReason() != TradingManager.ExitReason.STRATEGY_INVERSION
//                || pendingSignal == DireccionOperation.NEUTRAL
//                || !Double.isFinite(pendingDistancePercent)
//                || pendingDistancePercent <= 0) {
//            return;
//        }
//
//        double minMarginUsdt = MIN_ORDER_NOTIONAL / Math.max(LEVERAGE, 1);
//        double amountUsdt = Math.max(orderUnits * BASE_ORDER_MARGIN_USDT, minMarginUsdt);
//        amountUsdt = Math.min(amountUsdt, operations.getAvailableBalance());
//        DireccionOperation signal = pendingSignal;
//        double distancePercent = pendingDistancePercent;
//        pendingSignal = DireccionOperation.NEUTRAL;
//        pendingDistancePercent = 0;
//
//        if (amountUsdt * LEVERAGE < MIN_ORDER_NOTIONAL) {
//            return;
//        }
//
//        operations.open(
//                signal,
//                amountUsdt,
//                LEVERAGE
//        );
//    }
//
//    @Override
//    public @NotNull CandlesBuilder getBuilder(){
//        return new CandlesBuilder().addATRIndicator("atr", 14).addRSIIndicator("rsi", 8);
//    }
//
//    @NotNull
//    private static DireccionOperation getSignal(double baseline, double previousBaseline) {
//        if (baseline > previousBaseline) {
//            return DireccionOperation.LONG;
//        }
//        if (baseline < previousBaseline) {
//            return DireccionOperation.SHORT;
//        }
//        return DireccionOperation.NEUTRAL;
//    }
//
//    private static double getClosedPnlPercent(@NotNull TradingManager.ClosePosition closeOperation) {
//        TradingManager.OpenPosition open = closeOperation.getOpenPosition();
//        double entry = open.getEntryPrice();
//        double exit = closeOperation.getExitPrice();
//        if (!Double.isFinite(entry) || !Double.isFinite(exit) || entry <= 0) {
//            return 0;
//        }
//
//        double movePercent = ((exit - entry) / entry) * 100.0;
//        return open.isUpDireccion() ? movePercent : -movePercent;
//    }
//}
