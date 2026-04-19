package xyz.cereshost.vesta.core.trading.backtest;

import lombok.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.market.*;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.io.IOMarket;
import xyz.cereshost.vesta.core.io.setup.LoadDataMethodLocalRange;
import xyz.cereshost.vesta.core.strategy.*;
import xyz.cereshost.vesta.core.strategy.candles.ExecutorCandles;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.utils.ChartUtils;
import xyz.cereshost.vesta.core.utils.ProgressBar;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.util.*;

@Getter
public class BackTestEngine {

    private final BackTestStats stats;
    private final List<CompleteTrade> extraStats = new ArrayList<>();
    @NotNull private final TradingManagerBackTest operations = new TradingManagerBackTest(this);
    @NotNull private final Market marketMaster;
    @NotNull private final TradingStrategy strategy;
    @Nullable private final PredictionEngine engine;
    private double balance = 6;
    private double currentPrice;
    private long currentTime;
    @Getter(AccessLevel.NONE)
    private double lastPrice;

    public BackTestEngine(int to, int from, @Nullable PredictionEngine engine, @NotNull TradingStrategy strategy) {
        this.marketMaster = IOMarket.loadMarket(
                strategy.getMarketMaster(),
                new LoadDataMethodLocalRange(true, to, from)
        );
        this.engine = engine;
        this.strategy = strategy;
        this.stats = new BackTestStats(marketMaster);
        currentPrice = marketMaster.getCandles().getFirst().getOpen();
    }

    public BackTestEngine(@Nullable PredictionEngine engine, @NotNull TradingStrategy strategy) {
        this(0, 30, engine, strategy);
    }

    public BackTestResult run() {
        marketMaster.sortd();

        SequenceCandles allCandles = strategy.getBuilder().build(marketMaster);
        ChartUtils.showCandleChart("Mercado", allCandles, marketMaster.getSymbol());
        return run(allCandles);
    }

    public BackTestResult run(SequenceCandles allCandles){
        marketMaster.buildTradeCache();

        @NotNull
        final StrategyConfig config;
        if (strategy instanceof TradingStrategyConfigurable configurable) {
            config = configurable.getStrategyConfig(operations);
        }else {
            config = StrategyConfig.builder().build();
        }

        int totalSamples = allCandles.size();
        int lookBack = engine == null ? config.getLookBack() : engine.getLookBack();

        int startIndex = lookBack + 1;

        double initialBalance = balance;


        ProgressBar progressBar = new ProgressBar(totalSamples - 1);
        // Loop principal
        for (int i = startIndex; i < totalSamples - 1; i++) {
            progressBar.setCurrentValue(i);
            progressBar.printAsync();

            SequenceCandles window = allCandles.subSequence(i - lookBack, i + 1);
            Optional<PredictionEngine.SequenceCandlesPrediction> prediction;
            if (engine != null && config.getHowUseIA() != null && config.getHowUseIA().useModelIA()) {
                prediction = Optional.of(engine.predictNextPriceDetail(window, config.getFuturePredict()));
            }else {
                prediction = Optional.empty();
            }

            // Consultar estrategia
            strategy.executeStrategy(prediction, window, operations);

            ExecutorCandles executorCandles;
            if (strategy instanceof TradingStrategyExecutor executor) {
                executorCandles = executor.getExecutorCandles(operations);
            }else {
                executorCandles = ExecutorCandles.empty();
            }

            // Inicia la simulaciónn de una vela de duración
            simulateOneTick(
                    allCandles.get(i + 1),
                    operations,
                    executorCandles
            );
            operations.getOpenPosition().ifPresent(TradingManager.OpenPosition::nextStep);
        }

        stats.getTradesComplete().addAll(extraStats);
        return new BackTestResult(
                initialBalance, balance, balance - initialBalance, stats.getRoi(),
                stats.totalTrades, stats.getWins(), stats.getLosses(), stats.maxDrawdownPercent,
                stats
        );
    }

    public void computeClose(@NotNull Double currentPrice,
                             @NotNull TradingManager.OpenPosition openPosition,
                             @NotNull Boolean exitIsMaker
    ){

        double netPnL = getNetPnL(currentPrice, openPosition, exitIsMaker);
        double pnlPercent = netPnL / openPosition.getQuantity();

        balance += netPnL;
        if (balance < 0) balance = 0;

//        TradeResult resultObj = new TradeResult(
//                netPnL,
//                pnlPercent,
//                closeOperation.getExitPrice(),
//                closeOperation.getReason(),
//                closeOperation.getEntryTime(),
//                closeOperation.getExitTime()
//        );
//        stats.addComplenteTrade(resultObj, balance);
//        extraStats.add(new CompleteTrade(
//                ,
//                closeOperation.getReason(),
//                openPosition.getDireccion(),
//                openPosition.getEntryPrice(),
//                closeOperation.getExitPrice(),
//                closeOperation.getEntryTime(),
//                closeOperation.getExitTime(),
//                netPnL,
//                (float) balance,
//                (float) (openPosition.getTpPercent() / openPosition.getSlPercent()),
//                (float) pnlPercent
//        ));
    }

    private double getNetPnL(@NotNull Double currentPrice,
                             @NotNull TradingManager.OpenPosition openPosition,
                             @NotNull Boolean exitIsMaker
    ) {
        double positionSize = openPosition.getQuantity() * openPosition.getLeverage();
        double qty = positionSize / openPosition.getEntryPrice();

        double entryFee = positionSize * (openPosition.getOrder() != null ? marketMaster.getFeedMaker() : marketMaster.getFeedTaker());
        double exitNotional = qty * currentPrice;
        double exitFee = exitNotional * (exitIsMaker ? marketMaster.getFeedMaker() : marketMaster.getFeedTaker());

        double grossPnL = getGrossPnL(currentPrice, openPosition, qty);
        return grossPnL - entryFee - exitFee;
    }

//    private static double roundPnlAgainst(double pnl) {
//        return BigDecimal.valueOf(pnl)
//                .setScale(2, RoundingMode.FLOOR)
//                .doubleValue();
//    }

    private static double getGrossPnL(@NotNull Double currentPrice,
                                      @NotNull TradingManager.OpenPosition openPosition,
                                      @NotNull Double qty
    ) {
        if (openPosition.getDireccion() == DireccionOperation.LONG) {
            return (currentPrice - openPosition.getEntryPrice()) * qty;
        }
        if (openPosition.getDireccion() == DireccionOperation.SHORT) {
            return (openPosition.getEntryPrice() - currentPrice) * qty;
        }
        return 0;
    }

    /**
     * Simula la vida del trade usando el mercado de 1 minuto.
     */
    private void simulateOneTick(@NotNull Candle candle, @NotNull TradingManagerBackTest manager, @NotNull ExecutorCandles executorCandles) {
        List<Trade> trades = marketMaster.getTradesInWindow(candle.getOpenTime(), candle.getCloseTime());
        if (trades.isEmpty()) {
            currentPrice = candle.getClose();
            currentTime = candle.getOpenTime();
            return;
        }
        Optional<TradingManager.OpenPosition> optional = manager.getOpenPosition();
        for (Trade trade : trades) {
            currentPrice = trade.price();
            currentTime = trade.time();
            if (optional.isPresent()){
                for (TradingManager.OrderAlgo orderAlgo : manager.getLimitAlgos()){
                    if (orderAlgo.satisfaceCondicion((currentPrice))) {
                        if (orderAlgo.getTypeOrder().isAllowClosePosition()) {
                            computeClose(currentPrice, optional.get(), orderAlgo.getTypeOrder().isLimit());
                            manager.closeForEngine(
                                    new TradingManagerBackTest.BackTestClosePosition(currentPrice,
                                            currentTime,
                                            TradingManager.ExitReason.LONG_STOP_LOSS,
                                            optional.get()
                                    )
                            );
                        }else {
                            throw new UnsupportedOperationException("Not supported yet.");
                        }
                    }
                }
            }else {
                manager.cancelAllOrderAlgo();
            }
            for (TradingManager.Order order : manager.getOrder()){
                if ((currentPrice >= order.getTriggerPrice() && lastPrice <= order.getTriggerPrice()) ||
                        (currentPrice <= order.getTriggerPrice() && lastPrice >= order.getTriggerPrice())
                ){
                    TradingManagerBackTest.BackTestOpenPosition o = new TradingManagerBackTest.BackTestOpenPosition(manager,
                            currentPrice,
                            order.getDireccion(),
                            order.getQuantity(),
                            order.getLeverage(),
                            order
                    );
                    manager.setOpenOperation(o);
                }
            }
            executorCandles.executeStack(manager);
            lastPrice = currentPrice;
        }
    }

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
                initialBalance = currentBalance - result.pnl;
                peakBalance = initialBalance;
            }

            totalTrades++;
            totalPnL += result.pnl;

            if (result.reason == TradingManager.ExitReason.TIMEOUT) timeouts++;

            if (currentBalance > peakBalance) {
                peakBalance = currentBalance;
                currentDrawdown = 0;
            } else {
                double dd = (peakBalance - currentBalance) / peakBalance;
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
