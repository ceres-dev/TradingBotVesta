package xyz.cereshost.vesta.core.trading.backtest;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.market.Market;
import xyz.cereshost.vesta.core.message.MediaNotification;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;

import java.util.ArrayList;
import java.util.List;

public class TradingManagerBackTest implements TradingManager {

    private @Nullable BackTestOpenOperation openOperation = null;
    private final ArrayList<BackTestCloseOperation> closeOperations = new ArrayList<>();

    @Getter
    private final List<BackTestOpenOperation> lastOpenOperation = new ArrayList<>();
    private final BackTestEngine backTestEngine;

    public TradingManagerBackTest(BackTestEngine backTestEngine) {
        this.backTestEngine = backTestEngine;
    }


    @Override
    public int limitsSize() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public @Nullable OpenOperation getOpen() {
        return openOperation;
    }

    @Override
    public @Nullable OpenOperation open(RiskLimits riskLimits, @NotNull DireccionOperation direccion, double amountUSD, int leverage) {
        // Lo minimo para invertir en Binance
        if (amountUSD *leverage < 5)return null;
        double currentPrice = backTestEngine.getCurrentPrice();
        // Simular el bin y el ask al comprar en mercado
//        double realPrice = direccion == DireccionOperation.LONG ? currentPrice + 0.0001 : currentPrice - 0.0001;
        BackTestOpenOperation o = new BackTestOpenOperation(this, riskLimits, currentPrice, direccion, amountUSD, leverage);
        lastOpenOperation.add(o);
        openOperation = o;
        return o;
    }

    @Override
    public @Nullable CloseOperation close(ExitReason reason) {
        BackTestCloseOperation closeOperation = new BackTestCloseOperation(backTestEngine.getCurrentPrice(), backTestEngine.getCurrentTime(), reason, openOperation);
        return closeForEngine(closeOperation);
    }

    @Override
    public @Nullable LimiteOperation limit(double entryPrice, RiskLimits riskLimits, @NotNull DireccionOperation direccion, double amountUSD, int leverage) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void cancelLimit(LimiteOperation limiteOperation) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public CloseOperation closeForEngine(BackTestCloseOperation closeOperation){
        backTestEngine.computeClose(closeOperation, openOperation);
        openOperation = null;
        backTestEngine.getStrategy().closeOperation(closeOperation, this);
        return closeOperation;
    }

    @Override
    public @NotNull List<LimiteOperation> getLimites() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Market getMarket() {
        return backTestEngine.getMarket();
    }

    @Override
    public double getAvailableBalance() {
        double balanceAvailable = backTestEngine.getBalance();
        if (openOperation != null) {
            balanceAvailable -= openOperation.getInitialMargenUSD();
        }
        return balanceAvailable;
    }

    @Override
    public double getCurrentPrice() {
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

    public static class BackTestOpenOperation extends OpenOperation {

        @Getter @Setter
        private double lastExitPrices;
        public BackTestOpenOperation(TradingManager tradingManager, RiskLimits riskLimits, double entryPrice, DireccionOperation direccion, double amountUSDT, int leverage) {
            super(tradingManager, riskLimits, direccion, entryPrice, amountUSDT, leverage);
        }
    }

    public static class BackTestCloseOperation extends CloseOperation {

        public BackTestCloseOperation(double exitPrice, long exitTime, ExitReason reason, OpenOperation openOperation) {
            super(exitPrice, exitTime, reason, openOperation);
        }
    }
}
