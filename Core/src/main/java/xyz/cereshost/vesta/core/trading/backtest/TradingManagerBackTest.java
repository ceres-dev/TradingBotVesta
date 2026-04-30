package xyz.cereshost.vesta.core.trading.backtest;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.market.Market;
import xyz.cereshost.vesta.core.message.MediaNotification;
import xyz.cereshost.vesta.core.trading.*;

import java.util.*;

public class TradingManagerBackTest implements TradingManager {

    private @Nullable TradingManagerBackTest.BackTestOpenPosition openOperation = null;
    private final @Nullable TradingTelemetry telemetry;
    @Getter
    private final @NotNull HashMap<UUID, LimitedPosition> pendingOrder = new HashMap<>();
    private final @NotNull BackTestEngine backTestEngine;

    public TradingManagerBackTest(@NotNull BackTestEngine backTestEngine) {
        this.backTestEngine = backTestEngine;
        this.telemetry = new TradingTelemetry(backTestEngine);
    }

    @Override
    public @NotNull Integer pendingOrderSize() {
        return pendingOrder.size();
    }

    @Override
    public @NotNull Optional<TradingManager.OpenPosition> getOpenPosition() {
        return Optional.ofNullable(openOperation);
    }

    @Override
    public @NotNull Optional<TradingManager.OpenPosition> open(@NotNull DireccionOperation direccion, @NotNull Double quantity, @NotNull Integer leverage) {
        // Lo minimo para invertir en Binance

        if (quantity * leverage < 5) return Optional.empty();
        double currentPrice = backTestEngine.getCurrentPrice();

        // Simular el bin y el ask al comprar en mercado
//        double realPrice = direccion == DireccionOperation.LONG ? currentPrice + 0.0001 : currentPrice - 0.0001;
        BackTestOpenPosition o = new BackTestOpenPosition(this, currentPrice, direccion, quantity, leverage, null);
        openForEngine(o);
        return Optional.of(o);
    }

    @Override
    public @NotNull Optional<Order> limit(@NotNull DireccionOperation direccion,
                                          @NotNull Double trigger,
                                          @NotNull Double quantity,
                                          @NotNull Integer leverage,
                                          @NotNull TypeOrder typeOrder,
                                          @NotNull TimeInForce timeInForce
    ) {
        Order limit = new BackTestOrder(
                this, direccion, trigger, quantity, leverage, typeOrder, timeInForce
        );
        pendingOrder.put(limit.getUuid(), limit);
        if (telemetry != null) {
            telemetry.recordOrderCreated(limit, backTestEngine.getCurrentTime());
        }
        return Optional.of(limit);
    }

    @Override
    public @NotNull Optional<OrderAlgo> limitAlgo(@NotNull DireccionOperation side,
                                                  @NotNull TypeOrder type,
                                                  @NotNull Double trigger,
                                                  @Nullable Integer leverage,
                                                  @Nullable Double quantity,
                                                  @NotNull TimeInForce timeInForce, @NotNull Boolean reduceOnly
    ) {
        OrderAlgo limit = new BackTestOrderAlgo(
                this, side, trigger, quantity, leverage, reduceOnly, type, timeInForce
        );
        pendingOrder.put(limit.getUuid(), limit);
        if (telemetry != null) {
            telemetry.recordOrderAlgoCreated(limit, backTestEngine.getCurrentTime());
        }
        return Optional.of(limit);
    }

    @Override
    public @NotNull Optional<TradingManager.ClosePosition> close(ExitReason reason) {
        if (openOperation == null) return Optional.empty();
        BackTestClosePosition closeOperation = new BackTestClosePosition(backTestEngine.getCurrentPrice(),
                backTestEngine.getCurrentTime(),
                reason,
                openOperation
        );
        TradingTelemetry.TradePerformance performance = backTestEngine.computeClose(
                closeOperation.getExitPrice(),
                openOperation,
                false
        );
        return closeForEngine(closeOperation, performance);
    }

    @Override
    public void cancelOrder(UUID uuid) {
        LimitedPosition removed = pendingOrder.remove(uuid);
        if (telemetry == null || removed == null) {
            return;
        }
        long currentTime = backTestEngine.getCurrentTime();
        if (removed instanceof Order) {
            telemetry.recordOrderCancelled(uuid, currentTime);
            return;
        }
        telemetry.recordOrderAlgoCancelled(uuid, currentTime, "CANCELLED");
    }

    @Override
    public void cancelAllOrder() {
        List<UUID> uuidsForRemover = pendingOrder.values().stream().filter(order ->
                order instanceof Order
        ).map(order ->
                (MarketObject<?>) order
        ).map(MarketObject::getUuid).toList();
        uuidsForRemover.forEach(this::cancelOrder);
    }

    @Override
    public void cancelAllOrderAlgo() {
        List<UUID> uuidsForRemover = pendingOrder.values().stream().filter(order ->
                order instanceof OrderAlgo
        ).map(order ->
                (MarketObject<?>) order
        ).map(MarketObject::getUuid).toList();
        uuidsForRemover.forEach(this::cancelOrder);
    }

    public Optional<TradingManager.ClosePosition> closeForEngine(@NotNull BackTestClosePosition closeOperation,
                                                                 @NotNull TradingTelemetry.TradePerformance performance
    ){
        List<OrderAlgo> activeOrderAlgos = getLimitAlgos();
        openOperation = null;
        for (OrderAlgo orderAlgo : activeOrderAlgos) {
            pendingOrder.remove(orderAlgo.getUuid());
            if (telemetry != null) {
                telemetry.recordOrderAlgoCancelled(orderAlgo.getUuid(), closeOperation.getExitTime(), "POSITION_CLOSED");
            }
        }
        if (telemetry != null) {
            telemetry.recordPositionClosed(closeOperation, performance);
        }
        backTestEngine.getStrategy().closeOperation(closeOperation, this);
        return Optional.of(closeOperation);
    }

    public void closeInverseForEngine(Double quanty, LimitedPosition limited) {
        if (openOperation == null) return;

        BackTestOpenPosition o = new BackTestOpenPosition(this,
                limited.getTriggerPrice(),
                openOperation.getDireccion().inverse(),
                quanty,
                openOperation.getLeverage(),
                limited
        );
        BackTestClosePosition c = new BackTestClosePosition(limited.getTriggerPrice(),
                getCurrentTime(),
                ExitReason.INVERSION,
                o
        );
        TradingTelemetry.TradePerformance performance = backTestEngine.computeClose(
                limited.getTriggerPrice(),
                openOperation,
                false
        );
        closeForEngine(c, performance);
        openForEngine(o);
    }

    public void openForEngine(@Nullable TradingManagerBackTest.BackTestOpenPosition openOperation) {
        this.openOperation = openOperation;
        if (openOperation == null) {
            return;
        }
        Order sourceOrder = openOperation.getOrder();
        if (sourceOrder != null) {
            pendingOrder.remove(sourceOrder.getUuid());
            if (telemetry != null) {
                telemetry.recordOrderFilled(
                        sourceOrder,
                        openOperation.getUuid(),
                        openOperation.getTriggerPrice(),
                        openOperation.getEntryTime()
                );
            }
        }
        if (telemetry != null) {
            telemetry.recordPositionOpened(openOperation);
        }
    }

    public void fillOrderAlgo(@NotNull OrderAlgo orderAlgo,
                              @NotNull UUID positionUuid,
                              double fillPrice,
                              long fillTime
    ) {
        pendingOrder.remove(orderAlgo.getUuid());
        if (telemetry != null) {
            telemetry.recordOrderAlgoFilled(orderAlgo, positionUuid, fillPrice, fillTime);
        }
    }

    @Override
    public @NotNull List<Order> getOrder() {
        return pendingOrder.values().stream().filter(order ->
                order instanceof Order
        ).map(order ->
                (Order) order
        ).toList();
    }

    @Override
    public @NotNull List<OrderAlgo> getLimitAlgos() {
        return pendingOrder.values().stream().filter(order ->
                order instanceof OrderAlgo
        ).map(order ->
                (OrderAlgo) order
        ).toList();
    }

    @Override
    public @NotNull Optional<TradingManager.OrderAlgo> getTakeProfit() {
        return pendingOrder.values().stream().filter(order ->
                order instanceof OrderAlgo
        ).map(order ->
                (OrderAlgo) order
        ).filter(order ->
                order.getTypeOrder().isTakeProfit()
        ).findAny();
    }

    @Override
    public @NotNull Optional<OrderAlgo> getStopLoss() {
        return pendingOrder.values().stream().filter(order ->
                order instanceof OrderAlgo
        ).map(order ->
                (OrderAlgo) order
        ).filter(order ->
                order.getTypeOrder().isStopLoss()
        ).findAny();
    }

    @Override
    public @NotNull Optional<TradingTelemetry> getTelemetry() {
        return Optional.ofNullable(telemetry);
    }

    @Override
    public @NotNull Market getMarket() {
        return backTestEngine.getMarketMaster();
    }

    @Override
    public @NotNull Double getAvailableBalance() {
        double balanceAvailable = backTestEngine.getBalance();
        if (openOperation != null) {
            balanceAvailable -= openOperation.getQuantity();
        }
        return balanceAvailable;
    }

    @Override
    public @NotNull Double getCurrentPrice() {
        return backTestEngine.getCurrentPrice();
    }

    @Override
    public long getCurrentTime() {
        return backTestEngine.getCurrentTime();
    }

    @Override
    public @NotNull MediaNotification getMediaNotification() {
        return MediaNotification.empty();
    }

    @Override
    public void setMediaNotification(@NotNull MediaNotification mediaNotification) {

    }

    public static class BackTestOrderAlgo extends OrderAlgo {

        public BackTestOrderAlgo(@NotNull TradingManager tradingManager,
                                 @NotNull DireccionOperation direccion,
                                 @NotNull Double triggerPrice,
                                 @Nullable Double quantity,
                                 @Nullable Integer leverage,
                                 @NotNull Boolean reduceOnly,
                                 @NotNull TypeOrder typeOrder,
                                 @Nullable TimeInForce timeInForce
        ) {
            super(tradingManager, direccion, triggerPrice, quantity, leverage, reduceOnly, typeOrder, timeInForce);
        }

        public BackTestOrderAlgo(@NotNull BackTestOrderAlgo backTestOrderAlgo) {
            this(
                    backTestOrderAlgo.tradingManager,
                    backTestOrderAlgo.direccion,
                    backTestOrderAlgo.triggerPrice,
                    backTestOrderAlgo.quantity,
                    backTestOrderAlgo.leverage,
                    backTestOrderAlgo.reduceOnly,
                    backTestOrderAlgo.typeOrder,
                    backTestOrderAlgo.timeInForce
            );
        }

        @Override
        public @NotNull OrderAlgo copy() {
            return new BackTestOrderAlgo(this);
        }
    }

    public static class BackTestOrder extends Order {

        public BackTestOrder(@NotNull TradingManager tradingManager,
                             @NotNull DireccionOperation direccion,
                             @NotNull Double triggerPrice,
                             @NotNull Double quantity,
                             @NotNull Integer leverage,
                             @NotNull TypeOrder typeOrder,
                             @Nullable TimeInForce timeInForce
        ) {
            super(tradingManager, direccion, triggerPrice, quantity, leverage, typeOrder, timeInForce);
        }

        public BackTestOrder(@NotNull BackTestOrder backTestOrder){
            super(backTestOrder);
        }

        @Override
        public @NotNull Order copy() {
            return new BackTestOrder(this);
        }
    }

    public static class BackTestOpenPosition extends OpenPosition {

        @Getter @Setter
        private @NotNull Double lastExitPrices;
        public BackTestOpenPosition(@NotNull TradingManager tradingManager,
                                    @NotNull Double entryPrice,
                                    @NotNull DireccionOperation direccion,
                                    @NotNull Double quantity,
                                    @NotNull Integer leverage, // TODO: Cambiar el apaleamiento por el sistema que Binance
                                    @Nullable LimitedPosition order
        ) {
            super(tradingManager, direccion, entryPrice, quantity, leverage, order);
        }

        public BackTestOpenPosition(@NotNull BackTestOpenPosition backTestOpenPosition){
            super(backTestOpenPosition);
        }

        @Override
        public @NotNull BackTestOpenPosition copy() {
            return new BackTestOpenPosition(this);
        }
    }

    public static class BackTestClosePosition extends ClosePosition {

        public BackTestClosePosition(@NotNull Double exitPrice,
                                     @NotNull Long exitTime,
                                     @NotNull ExitReason reason,
                                     @NotNull OpenPosition openPosition
        ) {
            super(exitPrice, exitTime, reason, openPosition);
        }


        public BackTestClosePosition(@NotNull BackTestClosePosition backTestClosePosition){
            super(backTestClosePosition);
        }

        @Override
        public @NotNull ClosePosition copy() {
            return new BackTestClosePosition(this);
        }
    }


}
