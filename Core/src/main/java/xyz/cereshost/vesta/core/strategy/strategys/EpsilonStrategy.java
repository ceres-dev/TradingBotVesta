//package xyz.cereshost.vesta.core.strategy.strategys;
//
//import org.jetbrains.annotations.Nullable;
//import xyz.cereshost.vesta.common.market.Candle;
//import xyz.cereshost.vesta.core.ia.PredictionEngine;
//import xyz.cereshost.vesta.core.strategy.TradingStrategy;
//import xyz.cereshost.vesta.core.trading.DireccionOperation;
//import xyz.cereshost.vesta.core.trading.TradingManager;
//
//import java.util.List;
//import java.util.Map;
//import java.util.Set;
//import java.util.UUID;
//import java.util.concurrent.ConcurrentHashMap;
//
//import static xyz.cereshost.vesta.core.util.StrategyUtils.highestHigh;
//import static xyz.cereshost.vesta.core.util.StrategyUtils.lowestLow;
//import static xyz.cereshost.vesta.core.util.StrategyUtils.priceDistanceToPercent;
//
//public class EpsilonStrategy implements TradingStrategy {
//
//    private static final int SL_PIPS = 200;
//    private static final int TP_PIPS = 400;
//    private static final int TRAIL_PIPS = 150;
//    private static final int LOOKBACK = 10;
//
//    private static final double PIP_SIZE = 0.0001;
//    private static final int LEVERAGE = 1;
//    private static final double ORDER_BALANCE_FRACTION = 1.0;
//    private static final double MIN_ORDER_NOTIONAL = 5.0;
//
//    private final Map<UUID, TrailingState> trailingStates = new ConcurrentHashMap<>();
//
//    @Override
//    public void executeStrategy(PredictionEngine.@Nullable PredictionResult pred, List<Candle> visibleCandles, TradingManager operations) {
//        if (visibleCandles == null || visibleCandles.size() < LOOKBACK + 2) {
//            return;
//        }
//
//        int lastIndex = visibleCandles.size() - 1;
//        int prevIndex = lastIndex - 1;
//        Candle current = visibleCandles.get(lastIndex);
//
//        double prevHighest = highestHigh(visibleCandles, prevIndex, LOOKBACK);
//        double prevLowest = lowestLow(visibleCandles, prevIndex, LOOKBACK);
//        if (!Double.isFinite(prevHighest) || !Double.isFinite(prevLowest)) {
//            return;
//        }
//
//        boolean bullishBOS = current.close() > prevHighest;
//        boolean bearishBOS = current.close() < prevLowest;
//
//        syncTrailingState(operations);
//        applyTrailingStop(operations, current);
//
//        if (bullishBOS) {
//            closeDirection(operations, DireccionOperation.SHORT);
//            if (!hasOpenDirection(operations, DireccionOperation.LONG)) {
//                openPosition(operations, DireccionOperation.LONG, current.close());
//            }
//        }
//        if (bearishBOS) {
//            closeDirection(operations, DireccionOperation.LONG);
//            if (!hasOpenDirection(operations, DireccionOperation.SHORT)) {
//                openPosition(operations, DireccionOperation.SHORT, current.close());
//            }
//        }
//    }
//
//    @Override
//    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {
//        trailingStates.remove(closeOperation.getUuid());
//    }
//
//    private void openPosition(TradingManager operations,
//                              DireccionOperation direction,
//                              double entryPrice) {
//        if (!Double.isFinite(entryPrice) || entryPrice <= 0.0) {
//            return;
//        }
//
//        double slPercent = priceDistanceToPercent(entryPrice, SL_PIPS * PIP_SIZE);
//        double tpPercent = priceDistanceToPercent(entryPrice, TP_PIPS * PIP_SIZE);
//        if (!Double.isFinite(slPercent) || !Double.isFinite(tpPercent)) {
//            return;
//        }
//
//        double available = operations.getAvailableBalance();
//        if (!Double.isFinite(available) || available <= 0.0) {
//            return;
//        }
//
//        double minMargin = MIN_ORDER_NOTIONAL / Math.max(1, LEVERAGE);
//        double amountUsdt = Math.max(available * ORDER_BALANCE_FRACTION, minMargin);
//        amountUsdt = Math.min(amountUsdt, available);
//        if (amountUsdt * LEVERAGE < MIN_ORDER_NOTIONAL) {
//            operations.log("Balance insuficiente para abrir EpsilonStrategy");
//            return;
//        }
//
//        TradingManager.OpenOperation open = operations.open(
//                tpPercent,
//                slPercent,
//                direction,
//                amountUsdt,
//                LEVERAGE
//        );
//        if (open != null) {
//            trailingStates.put(open.getUuid(), new TrailingState(open.getEntryPrice()));
//        }
//    }
//
//    private void applyTrailingStop(TradingManager operations, Candle current) {
//        double trailDistance = TRAIL_PIPS * PIP_SIZE;
//        double trailOffset = TRAIL_PIPS * PIP_SIZE;
//        if (!Double.isFinite(trailDistance) || !Double.isFinite(trailOffset)
//                || trailDistance <= 0.0 || trailOffset <= 0.0) {
//            return;
//        }
//
//        for (TradingManager.OpenOperation open : operations.getOpens()) {
//            TrailingState state = trailingStates.computeIfAbsent(open.getUuid(), id -> new TrailingState(open.getEntryPrice()));
//            if (open.isUpDireccion()) {
//                state.highestPrice = Math.max(state.highestPrice, current.high());
//                if (!state.activated && state.highestPrice >= (open.getEntryPrice() + trailOffset)) {
//                    state.activated = true;
//                }
//                if (!state.activated) {
//                    continue;
//                }
//
//                double trailStopPrice = state.highestPrice - trailDistance;
//                if (!Double.isFinite(trailStopPrice)) {
//                    continue;
//                }
//                if (trailStopPrice > open.getSlPrice()) {
//                    double newSlPercent = ((open.getEntryPrice() - trailStopPrice) / open.getEntryPrice()) * 100.0;
//                    if (Double.isFinite(newSlPercent)) {
//                        open.setSlPercent(newSlPercent);
//                    }
//                }
//            } else {
//                state.lowestPrice = Math.min(state.lowestPrice, current.low());
//                if (!state.activated && state.lowestPrice <= (open.getEntryPrice() - trailOffset)) {
//                    state.activated = true;
//                }
//                if (!state.activated) {
//                    continue;
//                }
//
//                double trailStopPrice = state.lowestPrice + trailDistance;
//                if (!Double.isFinite(trailStopPrice)) {
//                    continue;
//                }
//                if (trailStopPrice < open.getSlPrice()) {
//                    double newSlPercent = ((trailStopPrice - open.getEntryPrice()) / open.getEntryPrice()) * 100.0;
//                    if (Double.isFinite(newSlPercent)) {
//                        open.setSlPercent(newSlPercent);
//                    }
//                }
//            }
//        }
//    }
//
//    private static void closeDirection(TradingManager operations, DireccionOperation direction) {
//        for (TradingManager.OpenOperation open : operations.getOpens()) {
//            if (open.getDireccion() == direction) {
//                operations.close(TradingManager.ExitReason.STRATEGY_INVERSION, open);
//            }
//        }
//    }
//
//    private static boolean hasOpenDirection(TradingManager operations, DireccionOperation direction) {
//        for (TradingManager.OpenOperation open : operations.getOpens()) {
//            if (open.getDireccion() == direction) {
//                return true;
//            }
//        }
//        return false;
//    }
//
//    private void syncTrailingState(TradingManager operations) {
//        Set<UUID> active = ConcurrentHashMap.newKeySet();
//        for (TradingManager.OpenOperation open : operations.getOpens()) {
//            active.add(open.getUuid());
//            trailingStates.computeIfAbsent(open.getUuid(), id -> new TrailingState(open.getEntryPrice()));
//        }
//        trailingStates.keySet().removeIf(uuid -> !active.contains(uuid));
//    }
//
//    private static final class TrailingState {
//        private double highestPrice;
//        private double lowestPrice;
//        private boolean activated;
//
//        private TrailingState(double entryPrice) {
//            this.highestPrice = entryPrice;
//            this.lowestPrice = entryPrice;
//            this.activated = false;
//        }
//    }
//}
