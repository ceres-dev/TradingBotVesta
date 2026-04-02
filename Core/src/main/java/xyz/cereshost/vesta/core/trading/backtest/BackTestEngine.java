package xyz.cereshost.vesta.core.trading.backtest;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.common.market.Market;
import xyz.cereshost.vesta.common.market.Trade;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.StrategyConfig;
import xyz.cereshost.vesta.core.strategy.TradingStrategyConfigurable;
import xyz.cereshost.vesta.core.strategy.strategis.DefaultStrategy;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.ChartUtils;
import xyz.cereshost.vesta.core.utils.ProgressBar;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Getter
public class BackTestEngine {

    private final BackTestStats stats;
    private final List<CompleteTrade> extraStats = new ArrayList<>();
    @NotNull private final TradingManagerBackTest operations = new TradingManagerBackTest(this);
    @NotNull private final Market market;
    @NotNull private final TradingStrategy strategy;
    @Nullable private final PredictionEngine engine;
    private double balance = 6;
    private double currentPrice;
    private long currentTime;

    public BackTestEngine(@NotNull Market market, @Nullable PredictionEngine engine, @NotNull TradingStrategy strategy) {
        this.market = market;
        this.engine = engine;
        this.strategy = strategy;
        this.stats = new BackTestStats(market);
        currentPrice = market.getCandles().getFirst().getOpen();

    }

    public BackTestEngine(Market market, PredictionEngine engine) {
        this(market, engine, new DefaultStrategy());
    }

    public BackTestResult run() {
        market.sortd();
        SequenceCandles allCandles = strategy.getBuilder().build(market);
        ChartUtils.showCandleChart("Mercado", allCandles, market.getSymbol());
        return run(allCandles);
    }

    public BackTestResult run(SequenceCandles allCandles){

        market.buildTradeCache();

        @NotNull
        final StrategyConfig config;
        if (strategy instanceof TradingStrategyConfigurable configurable) {
            config = configurable.getStrategyConfig(operations);
        }else {
            config = StrategyConfig.builder().build();
        }

        int totalSamples = allCandles.size();
        int lookBack = engine == null ? config.getLookBack() : engine.getLookBack();

        // Empezamos donde tenemos datos suficientes
        int startIndex = lookBack + 1;

        // Variables de estado
        double initialBalance = balance;


        ProgressBar progressBar = new ProgressBar(totalSamples - 1);
        progressBar.setEachPrint(200);
        // Loop principal
        for (int i = startIndex; i < totalSamples - 1; i++) {
            progressBar.setCurrentValue(i);
            progressBar.print();
            // Obtener predicción
            SequenceCandles window = allCandles.subSequence(i - lookBack, i + 1);
            PredictionEngine.SequenceCandlesPrediction prediction ;
            if (engine != null && config.getHowUseIA() != null && config.getHowUseIA().useModelIA()) {
                prediction = engine.predictNextPriceDetail(window, config.getFuturePredict());
//                stats.getAllTrades().add(new InCompleteTrade(currentPrice, prediction.getTpPrice(), prediction.getSlPrice(), currentTime));
            }else {
                prediction = null;
            }

            // Consultar estrategia
            strategy.executeStrategy(prediction, window, operations);

            if (operations.getLastOpenOperation().isEmpty())stats.nothing++;
            for (TradingManagerBackTest.BackTestOpenOperation setup : operations.getLastOpenOperation()){
                if (setup != null) {
                    if (setup.getDireccion() != DireccionOperation.NEUTRAL) {
                        switch (setup.getDireccion()) {
                            case LONG -> stats.longs++;
                            case SHORT -> stats.shorts++;
                        }
                    } else stats.nothing++;
                } else stats.nothing++;
            }

            // Inicia la simulaciónn de una vela de duración

            simulateOneTick(
                    allCandles.get(i + 1),
                    operations
            );
            operations.computeHasOpenOperation(TradingManager.OpenOperation::nextMinute);
            operations.computeCloses();
        }
        stats.getTradesComplete().addAll(extraStats);
        return new BackTestResult(
                initialBalance, balance, balance - initialBalance, stats.getRoi(),
                stats.totalTrades, stats.getWins(), stats.getLosses(), stats.maxDrawdownPercent,
                stats
        );
    }

    public void computeClose(TradingManager.@NotNull CloseOperation closeOperation, TradingManager.OpenOperation openOperation) {
        if (closeOperation.getReason() == TradingManager.ExitReason.NO_DATA_ERROR) {
            return;
        }

        // D. Calcular PnL Real (con fees)
        // Fee se cobra al entrar (sobre entry) y al salir (sobre exit)
        double netPnL = getNetPnL(closeOperation, openOperation);

        double pnlPercent = netPnL / openOperation.getInitialMargenUSD(); // sobre margen

        balance += netPnL;
        if (balance < 0) balance = 0; // Quiebra
//        Vesta.info(openOperation.getDireccion().name() + " " + closeOperation.getReason().name());
//        LockSupport.parkNanos((long) 3e+9);

        // F. Registrar estadísticas
        TradeResult resultObj = new TradeResult(netPnL, pnlPercent, closeOperation.getExitPrice(), closeOperation.getReason(), closeOperation.getEntryTime(), closeOperation.getExitTime());
        stats.addComplenteTrade(resultObj, balance);
        extraStats.add(new CompleteTrade(
                openOperation.getRiskLimits(), closeOperation.getReason(), openOperation.getDireccion(),
                openOperation.getEntryPrice(),
                closeOperation.getExitPrice(),
                closeOperation.getEntryTime(),
                closeOperation.getExitTime(),
                netPnL,
                (float) balance,
                (float) (openOperation.getTpPercent() / openOperation.getSlPercent()),
                (float) pnlPercent
        ));
    }

    private double getNetPnL(TradingManager.@NotNull CloseOperation closeOperation, TradingManager.OpenOperation openOperation) {
        double positionSize = openOperation.getInitialMargenUSD() * openOperation.getLeverage(); // notional
        double qty = positionSize / openOperation.getEntryPrice();

        double entryFee = positionSize * market.getFeedMaker();
        double exitNotional = qty * closeOperation.getExitPrice();
        double exitFee = exitNotional * market.getFeedTaker();

        double grossPnL = getGrossPnL(closeOperation, openOperation, qty);

        return grossPnL - entryFee - exitFee;
    }

    /**
     * Redondeo conservador a 2 decimales:
     * positivo -> hacia abajo, negativo -> mas negativo.
     */
    private static double roundPnlAgainst(double pnl) {
        return BigDecimal.valueOf(pnl)
                .setScale(2, RoundingMode.FLOOR)
                .doubleValue();
    }

    private static double getGrossPnL(TradingManager.@NotNull CloseOperation closeOperation, TradingManager.OpenOperation openOperation, double qty) {
        double grossPnL;
        if (openOperation.getDireccion() == DireccionOperation.LONG) {
            // LONG
            grossPnL = (closeOperation.getExitPrice() - openOperation.getEntryPrice()) * qty;
        } else if (openOperation.getDireccion() == DireccionOperation.SHORT) {
            // SHORT
            grossPnL = (openOperation.getEntryPrice() - closeOperation.getExitPrice()) * qty;
        } else {
            // NEUTRAL
            grossPnL = 0;
        }
        return grossPnL;
    }

    /**
     * Simula la vida de un trade a través del tiempo (velas) y trades (ticks).
     */
    private void simulateOneTick(
            Candle candle,
            TradingManagerBackTest operations
    ) {
        long endTime = candle.getOpenTime() + 60_000;

        // Obtener trades reales de este minuto
        List<Trade> trades = market.getTradesInWindow(candle.getOpenTime(), endTime);
        if (trades.isEmpty()) {
            currentPrice = candle.getClose();
            currentTime = candle.getOpenTime();
            return;
        }

        if (operations.hasOpenOperation()) {
            TradingManager.OpenOperation openOperation = operations.getOpen();
            for (Trade t : trades) {
                currentPrice = t.price();
                currentTime = t.time();
                double price = t.price();
                ((TradingManagerBackTest.BackTestOpenOperation) openOperation).setLastExitPrices(price);
                boolean computeLimit = false;
                switch (openOperation.getDireccion()) {
                    case LONG -> {
                        if (price >= openOperation.getTpPrice()) {
                            operations.closeForEngine(new TradingManagerBackTest.BackTestCloseOperation(price, t.time(), TradingManager.ExitReason.LONG_TAKE_PROFIT, openOperation));
                            computeLimit = true;
                            break;

                        }
                        if (price <= openOperation.getSlPrice()) {
                            operations.closeForEngine(new TradingManagerBackTest.BackTestCloseOperation(price, t.time(), TradingManager.ExitReason.LONG_STOP_LOSS, openOperation));
                            computeLimit = true;
                        }
                    }
                    case SHORT -> {
                        if (price <= openOperation.getTpPrice()) {
                            operations.closeForEngine(new TradingManagerBackTest.BackTestCloseOperation(price, t.time(), TradingManager.ExitReason.SHORT_TAKE_PROFIT, openOperation));
                            computeLimit = true;
                            break;
                        }
                        if (price >= openOperation.getSlPrice()) {
                            operations.closeForEngine(new TradingManagerBackTest.BackTestCloseOperation(price, t.time(), TradingManager.ExitReason.SHORT_STOP_LOSS, openOperation));
                            computeLimit = true;
                        }

                    }
                }
                if (computeLimit) break;
            }
        } else {
            currentPrice = candle.getClose();
            currentTime = trades.getLast().time();
        }
    }

    /**
     * Telemetría y Datos de resultados del backTest
     */

    @Getter
    @Setter
    public static class BackTestStats {
        private final Market market;
        private int totalTrades = 0;
        private int timeouts = 0;
        private int longs = 0;
        private int shorts = 0;
        private int nothing = 0;

        double maxDrawdownPercent = 0.0;
        double peakBalance = 0.0;
        double currentDrawdown = 0.0;
        double totalPnL = 0.0;
        double initialBalance = 0.0;

        private List<TradeResult> trades = new ArrayList<>();
        private List<CompleteTrade> TradesComplete = new ArrayList<>();
        private List<IncompleteTrade> allTrades = new ArrayList<>();

        public BackTestStats(Market market) {
            this.market = market;
        }

        public int getTrades(TradingManager.ExitReason reason) {
            int i = 0;
            for (TradeResult tr : trades) if (tr.reason().equals(reason)) i++;
            return i;
        }

        public double getRoiTPMinLong(){
            double roi = 1;
            for (TradeResult tr : trades) if (tr.reason().equals(TradingManager.ExitReason.LONG_TAKE_PROFIT)) {
                if (roi > tr.pnlPercent()){
                    roi = tr.pnlPercent();
                }
            }
            return roi*100;
        }

        public double getRoiTPMinShort(){
            double roi = 1;
            for (TradeResult tr : trades) if (tr.reason().equals(TradingManager.ExitReason.SHORT_TAKE_PROFIT)) {
                if (roi > tr.pnlPercent()){
                    roi = tr.pnlPercent();
                }
            }
            return roi*100;
        }

        public double getRoiAvg(){
            double roi = 0;
            for (TradeResult tr : trades){
                roi += tr.pnlPercent()*100;
            }
            return roi/trades.size();
        }

        public double getRoiAvg(TradingManager.ExitReason reason) {
            double roi = 0;
            int count = 0;
            for (TradeResult tr : trades){
                if (reason.equals(tr.reason())) {
                    roi += tr.pnlPercent()*100;
                    count++;
                }
            }
            return roi/count;
        }


        // Para calcular Hold Time promedio

        public double getHoldAvg(){
            long totalHoldTimeMillis = 0;
            for (TradeResult tr : trades) totalHoldTimeMillis += (tr.exitTime - tr.entryTime);
            return (double) totalHoldTimeMillis / (double) trades.size();
        }

        public double getHoldAvgWins(){
            long totalHoldTimeMillis = 0;
            for (TradeResult tr : trades) if (tr.pnlPercent > 0) totalHoldTimeMillis += (tr.exitTime - tr.entryTime);
            return (double) totalHoldTimeMillis / (double) getWins();
        }

        public double getHoldAvgLoss(){
            long totalHoldTimeMillis = 0;
            for (TradeResult tr : trades) if (tr.pnlPercent < 0) totalHoldTimeMillis += (tr.exitTime - tr.entryTime);
            return (double) totalHoldTimeMillis / (double) getLosses();
        }

        public int getLosses(){
            int i = 0;
            for (TradeResult tr : trades) if (tr.pnlPercent < 0 ) i++;
            return i;
        }

        public int getWins(){
            int i = 0;
            for (TradeResult tr : trades) if (tr.pnlPercent > 0 ) i++;
            return i;
        }

        public void addComplenteTrade(TradeResult result, double currentBalance) {
            trades.add(result);
            if (totalTrades == 0) {
                initialBalance = currentBalance - result.pnl; // Reconstruir inicial
                peakBalance = initialBalance;
            }

            totalTrades++;
            totalPnL += result.pnl;

            if (result.reason == TradingManager.ExitReason.TIMEOUT) timeouts++;

            // Drawdown calculation
            if (currentBalance > peakBalance) {
                peakBalance = currentBalance;
                currentDrawdown = 0;
            } else {
                double dd = (peakBalance - currentBalance) / peakBalance; // 0.10 = 10%
                if (dd > maxDrawdownPercent) {
                    maxDrawdownPercent = dd;
                }
            }
        }

        public double getRoi() {
            return initialBalance > 0 ? (totalPnL / initialBalance) * 100 : 0;
        }

        public double getRoiWins() {
            double roi = 0;
            for (TradeResult tr : trades) if (tr.pnlPercent > 0) roi += tr.pnlPercent*100;
            return roi / getWins();
        }

        public double getRoiLosses() {
            double roi = 0;
            for (TradeResult tr : trades) if (tr.pnlPercent < 0) roi += tr.pnlPercent*100;
            return roi / getLosses();
        }

        public double getRoiTimeOut() {
            double roi = 0;
            for (TradeResult tr : trades) if (tr.reason().equals(TradingManager.ExitReason.TIMEOUT)) roi += tr.pnlPercent;
            return roi*100;
        }

        public double getRoiLong() {
            double roi = 0;
            for (TradeResult tr : trades)
                if (tr.reason().equals(TradingManager.ExitReason.LONG_STOP_LOSS) || tr.reason().equals(TradingManager.ExitReason.LONG_TAKE_PROFIT))
                    roi += tr.pnlPercent;
            return roi*100;
        }

        public double getRoiShort() {
            double roi = 0;
            for (TradeResult tr : trades)
                if (tr.reason().equals(TradingManager.ExitReason.SHORT_STOP_LOSS) || tr.reason().equals(TradingManager.ExitReason.SHORT_TAKE_PROFIT))
                    roi += tr.pnlPercent;
            return roi*100;
        }

        public double getRoiStrategy() {
            double roi = 0;
            for (TradeResult tr : trades) {
                if (tr.reason().equals(TradingManager.ExitReason.STRATEGY)) roi += tr.pnlPercent;
            }
            return roi*100;
        }

        public double getRatioAvg() {
            return TradesComplete.stream().mapToDouble(CompleteTrade::getRatio).average().orElse(0);
        }

        public double getRatioMin(){
            return TradesComplete.stream().mapToDouble(CompleteTrade::getRatio).min().orElse(0);
        }

        public double getRatioMax(){
            return TradesComplete.stream().mapToDouble(CompleteTrade::getRatio).max().orElse(0);
        }
    }

    public record TradeResult(double pnl, double pnlPercent, double exitPrice, TradingManager.ExitReason reason, long entryTime, long exitTime) {}

    @EqualsAndHashCode(callSuper = true)
    @Data
    public static final class CompleteTrade extends IncompleteTrade {

        private final TradingManager.ExitReason exitReason;
        private final double exitPrice;
        private final long exitTime;
        private final double pnl;
        private final float balance;
        private final float ratio;
        private final float pnlPercent;

        public CompleteTrade(
                TradingManager.RiskLimits riskLimits,
                TradingManager.ExitReason exitReason,
                DireccionOperation direction,
                double entryPrice,
                double exitPrice,
                long entryTime,
                long exitTime,
                double pnl,
                float balance,
                float ratio,
                float pnlPercent
        ) {
            super(entryPrice, entryTime, riskLimits, direction);
            this.exitPrice = exitPrice;
            this.exitTime = exitTime;
            this.exitReason = exitReason;
            this.pnl = pnl;
            this.balance = balance;
            this.ratio = ratio;
            this.pnlPercent = pnlPercent;
        }
    }

    @Data
    public static class IncompleteTrade implements TradingManager.RiskLimiterContainer {
        private final double entryPrice;
        private final long entryTime;
        private final TradingManager.RiskLimits riskLimits;
        private final DireccionOperation direccion;
    }

    // Mantener compatibilidad con tu código existente
    public record BackTestResult(
            double initialBalance,
            double finalBalance,
            double netPnL,
            double roiPercent,
            int totalTrades,
            int winTrades,
            int lossTrades,
            double maxDrawdown,
            BackTestStats stats
    ) {}

}
