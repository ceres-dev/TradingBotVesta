package xyz.cereshost.vesta.core.trading.real;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.common.market.Market;
import xyz.cereshost.vesta.core.message.MediaNotification;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TimeInForce;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.trading.TypeOrder;
import xyz.cereshost.vesta.core.trading.real.api.BinanceApi;
import xyz.cereshost.vesta.core.utils.BiDictionary;
import xyz.cereshost.vesta.core.utils.ConcurrentHashBiDictionary;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class TradingManagerBinance implements TradingManager {

    private final BinanceApi binanceApi;
    private int lastLeverage = 1;
    private double lastBalance = 0;
    @Setter
    private TradingTickLoop tradingTickLoop;
    @Getter @Setter @NotNull
    private MediaNotification mediaNotification;

    // Mapa para vincular tu UUID interno con los IDs de órdenes de Binance
    private BinanceOpenOperation openOperation = null;
    private final ConcurrentHashMap<UUID, BinanceLimitOperation> pendingLimitOperations = new ConcurrentHashMap<>();
    private final Market market;

    public TradingManagerBinance(@NotNull BinanceApi binanceApi, @Nullable MediaNotification mediaNotification, @NotNull Market market) {
        this.binanceApi = binanceApi;
        this.market = market;
        this.mediaNotification = Objects.requireNonNullElse(mediaNotification, MediaNotification.empty());
    }

    @Override
    public OpenOperation open(TradingManager.RiskLimits riskLimits, @NotNull DireccionOperation direccion, double amountUSD, int leverage) {
        String symbol = getMarket().getSymbol();
        try {
            CountDownLatch latch = new CountDownLatch(3);
            tradingTickLoop.getExecutor().execute(() -> {
                try {
                    if (lastLeverage != leverage) binanceApi.changeLeverage(symbol, leverage);
                    lastLeverage = leverage;
                }finally {
                    latch.countDown();
                }
            });
            AtomicReference<Double> safeAmountUSDT = new AtomicReference<>(amountUSD);
            tradingTickLoop.getExecutor().execute(() -> {
                try {
                    double balance = getAvailableBalance();
                    if (balance <= 0) {
                        safeAmountUSDT.set(0d);
                        return;
                    }
                    double requested = Math.max(0d, safeAmountUSDT.get());
                    safeAmountUSDT.set(Math.min(requested, balance) * 0.99);
                }finally {
                    latch.countDown();
                }
            });
            AtomicReference<Double> currentPrice = new AtomicReference<>(0d);
            tradingTickLoop.getExecutor().execute(() -> {
                try {
                    currentPrice.set(binanceApi.getTickerPrice(symbol));
                }finally {
                    latch.countDown();
                }
            });
            latch.await();
            if (safeAmountUSDT.get() <= 0) return null;

            double quantity = (safeAmountUSDT.get() * leverage) / currentPrice.get();
            BinanceOpenOperation op = new BinanceOpenOperation(
                    this, riskLimits, direccion, currentPrice.get(), amountUSD, leverage
            );
            String colorGreen = "\u001B[32m";
            String colorRed = "\u001B[31m";
            String reset = "\u001B[0m";
            String displayDireccion = direccion == DireccionOperation.LONG ? colorGreen + direccion.name() + reset : colorRed + direccion.name() + reset;
            Vesta.info(String.format("🔓 Abriendo operacion %s, con un margen: %.3f$. " + colorRed + " TP %.2f%% (%.4f$) " + colorGreen + "SL %.2f%% (%.4f$)" + reset + " (%s)",
                    displayDireccion, amountUSD,
                    op.getTpPercent(), op.getTpPrice(),
                    op.getSlPercent(), op.getSlPrice(),
                    op.getUuid()
            ));
            info("Abriendo operacion **%s** con un margen: %.3f$. **TP %.2f%%** (%.4f$) **SL %.2f%%** (%.4f$)",  direccion.name(), amountUSD,
                    op.getTpPercent(), op.getTpPrice(),
                    op.getSlPercent(), op.getSlPrice()
            );
            binanceApi.placeOrder(symbol, direccion, TypeOrder.MARKET, op.getTimeInForce(), quantity, null, false, false);
            placeProtectionOrders(symbol, op, op);

            openOperation = op;
            return op;
        } catch (InterruptedException e) {
            Vesta.sendErrorException("Binance Error Open Async", e);
            return null;
        }
    }

    @Override
    public @Nullable LimiteOperation limit(double entryPrice, TradingManager.RiskLimits riskLimits, @NotNull DireccionOperation direccion, double amountUSD, int leverage) {
        String symbol = getMarket().getSymbol();
        if (!Double.isFinite(entryPrice) || entryPrice <= 0) {
            Vesta.error("Precio de entrada invalido para orden limite en %s: %s", symbol, entryPrice);
            return null;
        }
        try {
            CountDownLatch latch = new CountDownLatch(2);

            tradingTickLoop.getExecutor().execute(() -> {
                try {
                    if (lastLeverage != leverage) binanceApi.changeLeverage(symbol, leverage);
                    lastLeverage = leverage;
                } finally {
                    latch.countDown();
                }
            });
            AtomicReference<Double> safeAmountUSDT = new AtomicReference<>(amountUSD);
            tradingTickLoop.getExecutor().execute(() -> {
                try {
                    double balance = getAvailableBalance();
                    if (balance <= 0) {
                        safeAmountUSDT.set(0d);
                        return;
                    }
                    double requested = Math.max(0d, safeAmountUSDT.get());
                    safeAmountUSDT.set(Math.min(requested, balance) * 0.99);
                } finally {
                    latch.countDown();
                }
            });
            latch.await();
            if (safeAmountUSDT.get() <= 0) return null;

            double quantity = (safeAmountUSDT.get() * leverage) / entryPrice;
            BinanceLimitOperation op = new BinanceLimitOperation(
                    this, riskLimits, direccion, entryPrice, amountUSD, leverage
            );
            op.setTimeInForce(TimeInForce.GTX);

            String colorGreen = "\u001B[32m";
            String colorRed = "\u001B[31m";
            String reset = "\u001B[0m";
            String displayDireccion = direccion == DireccionOperation.LONG ? colorGreen + direccion.name() + reset : colorRed + direccion.name() + reset;
            Vesta.info(String.format("Enviando orden LIMITE POST-ONLY %s: %.4f$ (margen %.3f$) TP %.2f%% SL %.2f%% (%s)",
                    displayDireccion, entryPrice, amountUSD,
                    riskLimits.getTpPercent(entryPrice), riskLimits.getSlPercent(entryPrice),
                    op.getUuid()
            ));
            info("Enviando orden LIMITE POST-ONLY **%s**: %.4f$ (margen %.3f$)", direccion.name(), entryPrice, amountUSD);

            long entryOrderId = binanceApi.placeOrder(
                    symbol,
                    direccion,
                    TypeOrder.LIMIT,
                    op.getTimeInForce(),
                    quantity,
                    entryPrice,
                    false,
                    false
            );
            op.setOrderId(entryOrderId);
            placeProtectionOrders(symbol, op, op);
            pendingLimitOperations.put(op.getUuid(), op);
            return op;
        } catch (InterruptedException e) {
            Vesta.sendErrorException("Binance Error Limit Async", e);
            return null;
        }
    }

    @Override
    public CloseOperation close(ExitReason reason) {
        BinanceOpenOperation op = openOperation;
        if (op == null) return null;

        String symbol = getMarket().getSymbol();
        try {
            CountDownLatch latch = new CountDownLatch(2);
            Vesta.info("🔒 Cerrando operacion: %s por %s", op.getUuid(), reason);
            info("Cerrando operacion: %s por %s", op.getUuid(), reason);

            // 1. Cancelar SL y TP pendientes
            tradingTickLoop.getExecutor().execute(() -> {
                binanceApi.cancelOrder(symbol, op.getSlBinanceId(), op.isSlIsAlgo());
                latch.countDown();
            });
            tradingTickLoop.getExecutor().execute(() -> {
                binanceApi.cancelOrder(symbol, op.getTpBinanceId(), op.isTpIsAlgo());
                latch.countDown();
            });
            latch.await();
            // 2. Cerrar posición (Market opuesto)
            // Usamos closePosition=true para cerrar cualquier remanente con seguridad

            double quantity = (op.getInitialMargenUSD() * op.getLeverage()) / op.getEntryPrice();

            tradingTickLoop.getExecutor().execute(() ->
                    binanceApi.placeOrder(
                            symbol,
                            op.getDireccion().inverse(),
                            TypeOrder.MARKET,
                            op.getTimeInForce(),
                            quantity,
                            null,
                            true,
                            false
                    )
            );

            // 3. Registrar cierre
            double exitPrice = binanceApi.getTickerPrice(symbol);
            BinanceCloseOperation closeOp = new BinanceCloseOperation(
                    exitPrice, System.currentTimeMillis(), reason, op
            );

            openOperation = null;
            return closeOp;
        } catch (InterruptedException e) {
            Vesta.sendErrorException("Binance Error Open Async", e);
            return null;
        }
    }

    @Override
    public void cancelLimit(LimiteOperation limiteOperation) {
        if (limiteOperation == null) {
            return;
        }
        BinanceLimitOperation pending = pendingLimitOperations.remove(limiteOperation.getUuid());
        if (pending == null) {
            return;
        }
        String symbol = getMarket().getSymbol();
        binanceApi.cancelOrder(symbol, pending.getOrderId(), false);
        Vesta.info("Orden LIMIT cancelada: %s (%s)", pending.getUuid(), pending.getOrderId());
        info("Orden LIMIT cancelada: %s", pending.getUuid());
    }

    @Override
    public int limitsSize() {
        return pendingLimitOperations.size();
    }

    @Override
    public @Nullable OpenOperation getOpen() {
        return openOperation;
    }

    @Override
    public @NotNull List<LimiteOperation> getLimites() {
        return new ArrayList<>(pendingLimitOperations.values());
    }

    @Override
    public Market getMarket() {
        return market;
    }

    @Override
    public double getAvailableBalance() {
        if (lastBalance == 0) {
            lastBalance = binanceApi.getBalance(market.getSymbol());
        }
        // Margen de seguridad
        return lastBalance*0.98;
    }

    @Override
    public double getCurrentPrice() {
        return binanceApi.getTickerPrice(getMarket().getSymbol());
    }

    @Override
    public long getCurrentTime() {
        return System.currentTimeMillis();
    }

    @Override
    public void log(String log){
        Vesta.info("📃 " + log);
    }

    private void placeProtectionOrders(@NotNull String symbol, @NotNull RiskLimitsBinance op, MargenContainer margen) {
        DireccionOperation direccionInverse = op.getDireccion().inverse();
        double slPrice = op.getSlPrice();
        if (!Double.isNaN(slPrice)) {
            System.err.println(slPrice); // Debug
            tradingTickLoop.getExecutor().execute(() -> {
                long slOrderId = binanceApi.placeAlgoOrder(
                        symbol, direccionInverse, op.getRiskLimits().isLimit() ? TypeOrder.STOP : TypeOrder.STOP_MARKET, op.getTimeInForce(), (margen.getLeverage() * margen.getInitialMargenUSD())/op.getEntryPrice(), slPrice, true
                );
                op.setSlBinanceId(slOrderId);
                op.setSlIsAlgo(true);
            });
        }
        double tpPrice = op.getTpPrice();
        if (!Double.isNaN(tpPrice)) {
            System.err.println(tpPrice);
            tradingTickLoop.getExecutor().execute(() -> {
                long tpOrderId = binanceApi.placeAlgoOrder(
                        symbol, direccionInverse, op.getRiskLimits().isLimit() ? TypeOrder.TAKE_PROFIT : TypeOrder.TAKE_PROFIT_MARKET, op.getTimeInForce(), (margen.getLeverage() * margen.getInitialMargenUSD())/op.getEntryPrice(), tpPrice, true
                );
                op.setTpBinanceId(tpOrderId);
                op.setTpIsAlgo(true);
            });
        }
    }

    public synchronized void sync(){
        List<BinanceApi.OrderData> orders = binanceApi.getAllOrders(market.getSymbol());
        BinanceApi.PositionData position = binanceApi.getPosition(market.getSymbol());
        if (position == null){
            for (BinanceApi.OrderData order : orders) {
                if (order.type().isExit()){
                    binanceApi.cancelOrder(market.getSymbol(), order.orderID(), order.isAlgoOrder());
                }
            }
            openOperation = null;
        }else {
            if (openOperation == null) {
                BinanceApi.OrderData TPLong = null, TPShort = null, SLLong = null, SLShort = null;
                for (BinanceApi.OrderData order : orders) {
                    if (order.type().isStopLoss()){
                        if (order.direccionOperation().isLong()) SLLong = order;
                        else SLShort = order;
                    }
                    if (order.type().isTakeProfit()){
                        if (order.direccionOperation().isLong()) TPLong = order;
                        else TPShort = order;
                    }
                }
                RiskLimits riskLimits;
                if (position.direccionOperation().isLong()) {
                    riskLimits = new RiskLimitsAbsolute(TPLong != null ? TPLong.triggerPrice() : null, SLLong != null ? SLLong.triggerPrice() : null);
                }else {
                    riskLimits = new RiskLimitsAbsolute(TPShort != null ? TPShort.triggerPrice() : null, SLShort != null ? SLShort.triggerPrice() : null);
                }
                openOperation = new BinanceOpenOperation(this, riskLimits, position.direccionOperation(), position.entryPrice(), position.margen(), position.leverage());
            }else {
                for (BinanceApi.OrderData order : orders) {
                    if (!order.isAlgoOrder()) continue;

                    TypeOrder typeOrder = order.type();

//                    RiskLimits riskLimits = openOperation.getRiskLimits();
//                    if (typeOrder.isTakeProfit()){
//                        openOperation.setTpBinanceId(order.orderID());
//                        if (riskLimits.isAbsolute()){
//                            riskLimits.setTakeProfit(order.triggerPrice(), price);
//                        }else {
//                            riskLimits.setTakeProfit(((order.triggerPrice() - openOperation.getEntryPrice())/openOperation.getEntryPrice())*100, price);
//                        }
//                    }
//                    if (typeOrder.isStopLoss()){
//                        openOperation.setSlBinanceId(order.orderID());
//                        if (riskLimits.isAbsolute()){
//                            riskLimits.setStopLoss(order.triggerPrice(), "");
//                        }else {
//                            riskLimits.setStopLoss(((order.triggerPrice() - openOperation.getEntryPrice())/openOperation.getEntryPrice())*100, price);
//                        }
//                    }
                }
            }
        }

        BiDictionary<UUID, Long> dictionary = new ConcurrentHashBiDictionary<>();
        for (BinanceLimitOperation limit : pendingLimitOperations.values()) {
            dictionary.add(limit.getUuid(), limit.getOrderId());
        }
        for (BinanceApi.OrderData order : orders) {
            if (order.type().equals(TypeOrder.LIMIT)) {
                UUID uuid = dictionary.removeRight(order.orderID());
                if (uuid == null){
                    BinanceLimitOperation limit = new BinanceLimitOperation(this,
                            new RiskLimitsAbsolute(null, null),
                            order.direccionOperation(), order.price(), order.quantity(), lastLeverage);
                    pendingLimitOperations.put(limit.getUuid(), limit);
                }else {
                    pendingLimitOperations.get(uuid).setEntryPrice(order.price());
                }
            }
        }
        for (BiDictionary.Entry<UUID, Long> entry : dictionary.getAll()){
            pendingLimitOperations.remove(entry.left());
        }
    }

    @Getter
    @Setter
    public static class BinanceOpenOperation extends OpenOperation implements RiskLimitsBinance, MargenContainer {
        private final TradingManagerBinance tradingBinance;

        private long tpBinanceId;
        private long slBinanceId;
        private boolean tpIsAlgo;
        private boolean slIsAlgo;

        private BinanceOpenOperation(TradingManagerBinance binance, TradingManager.RiskLimits riskLimits, DireccionOperation direccion, double entryPrice, double amountUSDT, int leverage) {
            super(binance, riskLimits, direccion, entryPrice, amountUSDT, leverage);
            this.tradingBinance = binance;
            riskLimits.setOnUpdate(this::updateProtectionOrder);
        }

        private boolean updateProtectionOrder(double newPercent, boolean isTpOrder) {
            if (!Double.isFinite(newPercent)) {
                Vesta.warning("Valor inválido para %s en %s: %s",
                        isTpOrder ? "TP" : "SL", getUuid(), newPercent);
                return false;
            }

            if (tradingBinance.openOperation == null) {
                Vesta.warning("No se puede actualizar %s: operación %s no está activa",
                        isTpOrder ? "TP" : "SL", getUuid());
                return false;
            }
            String symbol = tradingBinance.getMarket().getSymbol();

            long oldOrderId = isTpOrder ? getTpBinanceId() : getSlBinanceId();
            boolean oldIsAlgo = isTpOrder ? isTpIsAlgo() : isSlIsAlgo();
//            double triggerPrice = isTpOrder ? getTpPrice() : getSlPrice();

            try {
                tradingBinance.binanceApi.cancelOrder(symbol, oldOrderId, oldIsAlgo);
                long newOrderId = tradingBinance.binanceApi.placeAlgoOrder(
                        symbol, getDireccion().inverse(), isTpOrder ? TypeOrder.TAKE_PROFIT : TypeOrder.STOP, getTimeInForce(), (initialMargenUSD * leverage)/entryPrice, newPercent, true
                );

                if (isTpOrder) {
                    setTpBinanceId(newOrderId);
                    setTpIsAlgo(true);
                } else {
                    setSlBinanceId(newOrderId);
                    setSlIsAlgo(true);
                }
                return true;
            } catch (Exception e) {
                Vesta.sendErrorException("Binance Error Update " + (isTpOrder ? "TP" : "SL"), e);
                if (isTpOrder) {
                    setTpBinanceId(oldOrderId);
                    setTpIsAlgo(oldIsAlgo);
                } else {
                    setSlBinanceId(oldOrderId);
                    setSlIsAlgo(oldIsAlgo);
                }
                return false;
            }
        }
    }

    public static class BinanceCloseOperation extends CloseOperation {

        public BinanceCloseOperation(@NotNull BinanceOpenOperation op, @NotNull ExitReason reason) {
            super((reason.toString().contains("STOP")) ? op.getSlPrice() : op.getTpPrice(),
                    System.currentTimeMillis(),
                    reason,
                    op
            );
        }

        public BinanceCloseOperation(double exitPrice, long exitTime, ExitReason reason, OpenOperation openOperation) {
            super(exitPrice, exitTime, reason, openOperation);
        }
    }

    @Getter
    @Setter
    public static class BinanceLimitOperation extends LimiteOperation implements RiskLimitsBinance, MargenContainer {
        private long orderId;

        private long tpBinanceId;
        private long slBinanceId;
        private boolean tpIsAlgo;
        private boolean slIsAlgo;

        public BinanceLimitOperation(TradingManagerBinance binance, TradingManager.RiskLimits riskLimits, DireccionOperation direccion, double entryPrice, double amountUSDT, int leverage) {
            super(binance, riskLimits, direccion, entryPrice, amountUSDT, leverage);
        }
    }

    private interface RiskLimitsBinance extends RiskLimiterContainer {

        @Contract(pure = true)
        long getTpBinanceId();

        void setTpBinanceId(long tpBinanceId);

        @Contract(pure = true)
        long getSlBinanceId();

        void setSlBinanceId(long slBinanceId);

        @Contract(pure = true)
        boolean isTpIsAlgo();

        void setTpIsAlgo(boolean tpIsAlgo);

        @Contract(pure = true)
        boolean isSlIsAlgo();

        void setSlIsAlgo(boolean slIsAlgo);

        @Contract(pure = true)
        TimeInForce getTimeInForce();
    }

    private interface MargenContainer {
        @Contract(pure = true)
        double getInitialMargenUSD();

        @Contract(pure = true)
        int getLeverage();
    }

}
