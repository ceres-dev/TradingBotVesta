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
import xyz.cereshost.vesta.core.trading.TradingTelemetry;
import xyz.cereshost.vesta.core.utils.ChartUtils;
import xyz.cereshost.vesta.core.utils.ProgressBar;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.util.*;

@Getter
public class BackTestEngine {

    @NotNull private final TradingManagerBackTest manager;
    @NotNull private final Market marketMaster;
    @NotNull private final TradingStrategy strategy;
    @Nullable private final PredictionEngine engine;
    private double balance;
    private double currentPrice;
    private long currentTime;
    @Getter(AccessLevel.NONE)
    private double lastPrice;

    public BackTestEngine(int to, int from, @Nullable PredictionEngine engine, @NotNull TradingStrategy strategy) {
        this.marketMaster = IOMarket.loadMarket(
                strategy.getMarketMaster(),
                new LoadDataMethodLocalRange(true, to, from)
        );
        this.balance = 6;
        this.manager = new TradingManagerBackTest(this);
        this.engine = engine;
        this.strategy = strategy;
    }

    public BackTestEngine(@Nullable PredictionEngine engine, @NotNull TradingStrategy strategy) {
        this(0, 30, engine, strategy);
    }

    public TradingTelemetry run() {
        marketMaster.sortd();

        SequenceCandles allCandles = strategy.getBuilder().build(marketMaster);
        ChartUtils.showCandleChart("Mercado", allCandles, marketMaster.getSymbol());
        return run(allCandles);
    }

    public TradingTelemetry run(SequenceCandles allCandles){
        marketMaster.buildTradeCache();

        @NotNull
        final StrategyConfig config;
        if (strategy instanceof TradingStrategyConfigurable configurable) {
            config = configurable.getStrategyConfig(manager);
        }else {
            config = StrategyConfig.builder().build();
        }

        int totalSamples = allCandles.size();
        int lookBack = engine == null ? config.getLookBack() : engine.getLookBack();

        int startIndex = lookBack + 2;

        lastPrice = currentPrice;
        ProgressBar progressBar = new ProgressBar(totalSamples - 1);

        Candle startCandle = allCandles.subSequence(lookBack, lookBack + 1).getFirst();
        this.currentTime = startCandle.getOpenTime();
        this.currentPrice = startCandle.getOpen();
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
            strategy.executeStrategy(prediction, window, manager);

            ExecutorCandles executorCandles;
            if (strategy instanceof TradingStrategyExecutor executor) {
                executorCandles = executor.getExecutorCandles(manager);
            }else {
                executorCandles = ExecutorCandles.empty();
            }
            // Inicia la simulación de una vela de duración
            simulateOneTick(
                    allCandles.get(i + 1),
                    manager,
                    executorCandles
            );
            manager.getOpenPosition().ifPresent(TradingManager.OpenPosition::nextStep);
        }
        return Objects.requireNonNull(manager.getTelemetry().get());
    }

    public @NotNull TradingTelemetry.TradePerformance computeClose(@NotNull Double currentPrice,
                                                                   @NotNull TradingManager.OpenPosition openPosition,
                                                                   @NotNull Boolean exitIsMaker
    ){
        double positionSize = openPosition.getQuantity() * openPosition.getLeverage();
        double qty = positionSize / openPosition.getTriggerPrice();
        double entryFee = positionSize * (openPosition.getOrder() != null ? marketMaster.getFeedMaker() : marketMaster.getFeedTaker());
        double exitNotional = qty * currentPrice;
        double exitFee = exitNotional * (exitIsMaker ? marketMaster.getFeedMaker() : marketMaster.getFeedTaker());
        double grossPnL = getGrossPnL(currentPrice, openPosition, qty);
        double netPnL = grossPnL - entryFee - exitFee;
        double roiPercent = openPosition.getQuantity() == 0D ? 0D : (netPnL / openPosition.getQuantity()) * 100D;
        balance += netPnL;
        if (balance < 0) balance = 0;

        return new TradingTelemetry.TradePerformance(
                grossPnL,
                netPnL,
                entryFee,
                exitFee,
                roiPercent,
                balance
        );
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
            return (currentPrice - openPosition.getTriggerPrice()) * qty;
        }
        if (openPosition.getDireccion() == DireccionOperation.SHORT) {
            return (openPosition.getTriggerPrice() - currentPrice) * qty;
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
                TradingManager.OpenPosition openPosition = optional.get();
                for (TradingManager.OrderAlgo orderAlgo : manager.getLimitAlgos()){
                    if (orderAlgo.satisfaceCondicion((currentPrice))) {
                        Double quantityRisk = orderAlgo.simuleClose(openPosition);
                        if (quantityRisk == 0) {
                            manager.fillOrderAlgo(orderAlgo, openPosition.getUuid(), currentPrice, currentTime);
                            TradingTelemetry.TradePerformance performance = computeClose(currentPrice, openPosition, orderAlgo.getTypeOrder().isLimit());
                            manager.closeForEngine(
                                    new TradingManagerBackTest.BackTestClosePosition(currentPrice,
                                            currentTime,
                                            getExitReason(openPosition.getDireccion(), orderAlgo.getTypeOrder()),
                                            openPosition
                                    ),
                                    performance
                            );
                            optional = manager.getOpenPosition();
                            break;
                        }
                        if (quantityRisk > 0){
                            throw new UnsupportedOperationException("Not supported yet.");
                        }
                        if (quantityRisk < 0){
                            manager.closeInverseForEngine(Math.abs(quantityRisk), orderAlgo);
                        }
                    }
                }
            }else {
                manager.cancelAllOrderAlgo();
            }
            if (optional.isEmpty()) {
                for (TradingManager.OrderSimple orderSimple : manager.getOrder()){
                    if ((currentPrice >= orderSimple.getTriggerPrice() && lastPrice <= orderSimple.getTriggerPrice()) ||
                            (currentPrice <= orderSimple.getTriggerPrice() && lastPrice >= orderSimple.getTriggerPrice())
                    ){
                        TradingManagerBackTest.BackTestOpenPosition o = new TradingManagerBackTest.BackTestOpenPosition(manager,
                                currentPrice,
                                orderSimple.getDireccion(),
                                orderSimple.getQuantity(),
                                orderSimple.getLeverage(),
                                orderSimple
                        );
                        manager.openForEngine(o);
                        optional = manager.getOpenPosition();
                        break;
                    }
                }
            }
            executorCandles.executeStack(manager);
            optional = manager.getOpenPosition();
            lastPrice = currentPrice;
        }
    }

    private static @NotNull TradingManager.ExitReason getExitReason(@NotNull DireccionOperation direccion,
                                                                    @NotNull xyz.cereshost.vesta.core.trading.TypeOrder typeOrder
    ) {
        if (typeOrder.isTakeProfit()) {
            return direccion == DireccionOperation.LONG
                    ? TradingManager.ExitReason.LONG_TAKE_PROFIT
                    : TradingManager.ExitReason.SHORT_TAKE_PROFIT;
        }
        if (typeOrder.isStopLoss()) {
            return direccion == DireccionOperation.LONG
                    ? TradingManager.ExitReason.LONG_STOP_LOSS
                    : TradingManager.ExitReason.SHORT_STOP_LOSS;
        }
        throw new IllegalArgumentException("Tipo de orden no soportado para cierre: " + typeOrder);
    }
}
