package xyz.cereshost.vesta.core.trading;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Delegate;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.market.Market;
import xyz.cereshost.vesta.core.exception.OperationFilled;
import xyz.cereshost.vesta.core.message.Notifiable;
import xyz.cereshost.vesta.core.trading.real.TradingManagerBinance;
import xyz.cereshost.vesta.core.utils.Copyable;
import xyz.cereshost.vesta.core.utils.Identifiable;

import java.util.*;
import java.util.function.BiFunction;

import static xyz.cereshost.vesta.core.trading.DireccionOperation.LONG;

public interface TradingManager extends Notifiable {

    /**
     * Abre una operacion en futuros perpetuos
     *
     * @param direccion Si la operación es Short/Venta o una operación Larga/Compra
     * @param quantity Cantidad de USDT/USDC a operar
     * @param leverage  La cantidad de apaleamiento
     * @return la nueva instancia de la operación
     */
    @NotNull Optional<TradingManager.OpenPosition> open(@NotNull DireccionOperation direccion,
                                               @NotNull Double quantity,
                                               @NotNull Integer leverage
    );

    @NotNull Optional<Order> limit(@NotNull DireccionOperation side,
                                   @NotNull Double trigger,
                                   @NotNull Double quantity,
                                   @NotNull Integer leverage,
                                   @NotNull TypeOrder typeOrder,
                                   @NotNull TimeInForce timeInForce
    );

    default Optional<Order> limit(@NotNull DireccionOperation side,
                                  @NotNull Double trigger,
                                  @NotNull Double quantity,
                                  @NotNull Integer leverage,
                                  @NotNull TypeOrder typeOrder
    ){
        return limit(side, trigger, quantity, leverage, typeOrder, TimeInForce.GTE_GTC);
    }

    default Optional<Order> limit(@NotNull DireccionOperation side,
                                  @NotNull Double trigger,
                                  @NotNull Double quantity,
                                  @NotNull Integer leverage,
                                  @NotNull TimeInForce timeInForce
    ){
        return limit(side, trigger, quantity, leverage, TypeOrder.MARKET, timeInForce);
    }

    default Optional<Order> limit(@NotNull DireccionOperation side,
                                  @NotNull Double trigger,
                                  @NotNull Double quantity,
                                  @NotNull Integer leverage
    ){
        return limit(side, trigger, quantity, leverage, TypeOrder.MARKET, TimeInForce.GTE_GTC);
    }

    @NotNull Optional<OrderAlgo> limitAlgo(@NotNull DireccionOperation side,
                                           @NotNull TypeOrder type,
                                           @NotNull Double stopPrice,
                                           @Nullable Integer leverage,
                                           @Nullable Double quantity,
                                           @NotNull TimeInForce timeInForce,
                                           @NotNull Boolean reduceOnly
    );

    default @NotNull Optional<OrderAlgo> limitAlgo(@NotNull DireccionOperation side,
                                                   @NotNull TypeOrder type,
                                                   @NotNull Double stopPrice,
                                                   @Nullable Integer leverage,
                                                   @Nullable Double quantity,
                                                   @NotNull Boolean reduceOnly
    ){
        return limitAlgo(side, type, stopPrice, leverage, quantity, TimeInForce.GTE_GTC, reduceOnly);
    }

    default @NotNull Optional<OrderAlgo> limitAlgo(@NotNull DireccionOperation side,
                                                   @NotNull TypeOrder type,
                                                   @NotNull Double stopPrice,
                                                   @Nullable Integer leverage,
                                                   @Nullable Double quantity
    ){
        return limitAlgo(side, type, stopPrice, leverage, quantity, true);
    }

    default @NotNull Optional<OrderAlgo> limitAlgo(@NotNull DireccionOperation side,
                                                   @NotNull TypeOrder type,
                                                   @NotNull Double stopPrice
    ){
        return limitAlgo(side, type, stopPrice, null, null, TimeInForce.GTE_GTC, true);
    }

    default @NotNull Optional<OrderAlgo> limitAlgo(@NotNull DireccionOperation side,
                                                   @NotNull TypeOrder type,
                                                   @NotNull Double stopPrice,
                                                   @NotNull TimeInForce timeInForce
    ){
        return limitAlgo(side, type, stopPrice, null, null, timeInForce, true);
    }


    /**
     * Cierras una Operación previamente Abierta
     *
     * @param reason Razón de la salida
     */
    @NotNull Optional<TradingManager.ClosePosition> close(ExitReason reason);

    void cancelOrder(UUID uuid);

    void cancelAllOrder();

    void cancelAllOrderAlgo();

    @NotNull Integer pendingOrderSize();

    @NotNull Optional<OpenPosition> getOpenPosition();

    @NotNull List<Order> getOrder();

    @NotNull List<OrderAlgo> getLimitAlgos();

    @NotNull Optional<TradingManager.OrderAlgo> getTakeProfit();

    @NotNull Optional<TradingManager.OrderAlgo> getStopLoss();

    @NotNull Optional<TradingTelemetry> getTelemetry();

    @NotNull Market getMarket();

    @NotNull Double getAvailableBalance();

    /**
     * Obtienes el preio actual del mercado
     *
     * @return el precio absoluto actual
     */

    @NotNull Double getCurrentPrice();

    /**
     * La hora actual (En el backtest se usa la hora del propio backtest)
     *
     * @return La hora actual
     */

    long getCurrentTime();

    default void log(String s) {
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    abstract class ClosePosition extends OpenPosition {

        private final @NotNull Double exitPrice;
        private final @NotNull Long exitTime;
        private final @NotNull ExitReason reason;

        @Delegate
        private final OpenPosition openPosition;

        public ClosePosition(@NotNull Double exitPrice,
                             @NotNull Long exitTime,
                             @NotNull ExitReason reason,
                             @NotNull OpenPosition openPosition
        ) {
            super(openPosition.getTradingManager(),
                    openPosition.getDireccion(),
                    openPosition.getTriggerPrice(),
                    openPosition.getQuantity(),
                    openPosition.getLeverage(),
                    openPosition.getOrder()
            );
            this.exitPrice = exitPrice;
            this.exitTime = exitTime;
            this.reason = reason;
            this.openPosition = openPosition;
        }

        public ClosePosition(ClosePosition closePosition) {
            super(
                    closePosition.tradingManager,
                    closePosition.direccion,
                    closePosition.triggerPrice,
                    closePosition.quantity,
                    closePosition.leverage,
                    closePosition.order
            );
            this.exitPrice = closePosition.exitPrice;
            this.exitTime = closePosition.exitTime;
            this.reason = closePosition.reason;
            this.openPosition = closePosition.openPosition;
        }

        /* Methods que implica una modificación a la operación */
        @Override
        public void close(ExitReason reason) {
            throw new OperationFilled(this);
        }

        @Override
        public void close() {
            throw new OperationFilled(this);
        }

        @Override
        public void nextStep() {
            throw new OperationFilled(this);
        }

        public abstract @NotNull ClosePosition copy();
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    abstract class OpenPosition extends PossiblePosition<OpenPosition> {

        protected final @NotNull Long entryTime;
        protected final @Nullable LimitedPosition order;

        /**
         * Lista de banderas para identificar una operación
         */
        public final Set<String> flags;

        public OpenPosition(@NotNull TradingManager tradingManager,
                            @NotNull DireccionOperation direccion,
                            @NotNull Double entryPrice,
                            @NotNull Double quantity,
                            @NotNull Integer leverage,
                            @Nullable LimitedPosition order
        ) {
            super(tradingManager, direccion, entryPrice, quantity, leverage);
            this.entryTime = tradingManager.getCurrentTime();
            this.order = order;
            this.flags = new HashSet<>();
        }

        public OpenPosition(@NotNull OpenPosition openPosition){
            super(
                    openPosition.tradingManager,
                    openPosition.direccion,
                    openPosition.triggerPrice,
                    openPosition.quantity,
                    openPosition.leverage
            );
            this.entryTime = openPosition.entryTime;
            this.order = openPosition.order;
            this.flags = openPosition.flags;
        }

        public void close(ExitReason reason) {
            tradingManager.close(reason);
        }

        public void close() {
            tradingManager.close(ExitReason.STRATEGY);
        }

        @Override
        @Contract(value = "_ -> fail")
        public void setTriggerPrice(@NotNull Double entryTime){
            throw new UnsupportedOperationException();
        }

        /**
         * Obtienes la diferencia porcentual desde el precio de apertura
         *
         * @return diferencia porcentual -/+
         */
        public double getDiffPercent() {
            double currentPrice = tradingManager.getCurrentPrice();
            return ((currentPrice - triggerPrice) / currentPrice) * 100;
        }

        /**
         * Obtienes el low de la operación sin comisión y sin apalancamiénto
         *
         * @return El Roi crudo
         */
        public double getRoiRaw() {
            if (isUpDireccion()) {
                return getDiffPercent();
            } else {
                return -getDiffPercent();
            }
        }

        /**
         * Devuelve si la operacion en este instante de tiempo está en positivo
         * <strong>No tiene en cuenta la comisión y el apalancamiénto</strong>
         *
         * @return true si es rentable, false en caso contrario
         */
        public boolean isProfit() {
            return getRoiRaw() > 0;
        }

        public boolean isUpDireccion() {
            return direccion == LONG;
        }

        /**
         * Devuelve true si la operacion aún sigue abierta o solo está en modo lectura
         *
         * @return true si está abierto y modificable false en caso contrario
         */
        public boolean isOpen() {
            return !(this instanceof ClosePosition);
        }

        /**
         * La cantidad de velas que lleva abierto una operación
         */
        private int candlesOpen = 0;

        /**
         * Suma 1+ a la cantidad de minutos que lleva abierto la operación
         */
        public void nextStep() {
            candlesOpen++;
        }
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    abstract class OrderAlgo extends MarketObject<OrderAlgo> implements LimitedPosition {

        @NotNull protected TypeOrder typeOrder;
        @Nullable protected TimeInForce timeInForce;
        @NotNull protected Double triggerPrice;
        @Nullable protected final Double quantity;
        @Nullable protected final Integer leverage;
        @NotNull protected final Boolean reduceOnly;

        public OrderAlgo(@NotNull TradingManager tradingManager,
                         @NotNull DireccionOperation direccion,
                         @NotNull Double triggerPrice,
                         @Nullable Double quantity,
                         @Nullable Integer leverage,
                         @NotNull Boolean reduceOnly,
                         @NotNull TypeOrder typeOrder,
                         @Nullable TimeInForce timeInForce
        ) {
            super(tradingManager, direccion);
            this.typeOrder = typeOrder;
            this.triggerPrice = triggerPrice;
            this.quantity = quantity;
            this.leverage = leverage;
            this.reduceOnly = reduceOnly;
            this.timeInForce = timeInForce;
        }

        public OrderAlgo(@NotNull OrderAlgo order){
            this(
                    order.tradingManager,
                    order.direccion,
                    order.triggerPrice,
                    order.quantity,
                    order.leverage,
                    order.reduceOnly,
                    order.typeOrder,
                    order.timeInForce
            );
        }

        public boolean satisfaceCondicion(Double currentPrice) {
            switch (direccion){
                case LONG -> {
                    if (getTypeOrder().isTakeProfit()){
                        return currentPrice <= triggerPrice;
                    }
                    if (getTypeOrder().isStopLoss()){
                        return currentPrice >= triggerPrice;
                    }
                    throw new IllegalArgumentException("Tipo de orden invalido limite Algo: " + getTypeOrder());
                }
                case SHORT -> {
                    if (getTypeOrder().isTakeProfit()){
                        return currentPrice >= triggerPrice;
                    }
                    if (getTypeOrder().isStopLoss()){
                        return currentPrice <= triggerPrice;
                    }
                    throw new IllegalArgumentException("Tipo de orden invalido para limite Algo: " + getTypeOrder());
                }
                default -> throw new UnsupportedOperationException();
            }
        }

        public Double simuleClose(OpenPosition openPosition) {
            if (typeOrder.isAllowClosePosition()){
                return 0d;
            }else {
                if (quantity == null || leverage == null) throw new IllegalArgumentException("Quantity dio nulo cuando su tipo impide que diera nulo " + typeOrder.name());
                // TODO: Investigar si se usa el aplacamiento
                if (reduceOnly){
                    return Math.max(0d, openPosition.quantity* - quantity);
                }else {
                    return openPosition.quantity - quantity;
                }
            }
        }

        @Override
        public @NotNull Boolean isAlgo(){
            return false;
        }

        @Override
        public void cancel() {
            tradingManager.cancelOrder(this.uuid);
        }

    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    abstract class Order extends PossiblePosition<Order> implements LimitedPosition {

        @NotNull protected TypeOrder typeOrder;
        @Nullable protected TimeInForce timeInForce;

        public Order(@NotNull TradingManager tradingManager,
                     @NotNull DireccionOperation direccion,
                     @NotNull Double triggerPrice,
                     @NotNull Double quantity,
                     @NotNull Integer leverage,
                     @NotNull TypeOrder typeOrder,
                     @Nullable TimeInForce timeInForce
        ) {
            super(tradingManager, direccion, triggerPrice, quantity, leverage);
            this.typeOrder = typeOrder;
            this.timeInForce = timeInForce;
        }

        public Order(@NotNull Order order){
            this(
                    order.tradingManager,
                    order.direccion,
                    order.triggerPrice,
                    order.quantity,
                    order.leverage,
                    order.typeOrder,
                    order.timeInForce
            );
        }

        @Override
        public @NotNull Boolean isAlgo(){
            return true;
        }

        @Override
        public void cancel() {
            tradingManager.cancelOrder(this.uuid);
        }
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    abstract class PossiblePosition<T extends PossiblePosition<T>> extends MarketObject<T> {

        @NotNull protected Double triggerPrice;
        @NotNull protected final Double quantity;
        @NotNull protected final Integer leverage;

        public PossiblePosition(@NotNull TradingManager tradingManager,
                                @NotNull DireccionOperation direccion,
                                @NotNull Double triggerPrice,
                                @NotNull Double quantity,
                                @NotNull Integer leverage
        ) {
            super(tradingManager, direccion);
            this.triggerPrice = triggerPrice;
            this.quantity = quantity;
            this.leverage = leverage;
        }

        public PossiblePosition(PossiblePosition<T> possiblePosition){
            this(
                    possiblePosition.tradingManager,
                    possiblePosition.direccion,
                    possiblePosition.triggerPrice,
                    possiblePosition.quantity,
                    possiblePosition.leverage
            );
        }

        public @NotNull Double getQuantityLeverage(){
            return quantity * leverage;
        }
    }

    @Data
    abstract class MarketObject<T extends MarketObject<T>> implements Copyable<T>, Identifiable {
        protected final UUID uuid = UUID.randomUUID();
        protected final @NotNull TradingManager tradingManager;
        protected final @NotNull DireccionOperation direccion;
    }

    interface LimitedPosition extends Identifiable {
        @Contract(pure = true)
        @NotNull Double getTriggerPrice();
        void setTriggerPrice(@NotNull Double triggerPrice);

        @Contract(pure = true)
        @Nullable TimeInForce getTimeInForce();

        @Contract(pure = true)
        @NotNull TypeOrder getTypeOrder();

        @Contract(pure = true)
        @Nullable Double getQuantity();

        @Contract(pure = true)
        @NotNull Boolean isAlgo();

        void cancel();
    }

    /**
     * Razones para salir de una operación
     */
    enum ExitReason {
        /**
         * Cierre al cruzar él limite Take Profit en operación Long
         */
        LONG_TAKE_PROFIT,
        /**
         * Cierre al cruzar él limite Stop Loss en operación Long
         */
        LONG_STOP_LOSS,
        /**
         * Cierre al cruzar él limite Take Profit en operación short
         */
        SHORT_TAKE_PROFIT,
        /**
         * Cierre al cruzar él limite Stop Loss en operación short
         */
        SHORT_STOP_LOSS,
        /**
         * Se cierra por el exceder tiempo máximo (Se tiene añadir de forma manual)
         */
        TIMEOUT,
        /**
         * Cierra por qué la estrategia lo indica
         */
        STRATEGY,
        /**
         * Cierra por qué la estrategia lo indica (Se debe abrir una operacion inversa)
         */
        STRATEGY_INVERSION,
        /**
         * Cierre en caso de error
         */
        NO_DATA_ERROR,
        /**
         * Cierra por una orden Algo y al exceder el quanty habré una posición opuesta
         */
        INVERSION;

        public boolean isTakeProfit() {
            return this == LONG_TAKE_PROFIT || this == SHORT_TAKE_PROFIT;
        }

        public boolean isStopLoss() {
            return this == LONG_STOP_LOSS || this == SHORT_STOP_LOSS;
        }
    }

    @Data
    @AllArgsConstructor
    @Deprecated
    abstract class RiskLimits{ // TODO: Arreglar los limites cuando es relativo
        // Si es nulo no hay limite de Take Profit
        @Nullable private Double takeProfit;
        // Si es nulo no hay limite de Stop Loss
        @Nullable private Double stopLoss;

        @Nullable private Double qty;

        private boolean isLimit = true;
        private TimeInForce timeInForce = TimeInForce.GTC;

        public RiskLimits(@Nullable Double takeProfit, @Nullable Double stopLoss) {
            this.takeProfit = takeProfit;
            this.stopLoss = stopLoss;
        }

        public RiskLimits(@Nullable Double takeProfit, @Nullable Double stopLoss,  @Nullable Double qty) {
            this.takeProfit = takeProfit;
            this.stopLoss = stopLoss;
            this.qty = qty;
        }

        public RiskLimits setLimit(boolean isLimit) {
            this.isLimit = isLimit;
            return this;
        }

        public RiskLimits setTimeInForce(TimeInForce timeInForce) {
            this.timeInForce = timeInForce;
            return this;
        }

        private @Nullable BiFunction<Double, Boolean, Boolean> onUpdate = null;
        private final boolean isAbsolute = this instanceof RiskLimitsAbsolute;

        private static double toTpPriceFromPercent(double price, double takeProfitPercent, @NotNull DireccionOperation direccion) {
            double pct = takeProfitPercent * 0.01D;
            return direccion == LONG ? price + (price * pct) : price - (price * pct);
        }

        private static double toSlPriceFromPercent(double price, double stopLossPercent, @NotNull DireccionOperation direccion) {
            double pct = stopLossPercent * 0.01D;
            return direccion == LONG ? price - (price * pct) : price + (price * pct);
        }

        public void setStopLoss(Double stopLoss, double price) {
            setStopLoss(stopLoss, price, LONG);
        }

        public void setStopLoss(Double stopLoss, double price, @NotNull DireccionOperation direccion) {
            if (onUpdate != null) {
                if (onUpdate.apply(isAbsolute ? stopLoss : toSlPriceFromPercent(price, stopLoss, direccion), false)) this.stopLoss = stopLoss;
            }else this.stopLoss = stopLoss;
        }

        public void setTakeProfit(Double takeProfit, double price) {
            setTakeProfit(takeProfit, price, LONG);
        }

        public void setTakeProfit(Double takeProfit, double price, @NotNull DireccionOperation direccion) {
            if (onUpdate != null) {
                if (onUpdate.apply(isAbsolute ? takeProfit : toTpPriceFromPercent(price, takeProfit, direccion), true)) this.takeProfit = takeProfit;
            }else this.takeProfit = takeProfit;
        }

        public void setTakeProfitPercent(double percent, double price) {
            setTakeProfitPercent(percent, price, LONG);
        }

        public void setTakeProfitPercent(double percent, double price, @NotNull DireccionOperation direccion) {
            if (isAbsolute) {
                setTakeProfit(toTpPriceFromPercent(price, percent, direccion), price, direccion);
            }else setTakeProfit(percent, price, direccion);
        }

        public void addTakeProfitPercent(double percent, double price) {
            addTakeProfitPercent(percent, price, LONG);
        }

        public void addTakeProfitPercent(double percent, double price, @NotNull DireccionOperation direccion) {
            if (isAbsolute) {
                if (this.takeProfit == null){
                    setTakeProfit(toTpPriceFromPercent(price, percent, direccion), price, direccion);
                }else setTakeProfit(this.takeProfit + toTpPriceFromPercent(price, percent, direccion), price, direccion);
            }else {
                if (this.takeProfit == null) {
                    setTakeProfit(percent, price, direccion);
                }else setTakeProfit(this.takeProfit + percent, price, direccion);
            }
        }

        public void setStopLossPercent(double percent, double price) {
            setStopLossPercent(percent, price, LONG);
        }

        public void setStopLossPercent(double percent, double price, @NotNull DireccionOperation direccion) {
            if (isAbsolute) {
                setStopLoss(toSlPriceFromPercent(price, percent, direccion), price, direccion);
            }else setStopLoss(percent, price, direccion);
        }

        public void addStopLossPercent(double percent, double price) {
            addStopLossPercent(percent, price, LONG);
        }

        public void addStopLossPercent(double percent, double price, @NotNull DireccionOperation direccion) {
            if (isAbsolute) {
                if (this.stopLoss == null){
                    setStopLoss(toSlPriceFromPercent(price, percent, direccion), price, direccion);
                }else setStopLoss(this.stopLoss + toSlPriceFromPercent(price, percent, direccion), price, direccion);
            }else {
                if (this.stopLoss == null) {
                    setStopLoss(percent, price, direccion);
                }else setStopLoss(this.stopLoss + percent, price, direccion);
            }
        }

        public double getTpPercent(double price) {
            return getTpPercent(price, LONG);
        }

        public double getTpPercent(double price, @NotNull DireccionOperation direccion) {
            Double takeProfit = getTakeProfit();
            if (takeProfit == null) return Double.NaN;
            if (!isAbsolute()) return takeProfit;
            return direccion == LONG ? ((takeProfit - price) / price) * 100D : ((price - takeProfit) / price) * 100D;
        }

        public double getSlPercent(double price) {
            return getSlPercent(price, LONG);
        }

        public double getSlPercent(double price, @NotNull DireccionOperation direccion) {
            Double stopLoss = getStopLoss();
            if (stopLoss == null) return Double.NaN;
            if (!isAbsolute()) return stopLoss;
            return direccion == LONG ? ((price - stopLoss) / price) * 100D : ((stopLoss - price) / price) * 100D;
        }

        public double getTpPrice(double price){
            return getTpPrice(price, LONG);
        }

        public double getTpPrice(double price, @NotNull DireccionOperation direccion){
            Double takeProfit = getTakeProfit();
            if (takeProfit == null) return Double.NaN;
            if (isAbsolute()) return takeProfit;
            return toTpPriceFromPercent(price, takeProfit, direccion);
        }

        public double getSlPrice(double price){
            return getSlPrice(price, LONG);
        }

        public double getSlPrice(double price, @NotNull DireccionOperation direccion){
            Double stopLoss = getStopLoss();
            if (stopLoss == null) return Double.NaN;
            if (isAbsolute()) return stopLoss;
            return toSlPriceFromPercent(price, stopLoss, direccion);
        }
    }

    class RiskLimitsPercent extends RiskLimits {
        public RiskLimitsPercent(@Nullable Double takeProfitPercent, @Nullable Double stopLossPercent) {
            super(takeProfitPercent, stopLossPercent);
        }
    }

    class RiskLimitsAbsolute extends RiskLimits {
        public RiskLimitsAbsolute(@Nullable Double takeProfitAbsolute, @Nullable Double stopLossAbsolute) {
            super(takeProfitAbsolute, stopLossAbsolute);
        }

        public RiskLimitsAbsolute(@Nullable Double takeProfitAbsolute, @Nullable Double stopLossAbsolute, Double price) {
            super(takeProfitAbsolute == null ? null : takeProfitAbsolute + price, stopLossAbsolute == null ? null : price - stopLossAbsolute);
        }
    }


    interface RiskLimiterContainer {

        @Contract(pure = true)
        @NotNull RiskLimits getRiskLimits();

        @NotNull DireccionOperation getDireccion();

        double getEntryPrice();

        default double getTpPercent() {
            return getRiskLimits().getTpPercent(getEntryPrice(), getDireccion());
        }

        default double getSlPercent() {
            return getRiskLimits().getSlPercent(getEntryPrice(), getDireccion());
        }

        default double getTpPrice(){
            return getRiskLimits().getTpPrice(getEntryPrice(), getDireccion());
        }

        default double getSlPrice(){
            return getRiskLimits().getSlPrice(getEntryPrice(), getDireccion());
        }

        default void setTpPercent(double tpPercent) {
            getRiskLimits().setTakeProfitPercent(tpPercent, getEntryPrice(), getDireccion());
        }

        default void setSlPercent(double slPercent) {
            getRiskLimits().setStopLossPercent(slPercent, getEntryPrice(), getDireccion());
        }

        default void addTpPercent(double tpPercent) {
            getRiskLimits().addTakeProfitPercent(tpPercent, getEntryPrice(), getDireccion());
        }

        default void addSlPercent(double slPercent) {
            getRiskLimits().addStopLossPercent(slPercent, getEntryPrice(), getDireccion());
        }
    }
}
