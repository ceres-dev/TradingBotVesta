package xyz.cereshost.vesta.core.trading.backtest;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.market.Market;
import xyz.cereshost.vesta.core.message.MediaNotification;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TimeInForce;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.trading.TypeOrder;

import java.util.*;

public class TradingManagerBackTest implements TradingManager {

    @Setter
    private @Nullable TradingManagerBackTest.BackTestOpenPosition openOperation = null;

    @Getter
    private final @NotNull HashMap<UUID, LimitedPosition> pendingOrder = new HashMap<>();
    private final @NotNull BackTestEngine backTestEngine;

    public TradingManagerBackTest(@NotNull BackTestEngine backTestEngine) {
        this.backTestEngine = backTestEngine;
    }

    @Override
    public @NotNull Integer pendingOrderSize() {
        throw new UnsupportedOperationException("Not supported yet.");
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
        openOperation = o;
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
        return closeForEngine(closeOperation);
    }

    @Override
    public void cancelOrder(UUID uuid) {
        pendingOrder.remove(uuid);
    }

    @Override
    public void cancelAllOrder() {
        List<UUID> uuidsForRemover = pendingOrder.values().stream().filter(order ->
                order instanceof Order
        ).map(order ->
                (MarketObject) order
        ).map(MarketObject::getUuid).toList();
        uuidsForRemover.forEach(pendingOrder::remove);
    }

    @Override
    public void cancelAllOrderAlgo() {
        List<UUID> uuidsForRemover = pendingOrder.values().stream().filter(order ->
                order instanceof OrderAlgo
        ).map(order ->
                (MarketObject) order
        ).map(MarketObject::getUuid).toList();
        uuidsForRemover.forEach(pendingOrder::remove);
    }

    public Optional<TradingManager.ClosePosition> closeForEngine(BackTestClosePosition closeOperation){
        openOperation = null;
        backTestEngine.getStrategy().closeOperation(closeOperation, this);
        return Optional.of(closeOperation);
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
                                 @NotNull Double trigger,
                                 @Nullable Double quantity,
                                 @Nullable Integer leverage,
                                 @NotNull Boolean reduceOnly,
                                 @NotNull TypeOrder typeOrder,
                                 @Nullable TimeInForce timeInForce
        ) {
            super(tradingManager, direccion, trigger, quantity, leverage, reduceOnly, typeOrder, timeInForce);
        }
    }

    public static class BackTestOrder extends Order {

        public BackTestOrder(@NotNull TradingManager tradingManager,
                             @NotNull DireccionOperation direccion,
                             @NotNull Double trigger,
                             @NotNull Double quantity,
                             @NotNull Integer leverage,
                             @NotNull TypeOrder typeOrder,
                             @Nullable TimeInForce timeInForce
        ) {
            super(tradingManager, direccion, trigger, quantity, leverage, typeOrder, timeInForce);
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
                                    @Nullable Order order
        ) {
            super(tradingManager, direccion, entryPrice, quantity, leverage, order);
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
    }


}
