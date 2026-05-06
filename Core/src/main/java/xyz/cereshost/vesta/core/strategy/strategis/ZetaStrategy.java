//package xyz.cereshost.vesta.core.strategy.strategis;
//
//import org.jetbrains.annotations.NotNull;
//import org.jetbrains.annotations.Nullable;
//import xyz.cereshost.vesta.core.ia.PredictionEngine;
//import xyz.cereshost.vesta.core.strategy.TradingStrategy;
//import xyz.cereshost.vesta.core.symbols.DireccionOperation;
//import xyz.cereshost.vesta.core.trading.TradingManager;
//import xyz.cereshost.vesta.core.utils.candle.CandleIndicators;
//import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
//import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;
//
//public class ZetaStrategy implements TradingStrategy {
//    // TradingView inputs:
//    // point = 32, os = 1, mf = 2, anti = false
//    private static final double POINT = 25;
//    private static final double ORDER_SIZE_BASE = 1.0;
//    private static final double MARTINGALE_MULTIPLIER = 2.0;
//    private static final boolean ANTI_MARTINGALE = true;
//
//    // Mapping interno qty -> margen USD (el engine opera por amountUSD + leverage)
//    private static final double BASE_MARGIN_USD_PER_QTY = 10.0;
//    private static final int LEVERAGE = 4;
//
//    @Nullable
//    private Double baseline;
//    private double size = ORDER_SIZE_BASE;
//
//    @Override
//    public void executeStrategy(PredictionEngine.@Nullable SequenceCandlesPrediction prediction, @NotNull SequenceCandles visibleCandles, @NotNull TradingManager operations) {
//        CandleIndicators current = visibleCandles.getCandleLast();
//        // Fuente de precio usando indicador tecnico construido por CandlesBuilder
//        double close = current.getClose();
//        double ema = Math.abs(current.get("close_ema_low") - visibleCandles.getCandleLast(1).get("close_ema_low"));
//        double atr = current.get("atr");
//        if (!Double.isFinite(close) || close <= 0.0) {
//            return;
//        }
//
//        if (baseline == null || !Double.isFinite(baseline)) {
//            baseline = close;
//            return;
//        }
//
//        double previousBaseline = baseline;
//        if (close > previousBaseline + POINT || close < previousBaseline - POINT) {
//            baseline = close;
//        }
//
//        double newBaseline = baseline;
//        if (!Double.isFinite(newBaseline) || !Double.isFinite(previousBaseline)) {
//            return;
//        }
//
//        DireccionOperation signal = DireccionOperation.NEUTRAL;
//        if (newBaseline > previousBaseline) {
//            signal = DireccionOperation.LONG;
//        } else if (newBaseline < previousBaseline) {
//            signal = DireccionOperation.SHORT;
//        }
//
//        if (signal == DireccionOperation.NEUTRAL) {
//            return;
//        }
//
//        double upper = newBaseline + POINT;
//        double lower = newBaseline - POINT;
//        if (!Double.isFinite(upper) || !Double.isFinite(lower)) {
//            return;
//        }
//
//        if (ema < 0.7) return;
////        if (atr < 9 || atr > 17) return;
//
//        if (operations.hasOpenOperation()) {
//            TradingManager.OpenPosition open = operations.getOpenPosition();
//            if (open != null && open.getDireccion() != signal) {
//                open.close(TradingManager.ExitReason.STRATEGY_INVERSION);
//            }
//            return;
//        }
//
//        double requestedMargin = BASE_MARGIN_USD_PER_QTY * Math.max(size, ORDER_SIZE_BASE);
//        double available = operations.getAvailableBalance();
//        double amountUsd = Math.min(requestedMargin, available);
//        if (!Double.isFinite(amountUsd) || amountUsd <= 0.0) {
//            return;
//        }
////        TradingManager.RiskLimits riskLimits = new TradingManager.RiskLimitsAbsolute(null, null);
//        TradingManager.RiskLimits riskLimits = signal.isLong()
//                ? new TradingManager.RiskLimitsAbsolute(upper, null)
//                : new TradingManager.RiskLimitsAbsolute(lower, null);
//
//        operations.open(riskLimits, signal, amountUsd, LEVERAGE);
//    }
//
//    @Override
//    public void closeOperation(TradingManager.ClosePosition closeOperation, TradingManager operations) {
//        double pnlPercent = getClosedPnlPercent(closeOperation);
//
//        if (ANTI_MARTINGALE) {
//            size = pnlPercent > 0.0 ? size * MARTINGALE_MULTIPLIER : ORDER_SIZE_BASE;
//        } else {
//            size = pnlPercent < 0.0 ? size * MARTINGALE_MULTIPLIER : ORDER_SIZE_BASE;
//        }
//
//        if (!Double.isFinite(size) || size <= 0.0) {
//            size = ORDER_SIZE_BASE;
//        }
//    }
//
//    @Override
//    public @NotNull CandlesBuilder getBuilder() {
//        return new CandlesBuilder().addEMAIndicator("close_ema_low", 35).addATRIndicator("atr", 14);
//    }
//
//    private static double getClosedPnlPercent(@NotNull TradingManager.ClosePosition closeOperation) {
//        TradingManager.OpenPosition open = closeOperation.getOpenPosition();
//        double entry = open.getEntryPrice();
//        double exit = closeOperation.getExitPrice();
//        if (!Double.isFinite(entry) || !Double.isFinite(exit) || entry <= 0.0) {
//            return 0.0;
//        }
//
//        double movePercent = ((exit - entry) / entry) * 100.0;
//        return open.isUpDireccion() ? movePercent : -movePercent;
//    }
//}
