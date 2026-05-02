package xyz.cereshost.vesta.core.trading;

import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.market.Market;
import xyz.cereshost.vesta.core.trading.backtest.BackTestEngine;

import java.util.*;
import java.util.function.Predicate;

@SuppressWarnings("unused")
public class TradingTelemetry {

    private double initialBalance;
    private double currentBalance;
    @Getter private double peakBalance;
    @Getter private double maxDrawdown;
    @Getter private double maxDrawdownPercent;
    @Getter private long startedAt;
    @Getter private long lastEventTime;

    private final @NotNull @Getter Market market;

    private final @NotNull Map<UUID, PendingObjectLifecycle> orders = new LinkedHashMap<>();
    private final @NotNull Map<UUID, PendingObjectLifecycle> orderAlgos = new LinkedHashMap<>();
    private final @NotNull Map<UUID, PositionLifecycle> positions = new LinkedHashMap<>();
    private final @NotNull List<TradeSnapshot> trades = new ArrayList<>();

    public TradingTelemetry(@NotNull Market market) {
        reset(0D, 0L);
        this.market = market;
    }

    public TradingTelemetry(BackTestEngine backTestEngine) {
        reset(backTestEngine.getBalance(), 0);
        this.market = backTestEngine.getMarketMaster();
    }


    public void reset(double initialBalance, long startedAt) {
        this.initialBalance = initialBalance;
        this.currentBalance = initialBalance;
        this.peakBalance = initialBalance;
        this.maxDrawdown = 0D;
        this.maxDrawdownPercent = 0D;
        this.startedAt = startedAt;
        this.lastEventTime = startedAt;
        this.orders.clear();
        this.orderAlgos.clear();
        this.positions.clear();
        this.trades.clear();
    }

    public void recordOrderCreated(@NotNull TradingManager.OrderSimple orderSimple, long createdTime) {
        orders.put(orderSimple.getUuid(), PendingObjectLifecycle.fromOrder(orderSimple, createdTime));
        lastEventTime = createdTime;
    }

    public void recordOrderAlgoCreated(@NotNull TradingManager.OrderAlgo orderAlgo, long createdTime) {
        orderAlgos.put(orderAlgo.getUuid(), PendingObjectLifecycle.fromOrderAlgo(orderAlgo, createdTime));
        lastEventTime = createdTime;
    }

    public void recordOrderCancelled(@NotNull UUID uuid, long closedTime) {
        closeLifecycle(orders.get(uuid), LifecycleStatus.CANCELLED, closedTime, null, null, "CANCELLED");
    }

    public void recordOrderAlgoCancelled(@NotNull UUID uuid, long closedTime, @NotNull String closeReason) {
        closeLifecycle(orderAlgos.get(uuid), LifecycleStatus.CANCELLED, closedTime, null, null, closeReason);
    }

    public void recordOrderFilled(@NotNull TradingManager.OrderSimple orderSimple,
                                  @NotNull UUID positionUuid,
                                  double fillPrice,
                                  long fillTime
    ) {
        PendingObjectLifecycle lifecycle = orders.computeIfAbsent(
                orderSimple.getUuid(),
                ignored -> PendingObjectLifecycle.fromOrder(orderSimple, fillTime)
        );
        closeLifecycle(lifecycle, LifecycleStatus.FILLED, fillTime, fillPrice, positionUuid, "FILLED");
    }

    public void recordOrderAlgoFilled(@NotNull TradingManager.OrderAlgo orderAlgo,
                                      @NotNull UUID positionUuid,
                                      double fillPrice,
                                      long fillTime
    ) {
        PendingObjectLifecycle lifecycle = orderAlgos.computeIfAbsent(
                orderAlgo.getUuid(),
                ignored -> PendingObjectLifecycle.fromOrderAlgo(orderAlgo, fillTime)
        );
        closeLifecycle(lifecycle, LifecycleStatus.FILLED, fillTime, fillPrice, positionUuid, "FILLED");
    }

    public void recordPositionOpened(@NotNull TradingManager.OpenPosition position) {
        positions.put(position.getUuid(), PositionLifecycle.fromOpenPosition(position));
        lastEventTime = position.getEntryTime();
    }

    public void recordPositionClosed(@NotNull TradingManager.ClosePosition closePosition,
                                     @NotNull TradePerformance performance
    ) {
        UUID positionUuid = closePosition.getOpenPosition().getUuid();
        PositionLifecycle lifecycle = positions.computeIfAbsent(
                positionUuid,
                ignored -> PositionLifecycle.fromOpenPosition(closePosition.getOpenPosition())
        );
        lifecycle.close(closePosition);

        LevelSnapshot closestTakeProfit = findClosestLevel(
                closePosition.getDireccion(),
                closePosition.getEntryTime(),
                closePosition.getExitTime(),
                closePosition.getTriggerPrice(),
                TypeOrder::isTakeProfit
        ).orElse(null);
        LevelSnapshot closestStopLoss = findClosestLevel(
                closePosition.getDireccion(),
                closePosition.getEntryTime(),
                closePosition.getExitTime(),
                closePosition.getTriggerPrice(),
                TypeOrder::isStopLoss
        ).orElse(null);

        trades.add(new TradeSnapshot(
                positionUuid,
                closePosition.getOrder() == null ? null : closePosition.getOrder().getUuid(),
                closePosition.getDireccion(),
                closePosition.getQuantity(),
                closePosition.getLeverage(),
                closePosition.getTriggerPrice(),
                closePosition.getExitPrice(),
                closePosition.getEntryTime(),
                closePosition.getExitTime(),
                closePosition.getExitTime() - closePosition.getEntryTime(),
                closePosition.getReason(),
                performance.grossPnl(),
                performance.netPnl(),
                performance.entryFee(),
                performance.exitFee(),
                performance.roiPercent(),
                performance.balanceAfterClose(),
                performance.netPnl() > 0D,
                closestTakeProfit,
                closestStopLoss,
                computeTpSlRatio(closePosition.getTriggerPrice(), closestTakeProfit, closestStopLoss)
        ));

        currentBalance = performance.balanceAfterClose();
        peakBalance = Math.max(peakBalance, currentBalance);
        double currentDrawdown = peakBalance - currentBalance;
        maxDrawdown = Math.max(maxDrawdown, currentDrawdown);
        if (peakBalance > 0D) {
            maxDrawdownPercent = Math.max(maxDrawdownPercent, (currentDrawdown / peakBalance) * 100D);
        }
        lastEventTime = closePosition.getExitTime();
    }

    public double getNetPnl() {
        return currentBalance - initialBalance;
    }

    public double getTotalRoi() {
        if (initialBalance == 0D) {
            return 0D;
        }
        return (getNetPnl() / initialBalance) * 100D;
    }

    public int getTotalTrades() {
        return trades.size();
    }

    public long getWinTrades() {
        return trades.stream().filter(TradeSnapshot::winner).count();
    }

    public long getLossTrades() {
        return trades.stream().filter(trade -> !trade.winner()).count();
    }

    public double getWinRate() {
        if (trades.isEmpty()) {
            return 0D;
        }
        return (getWinTrades() * 100D) / trades.size();
    }

    public double getAverageRoi() {
        return trades.stream().mapToDouble(TradeSnapshot::roiPercent).average().orElse(0D);
    }

    public double getAverageWinningRoi() {
        return trades.stream().filter(TradeSnapshot::winner).mapToDouble(TradeSnapshot::roiPercent).average().orElse(0D);
    }

    public double getAverageLosingRoi() {
        return trades.stream().filter(trade -> !trade.winner()).mapToDouble(TradeSnapshot::roiPercent).average().orElse(0D);
    }

    public long getAveragePositionOpenTimeMillis() {
        return Math.round(trades.stream().mapToLong(TradeSnapshot::durationMillis).average().orElse(0D));
    }

    public double getAverageTpSlRatio() {
        return trades.stream()
                .map(TradeSnapshot::tpSlRatio)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0D);
    }

    public @NotNull DirectionRatio getDirectionRatio() {
        long longTrades = trades.stream().filter(trade -> trade.direction() == DireccionOperation.LONG).count();
        long shortTrades = trades.stream().filter(trade -> trade.direction() == DireccionOperation.SHORT).count();
        long total = longTrades + shortTrades;
        double longShare = total == 0L ? 0D : (longTrades * 100D) / total;
        double shortShare = total == 0L ? 0D : (shortTrades * 100D) / total;
        double longShortRatio = shortTrades == 0L ? (longTrades == 0L ? 0D : Double.POSITIVE_INFINITY) : ((double) longTrades / shortTrades);
        return new DirectionRatio(longTrades, shortTrades, longShare, shortShare, longShortRatio);
    }

    public @NotNull Summary getSummary() {
        return new Summary(
                getWinRate(),
                getAveragePositionOpenTimeMillis(),
                getAverageTpSlRatio(),
                getTotalRoi(),
                getAverageRoi(),
                getAverageWinningRoi(),
                getAverageLosingRoi(),
                getNetPnl(),
                getTotalTrades(),
                getMaxDrawdown(),
                getMaxDrawdownPercent(),
                getDirectionRatio()
        );
    }

    public @NotNull List<TradeSnapshot> getTrades() {
        return List.copyOf(trades);
    }

    public @NotNull List<PositionSnapshot> getPositions() {
        return positions.values().stream()
                .map(PositionLifecycle::snapshot)
                .sorted(Comparator.comparingLong(PositionSnapshot::openedAt))
                .toList();
    }

    public @NotNull List<PendingObjectSnapshot> getOrders() {
        return orders.values().stream()
                .map(PendingObjectLifecycle::snapshot)
                .sorted(Comparator.comparingLong(PendingObjectSnapshot::openedAt))
                .toList();
    }

    public @NotNull List<PendingObjectSnapshot> getOrderAlgos() {
        return orderAlgos.values().stream()
                .map(PendingObjectLifecycle::snapshot)
                .sorted(Comparator.comparingLong(PendingObjectSnapshot::openedAt))
                .toList();
    }

    public @NotNull List<PendingObjectSnapshot> getTakeProfitLevels() {
        return getOrderAlgos().stream().filter(PendingObjectSnapshot::takeProfit).toList();
    }

    public @NotNull List<PendingObjectSnapshot> getStopLossLevels() {
        return getOrderAlgos().stream().filter(PendingObjectSnapshot::stopLoss).toList();
    }

    private void closeLifecycle(@Nullable PendingObjectLifecycle lifecycle,
                                @NotNull LifecycleStatus status,
                                long closedTime,
                                @Nullable Double fillPrice,
                                @Nullable UUID linkedPositionUuid,
                                @NotNull String closeReason
    ) {
        if (lifecycle == null || lifecycle.closedAt != null) {
            return;
        }
        lifecycle.status = status;
        lifecycle.closedAt = closedTime;
        lifecycle.fillPrice = fillPrice;
        lifecycle.linkedPositionUuid = linkedPositionUuid;
        lifecycle.closeReason = closeReason;
        lastEventTime = closedTime;
    }

    private @NotNull Optional<LevelSnapshot> findClosestLevel(@NotNull DireccionOperation direction,
                                                              long entryTime,
                                                              long exitTime,
                                                              double entryPrice,
                                                              @NotNull Predicate<TypeOrder> predicate
    ) {
        return orderAlgos.values().stream()
                .filter(level -> level.direction == direction)
                .filter(level -> predicate.test(level.typeOrder))
                .filter(level -> overlaps(entryTime, exitTime, level.openedAt, level.closedAt == null ? exitTime : level.closedAt))
                .min(Comparator.comparingDouble(level -> Math.abs(level.triggerPrice - entryPrice)))
                .map(PendingObjectLifecycle::toLevelSnapshot);
    }

    private static boolean overlaps(long startA, long endA, long startB, long endB) {
        return startA <= endB && startB <= endA;
    }

    private static @Nullable Double computeTpSlRatio(double entryPrice,
                                                     @Nullable LevelSnapshot takeProfit,
                                                     @Nullable LevelSnapshot stopLoss
    ) {
        if (takeProfit == null || stopLoss == null) {
            return null;
        }
        double tpDistance = Math.abs(takeProfit.triggerPrice() - entryPrice);
        double slDistance = Math.abs(entryPrice - stopLoss.triggerPrice());
        if (slDistance == 0D) {
            return null;
        }
        return tpDistance / slDistance;
    }

    public record TradePerformance(
            double grossPnl,
            double netPnl,
            double entryFee,
            double exitFee,
            double roiPercent,
            double balanceAfterClose
    ) {
    }

    public record Summary(
            double winRate,
            long averagePositionOpenTimeMillis,
            double averageTpSlRatio,
            double totalRoi,
            double averageRoi,
            double averageWinningRoi,
            double averageLosingRoi,
            double netPnl,
            int totalTrades,
            double maxDrawdown,
            double maxDrawdownPercent,
            @NotNull DirectionRatio directionRatio
    ) {
        @Contract(pure = true)
        public double performer() {
            return winRate - (Math.abs(averageLosingRoi) / (Math.abs(averageLosingRoi) + averageWinningRoi))*100;
        }
    }

    public record DirectionRatio(
            long longTrades,
            long shortTrades,
            double longShare,
            double shortShare,
            double longShortRatio
    ) {
    }

    public record LevelSnapshot(
            @NotNull UUID uuid,
            @NotNull TypeOrder typeOrder,
            double triggerPrice,
            long openedAt,
            @Nullable Long closedAt,
            @NotNull LifecycleStatus status
    ) {
    }

    public record PositionSnapshot(
            @NotNull UUID uuid,
            @Nullable UUID sourceOrderUuid,
            @NotNull DireccionOperation direction,
            double entryPrice,
            @Nullable Double exitPrice,
            double quantity,
            int leverage,
            long openedAt,
            @Nullable Long closedAt,
            @Nullable TradingManager.ExitReason exitReason
    ) {
        public long getOpenDurationMillis(long currentTime) {
            return (closedAt == null ? currentTime : closedAt) - openedAt;
        }
    }

    public record PendingObjectSnapshot(
            @NotNull UUID uuid,
            @Nullable UUID linkedPositionUuid,
            @NotNull PendingObjectKind kind,
            @NotNull DireccionOperation direction,
            @NotNull TypeOrder typeOrder,
            @NotNull List<TradingManager.PriceSnapshot> historiesTriggerPrices,
            @Nullable TimeInForce timeInForce,
            double triggerPrice,
            @Nullable Double quantity,
            @Nullable Integer leverage,
            boolean reduceOnly,
            long openedAt,
            @Nullable Long closedAt,
            @Nullable Double fillPrice,
            @NotNull LifecycleStatus status,
            @NotNull String closeReason
    ) {
        public boolean takeProfit() {
            return typeOrder.isTakeProfit();
        }

        public boolean stopLoss() {
            return typeOrder.isStopLoss();
        }

        public long getOpenDurationMillis(long currentTime) {
            return (closedAt == null ? currentTime : closedAt) - openedAt;
        }
    }

    public record TradeSnapshot(
            @NotNull UUID positionUuid,
            @Nullable UUID sourceOrderUuid,
            @NotNull DireccionOperation direction,
            double quantity,
            int leverage,
            double entryPrice,
            double exitPrice,
            long entryTime,
            long exitTime,
            long durationMillis,
            @NotNull TradingManager.ExitReason exitReason,
            double grossPnl,
            double netPnl,
            double entryFee,
            double exitFee,
            double roiPercent,
            double balanceAfterClose,
            boolean winner,
            @Nullable LevelSnapshot closestTakeProfit,
            @Nullable LevelSnapshot closestStopLoss,
            @Nullable Double tpSlRatio
    ) {
    }

    public enum PendingObjectKind {
        ORDER,
        ORDER_ALGO
    }

    public enum LifecycleStatus {
        OPEN,
        FILLED,
        CANCELLED
    }

    private static final class PendingObjectLifecycle {
        private final @NotNull UUID uuid;
        private final @NotNull PendingObjectKind kind;
        private final @NotNull DireccionOperation direction;
        private final @NotNull TypeOrder typeOrder;
        private final @NotNull List<TradingManager.PriceSnapshot> historyTriggerPrices;
        private final @Nullable TimeInForce timeInForce;
        private final double triggerPrice;
        private final @Nullable Double quantity;
        private final @Nullable Integer leverage;
        private final boolean reduceOnly;
        private final long openedAt;
        private @Nullable Long closedAt;
        private @Nullable UUID linkedPositionUuid;
        private @Nullable Double fillPrice;
        private @NotNull LifecycleStatus status;
        private @NotNull String closeReason;

        private PendingObjectLifecycle(@NotNull UUID uuid,
                                       @NotNull PendingObjectKind kind,
                                       @NotNull DireccionOperation direction,
                                       @NotNull TypeOrder typeOrder,
                                       @NotNull List<TradingManager.PriceSnapshot> historyTriggerPrices,
                                       @Nullable TimeInForce timeInForce,
                                       double triggerPrice,
                                       @Nullable Double quantity,
                                       @Nullable Integer leverage,
                                       boolean reduceOnly,
                                       long openedAt
        ) {
            this.uuid = uuid;
            this.kind = kind;
            this.direction = direction;
            this.typeOrder = typeOrder;
            this.historyTriggerPrices = historyTriggerPrices;
            this.timeInForce = timeInForce;
            this.triggerPrice = triggerPrice;
            this.quantity = quantity;
            this.leverage = leverage;
            this.reduceOnly = reduceOnly;
            this.openedAt = openedAt;
            this.status = LifecycleStatus.OPEN;
            this.closeReason = "OPEN";
        }

        private static @NotNull PendingObjectLifecycle fromOrder(@NotNull TradingManager.OrderSimple orderSimple, long openedAt) {
            return new PendingObjectLifecycle(
                    orderSimple.getUuid(),
                    PendingObjectKind.ORDER,
                    orderSimple.getDireccion(),
                    orderSimple.getTypeOrder(),
                    orderSimple.getHistoryTriggerPrices(),
                    orderSimple.getTimeInForce(),
                    orderSimple.getTriggerPrice(),
                    orderSimple.getQuantity(),
                    orderSimple.getLeverage(),
                    false,
                    openedAt
            );
        }

        private static @NotNull PendingObjectLifecycle fromOrderAlgo(@NotNull TradingManager.OrderAlgo orderAlgo, long openedAt) {
            return new PendingObjectLifecycle(
                    orderAlgo.getUuid(),
                    PendingObjectKind.ORDER_ALGO,
                    orderAlgo.getDireccion(),
                    orderAlgo.getTypeOrder(),
                    orderAlgo.getHistoryTriggerPrices(),
                    orderAlgo.getTimeInForce(),
                    orderAlgo.getTriggerPrice(),
                    orderAlgo.getQuantity(),
                    orderAlgo.getLeverage(),
                    orderAlgo.getReduceOnly(),
                    openedAt
            );
        }

        private @NotNull PendingObjectSnapshot snapshot() {
            return new PendingObjectSnapshot(
                    uuid,
                    linkedPositionUuid,
                    kind,
                    direction,
                    typeOrder,
                    historyTriggerPrices,
                    timeInForce,
                    triggerPrice,
                    quantity,
                    leverage,
                    reduceOnly,
                    openedAt,
                    closedAt,
                    fillPrice,
                    status,
                    closeReason
            );
        }

        private @NotNull LevelSnapshot toLevelSnapshot() {
            return new LevelSnapshot(uuid, typeOrder, triggerPrice, openedAt, closedAt, status);
        }
    }

    private static final class PositionLifecycle {
        private final @NotNull UUID uuid;
        private final @Nullable UUID sourceOrderUuid;
        private final @NotNull DireccionOperation direction;
        private final double entryPrice;
        private final double quantity;
        private final int leverage;
        private final long openedAt;
        private @Nullable Double exitPrice;
        private @Nullable Long closedAt;
        private @Nullable TradingManager.ExitReason exitReason;

        private PositionLifecycle(@NotNull UUID uuid,
                                  @Nullable UUID sourceOrderUuid,
                                  @NotNull DireccionOperation direction,
                                  double entryPrice,
                                  double quantity,
                                  int leverage,
                                  long openedAt
        ) {
            this.uuid = uuid;
            this.sourceOrderUuid = sourceOrderUuid;
            this.direction = direction;
            this.entryPrice = entryPrice;
            this.quantity = quantity;
            this.leverage = leverage;
            this.openedAt = openedAt;
        }

        private static @NotNull PositionLifecycle fromOpenPosition(@NotNull TradingManager.OpenPosition position) {
            return new PositionLifecycle(
                    position.getUuid(),
                    position.getOrder() == null ? null : position.getOrder().getUuid(),
                    position.getDireccion(),
                    position.getTriggerPrice(),
                    position.getQuantity(),
                    position.getLeverage(),
                    position.getEntryTime()
            );
        }

        private void close(@NotNull TradingManager.ClosePosition closePosition) {
            this.exitPrice = closePosition.getExitPrice();
            this.closedAt = closePosition.getExitTime();
            this.exitReason = closePosition.getReason();
        }

        private @NotNull PositionSnapshot snapshot() {
            return new PositionSnapshot(
                    uuid,
                    sourceOrderUuid,
                    direction,
                    entryPrice,
                    exitPrice,
                    quantity,
                    leverage,
                    openedAt,
                    closedAt,
                    exitReason
            );
        }
    }
}
