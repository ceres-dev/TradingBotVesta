package xyz.cereshost.vesta.core.trading;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Delegate;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.common.market.Market;
import xyz.cereshost.vesta.core.exception.OperationFilled;
import xyz.cereshost.vesta.core.message.Notifiable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static xyz.cereshost.vesta.core.trading.DireccionOperation.LONG;

public interface TradingManager extends Notifiable {

    @Nullable OpenOperation open(RiskLimits riskLimits, @NotNull DireccionOperation direccion, double amountUSD, int leverage);

    /**
     * Abre una operacion en futuros perpetuos
     *
     * @param tpPercent porcentaje para él limite Take Profit
     * @param slPercent porcentaje para él limite Stop Loss
     * @param direccion Si la operación es Short/Venta o una operación Larga/Compra
     * @param amountUSD Cantidad de USDT/USDC a operar
     * @param leverage  La cantidad de apaleamiento
     * @return la nueva instancia de la operación
     */
    default @Nullable OpenOperation open(double tpPercent, double slPercent, @NotNull DireccionOperation direccion, double amountUSD, int leverage) {
        return open(new RiskLimitsPercent(tpPercent, slPercent), direccion, amountUSD, leverage);
    }

    @Nullable LimiteOperation limit(double entryPrice, RiskLimits riskLimits, @NotNull DireccionOperation direccion, double amountUSD, int leverage);

    /**
     * Cierras una Operación previamente Abierta
     *
     * @param reason Razón de la salida
     */
    @Nullable CloseOperation close(ExitReason reason);

    void cancelLimit(LimiteOperation limiteOperation);

    int limitsSize();

    OpenOperation getOpen();

    @NotNull List<LimiteOperation> getLimites();

    Market getMarket();

    double getAvailableBalance();

    /**
     * Obtienes el preio actual del mercado
     *
     * @return el precio absoluto actual
     */

    double getCurrentPrice();

    /**
     * La hora actual (En el backtest se usa la hora del propio backtest)
     *
     * @return La hora actual
     */

    long getCurrentTime();

    default void log(String s) {
    }

    default boolean hasOpenOperation() {
        return getOpen() != null;
    }

    default void computeHasOpenOperation(Consumer<OpenOperation> consumer) {
        if (hasOpenOperation()) consumer.accept(getOpen());
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    abstract class CloseOperation extends OpenOperation {

        private final double exitPrice;
        private final long exitTime;
        private final ExitReason reason;

        @Delegate
        private final OpenOperation openOperation;

        public CloseOperation(double exitPrice, long exitTime, ExitReason reason, OpenOperation openOperation) {
            super(openOperation.getTradingManager(),
                    openOperation.getRiskLimits(),
                    openOperation.getDireccion(),
                    openOperation.getEntryPrice(),
                    openOperation.getInitialMargenUSD(),
                    openOperation.getLeverage()
            );
            this.exitPrice = exitPrice;
            this.exitTime = exitTime;
            this.reason = reason;
            this.openOperation = openOperation;
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
        public void nextMinute() {
            throw new OperationFilled(this);
        }
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    abstract class OpenOperation extends LimiteOperation implements RiskLimiterContainer {
        private final long entryTime;
        /**
         * Lista de banderas para identificar una operación
         */
        public final Set<String> flags = new HashSet<>();

        // Ojo el TP y SL en Porcentajes ABS sin apalancar
        public OpenOperation(@NotNull TradingManager tradingManager, RiskLimits riskLimits, @NotNull DireccionOperation direccion, double entryPrice, double amountUSDT, int leverage) {
            super(tradingManager, riskLimits, direccion, entryPrice, amountUSDT, leverage);
            this.entryTime = tradingManager.getCurrentTime();
        }

        public void close(ExitReason reason) {
            tradingManager.close(reason);
        }

        public void close() {
            tradingManager.close(ExitReason.STRATEGY);
        }

        @Override
        @Contract(value = "_ -> fail")
        public void setEntryPrice(double entryPrice) {
            throw new OperationFilled("No se puede cambiar el precio de entrada en una posición ya abierta");
        }

        /**
         * Obtienes la diferencia porcentual desde el precio de apertura
         *
         * @return diferencia porcentual -/+
         */
        public double getDiffPercent() {
            double currentPrice = tradingManager.getCurrentPrice();
            return ((currentPrice - entryPrice) / currentPrice) * 100;
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
            return !(this instanceof CloseOperation);
        }

        /**
         * La cantidad de minutos que lleva abierto una operación
         */
        private int minutesOpen = 0;

        /**
         * Suma 1+ a la cantidad de minutos que lleva abierto la operación
         */
        public void nextMinute() {
            minutesOpen++;
        }

        @Override
        public void setTimeInForce(TimeInForce timeInForce) {
            throw new UnsupportedOperationException();
        }
    }

    @Data
    abstract class LimiteOperation implements RiskLimiterContainer {

        protected final UUID uuid = UUID.randomUUID();

        protected final @NotNull TradingManager tradingManager;
        protected final @NotNull RiskLimits originalRisklimits;
        protected final @NotNull DireccionOperation direccion;
        protected double entryPrice;
        protected final double initialMargenUSD;
        protected final int leverage;

        public LimiteOperation(@NotNull TradingManager tradingManager,
                               @NotNull RiskLimits originalRisklimits,
                               @NotNull DireccionOperation direccion,
                               double entryPrice, double amountUSDT, int leverage
        ) {
            this.tradingManager = tradingManager;
            this.originalRisklimits = originalRisklimits;
            this.direccion = direccion;
            this.entryPrice = entryPrice;
            this.initialMargenUSD = amountUSDT;
            this.leverage = leverage;

            this.riskLimits = originalRisklimits;
        }

        protected @NotNull RiskLimits riskLimits;

        @NotNull
        protected TimeInForce timeInForce = TimeInForce.GTC;
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
        NO_DATA_ERROR;

        public boolean isTakeProfit() {
            return this == LONG_TAKE_PROFIT || this == SHORT_TAKE_PROFIT;
        }

        public boolean isStopLoss() {
            return this == LONG_STOP_LOSS || this == SHORT_STOP_LOSS;
        }
    }

    @Data
    @AllArgsConstructor
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
