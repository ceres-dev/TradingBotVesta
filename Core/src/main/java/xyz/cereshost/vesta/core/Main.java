package xyz.cereshost.vesta.core;

import ai.djl.util.Pair;
import com.google.gson.Gson;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.common.market.CandleSimple;
import xyz.cereshost.vesta.common.market.Market;
import xyz.cereshost.vesta.common.market.Volumen;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.ia.VestaEngine;
import xyz.cereshost.vesta.core.ia.utils.EngineUtils;
import xyz.cereshost.vesta.core.ia.utils.XNormalizer;
import xyz.cereshost.vesta.core.ia.utils.YNormalizer;
import xyz.cereshost.vesta.core.io.IOMarket;
import xyz.cereshost.vesta.core.io.IOdata;
import xyz.cereshost.vesta.core.message.DiscordNotification;
import xyz.cereshost.vesta.core.strategys.AlfaStrategy;
import xyz.cereshost.vesta.core.strategys.BetaStrategy;
import xyz.cereshost.vesta.core.strategys.EtaStrategy;
import xyz.cereshost.vesta.core.strategys.ZetaStrategy;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.trading.backtest.BackTestEngine;
import xyz.cereshost.vesta.core.trading.real.TradingTickLoop;
import xyz.cereshost.vesta.core.trading.real.api.BinanceApiRest;
import xyz.cereshost.vesta.core.utils.BuilderData;
import xyz.cereshost.vesta.core.utils.ChartUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

public class Main {

    public static final String NAME_MODEL = "VestaIA";

    @NotNull public static final List<String> SYMBOLS_TRAINING = List.of("SOLUSDC");
    @NotNull public static final String SYMBOL = "SOLUSDC";
    @NotNull public static final DataSource DATA_SOURCE_FOR_TRAINING_MODEL = DataSource.LOCAL_ZST;
    @NotNull public static final DataSource DATA_SOURCE_FOR_BACK_TEST = DataSource.LOCAL_ZST;
    public static final int MAX_MONTH_TRAINING = 12*2;
    public static final Gson GSON = new Gson();


    @Getter
    private static Main instance;

    public static void main(String[] args) throws IOException, InterruptedException, ExecutionException {
        instance = new Main();
//        System.setProperty("java.awt.headless","true");
//        new PacketHandler();
        switch (args[0]) {
            case "training" -> {
                List<String> symbols = SYMBOLS_TRAINING;
                //checkEngines();

                VestaEngine.TrainingTestsResults result = VestaEngine.trainingModel(symbols);
                EngineUtils.ResultsEvaluate evaluateResult = result.evaluate();
                BackTestEngine.BackTestResult backtestResult = result.backtest();

                // Mostrar distribución de errores por dirección
                showDataBackTest(backtestResult);
            }
//            case "backtest" -> showDataBackTest(new BackTestEngine(IOMarket.loadMarkets(DATA_SOURCE_FOR_BACK_TEST, SYMBOL).limit(3), PredictionEngine.loadPredictionEngine("VestaIA"), new AlfaStrategy()).run());
            case "backtest" -> {
                Market market = getMarket();
                Vesta.info("🔙 Ejecutando backtest...");
                market.sortdInChunks();
                Pair<XNormalizer, YNormalizer> pair = IOdata.loadNormalizers();
                showDataBackTest(new BackTestEngine(market, new PredictionEngine(pair.getKey(), pair.getValue(), IOdata.loadModel(), VestaEngine.LOOK_BACK, BuilderData.FEATURES), new AlfaStrategy()).run());
            }
            case "trading" -> new TradingTickLoop("SOLUSDC", null, new ZetaStrategy(), new BinanceApiRest(false), new DiscordNotification()).startCandleLoop();
            case "extract" -> IOMarket.extractFirstBin(Path.of(IOMarket.STORAGE_DIR + "\\" + SYMBOL +"\\trades"));
            case "diagnose" -> {
                Market market = getMarket();
                Pair<XNormalizer, YNormalizer> pair = IOdata.loadNormalizers();
                List<Integer> indexes = List.of(120, 180, 240, 300, 360, 420, 480);
                for (int i : indexes) {
                    showPredictionSnapshot(market, new PredictionEngine(pair.getKey(), pair.getValue(), IOdata.loadModel(), VestaEngine.LOOK_BACK, BuilderData.FEATURES), i, 30);
                }
            }
        }
    }

    private static @NotNull Market getMarket() throws InterruptedException, ExecutionException {
        Market market = new Market(SYMBOL);
        List<CompletableFuture<Market>> task = new ArrayList<>();
        for (int day = 2; day >= 0; day--) {
            int finalDay = day;
            task.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return Objects.requireNonNull(IOMarket.loadMarkets(Main.DATA_SOURCE_FOR_BACK_TEST, SYMBOL, finalDay), "Dia: " + finalDay);
                } catch (InterruptedException | IOException e) {
                    return null;
                }
            }, VestaEngine.EXECUTOR_AUXILIAR_BUILD));
        }
        for (CompletableFuture<Market> future : task) {
            Market m = future.get();
            if (m == null) continue;
            market.concat(m);
        }
        return market;
    }


    private static void showDataBackTest(BackTestEngine.BackTestResult backtestResult) {
        BackTestEngine.BackTestStats stats = backtestResult.stats();
        ChartUtils.plotRatioVsROI("BackTest Ratio/ROI (Walk-Forward)", stats.getTradesComplete());
        ChartUtils.plotTPSLMagnitudeVsROI("BackTest Magnitud/ROI (Walk-Forward)", stats.getTradesComplete());
        ChartUtils.plot("BackTest Ratio (Walk-Forward)", "Trades", List.of(
                new ChartUtils.DataPlot("Ratio", stats.getTradesComplete().stream().map(BackTestEngine.CompleteTrade::getRatio).toList())
        ));
        ChartUtils.plot("BackTest Balance (Walk-Forward)", "Trades", List.of(
                new ChartUtils.DataPlot("Balance", stats.getTradesComplete().stream().map(BackTestEngine.CompleteTrade::getBalance).toList())
        ));
        ChartUtils.plot("BackTest ROI (Walk-Forward)", "Trades", List.of(
                new ChartUtils.DataPlot("ROI%", stats.getTradesComplete().stream().map(BackTestEngine.CompleteTrade::getPnlPercent).toList())
        ));
        //ChartUtils.animateCandlePredictions(stats.getMarket().getSymbol(), stats.getMarket().getCandleSimples().stream().toList(), stats.getAllTrades(), BuilderData.DEFAULT_FUTURE_WINDOW, 200);
        ChartUtils.showCandleChartWithTrades("Trades", stats.getMarket().getCandleSimples().stream().toList(), stats.getMarket().getSymbol(), stats.getTradesComplete());
        double winRate = stats.getTotalTrades() > 0 ? (double) stats.getWins() / stats.getTotalTrades() * 100 : 0;
        double avgHoldMinutes = stats.getTotalTrades() > 0 ? (stats.getHoldAvg() / 1000.0 / 60.0) : 0;
        double avgHoldMinutesWin = stats.getWins() > 0 ? (stats.getHoldAvgWins() / 1000.0 / 60.0) : 0;
        double avgHoldMinutesLoss = stats.getLosses() > 0 ? (stats.getHoldAvgLoss() / 1000.0 / 60.0) : 0;
        double performer = winRate - (Math.abs(stats.getRoiLosses()) / (Math.abs(stats.getRoiLosses()) + stats.getRoiWins()))*100;

        DecimalFormat decimalFormat = new DecimalFormat("###,###,###,###,##0.00");

        Vesta.info("--------------------------------------------------");
        Vesta.info("💰 SIMULACIÓN DE TRADING (Capital: $%.0f)", backtestResult.initialBalance());
        Vesta.info(" Trades Totales:          %d",  stats.getTotalTrades());
        Vesta.info("  Win Rate:               %.2f%% (%d W / %d L)", winRate, stats.getWins(), stats.getLosses());
        Vesta.info("  Timeouts:               %d (Salida por tiempo) ROI %.2f%% ", stats.getTimeouts(), stats.getRoiTimeOut());
        Vesta.info("  Total TP/SL/STR:        %d TP / %s SL / %s STR", stats.getTrades(TradingManager.ExitReason.LONG_TAKE_PROFIT) + stats.getTrades(TradingManager.ExitReason.SHORT_TAKE_PROFIT), stats.getTrades(TradingManager.ExitReason.LONG_STOP_LOSS) + stats.getTrades(TradingManager.ExitReason.SHORT_STOP_LOSS), stats.getTrades(TradingManager.ExitReason.STRATEGY));
        Vesta.info("  L TP/SL:                %d TP / %s SL", stats.getTrades(TradingManager.ExitReason.LONG_TAKE_PROFIT), stats.getTrades(TradingManager.ExitReason.LONG_STOP_LOSS));
        Vesta.info("  S TP/SL:                %d TP / %s SL", stats.getTrades(TradingManager.ExitReason.SHORT_TAKE_PROFIT), stats.getTrades(TradingManager.ExitReason.SHORT_STOP_LOSS));
        Vesta.info("  Ratio (P/M/N/R)         %.3f %.3f %.3f %.3f", stats.getRatioAvg(), stats.getRatioMax(), stats.getRatioMin(), stats.getRoiWins() / Math.abs(stats.getRoiLosses()));
        Vesta.info("  ROI TP (Min)            %.2f%% L %.2f%% S", stats.getRoiTPMinLong(), stats.getRoiTPMinShort());
        Vesta.info("  ROI STR                 %.2f%% (%.2f%%)", stats.getRoiStrategy(), stats.getRoiAvg(TradingManager.ExitReason.STRATEGY));
        Vesta.info("  DireRate:               (%d %.2f%% L, %d %.2f%% S, %d N)", stats.getLongs(), stats.getRoiLong(), stats.getShorts(), stats.getRoiShort(), stats.getNothing());
        Vesta.info("  Avg Hold Time P/W/L:    %.3fm %.3fm %.3fm", avgHoldMinutes, avgHoldMinutesWin, avgHoldMinutesLoss);
        Vesta.info("  Roi P/W/L               %.3f%% %.3f%% %.3f%%", stats.getRoiAvg(), stats.getRoiWins(),  stats.getRoiLosses());
        Vesta.info("  PNL Neto:               %s$%s%s", backtestResult.netPnL() >= 0 ? "\u001B[32m" : "\u001B[31m", decimalFormat.format(backtestResult.netPnL()), "\u001B[0m");
        Vesta.info("  ROI Total:              %s%s%%%s", backtestResult.roiPercent() >= 0 ? "\u001B[32m" : "\u001B[31m", decimalFormat.format(backtestResult.roiPercent()), "\u001B[0m");
        Vesta.info("  Rendimiento:            %s%.2f%%%s ", performer >= 0 ? "\u001B[32m" : "\u001B[31m", performer, "\u001B[0m");
        Vesta.info("  Max Drawdown:           %.2f%%", backtestResult.maxDrawdown()*100);
        Vesta.info("--------------------------------------------------");

        System.gc();
    }

    public static void showPredictionSnapshot(Market market, PredictionEngine engine) {
        showPredictionSnapshot(market, engine, 250, BuilderData.DEFAULT_FUTURE_WINDOW);
    }

    public static void showPredictionSnapshot(Market market, PredictionEngine engine, int predictionIndex, int horizon) {
        if (market == null || engine == null) {
            Vesta.error("Market o PredictionEngine es null");
            return;
        }

        List<Candle> candles = BuilderData.to1mCandles(market);
        List<CandleSimple> candleSimples = candles.stream()
                .map(c -> new CandleSimple(
                        c.openTime(),
                        c.open(),
                        c.high(),
                        c.low(),
                        c.close(),
                        new Volumen(
                                c.quoteVolume(),
                                c.volumeBase(),
                                c.buyQuoteVolume(),
                                c.sellQuoteVolume(),
                                c.deltaUSDT(),
                                c.buyRatio()
                        )
                ))
                .toList();

        if (candles.isEmpty() || candleSimples.isEmpty()) {
            Vesta.error("No hay velas para mostrar");
            return;
        }

        int lookBack = engine.getLookBack();
        int safeHorizon = Math.max(1, horizon);
        int maxIndex = candles.size() - safeHorizon - 1;
        if (maxIndex < lookBack) {
            Vesta.error("Historial insuficiente para prediccion: %d velas (se requieren %d)",
                    candles.size(), lookBack + safeHorizon + 1);
            return;
        }

        int idx = predictionIndex < 0 ? maxIndex : Math.min(predictionIndex, maxIndex);
        if (idx < lookBack) {
            Vesta.error("predictionIndex debe ser >= lookBack (%d)", lookBack);
            return;
        }

        int start = idx - lookBack + 1;
        List<CandleSimple> lookbackCandles = candleSimples.subList(start, idx + 1);

        List<Candle> window = candles.subList(0, idx + 1);
        PredictionEngine.PredictionResult result = engine.predictNextPriceDetail(window, safeHorizon);
        System.out.println(result.getCandles());
        if (result == null || result.getCandles() == null || result.getCandles().isEmpty()) {
            Vesta.error("No se pudo generar prediccion");
            return;
        }

        List<ChartUtils.ClosePredictionPoint> predicted = new ArrayList<>();
        double lastClose = candles.get(idx).close();
        long baseTime = candles.get(idx).openTime();
        for (int k = 0; k < result.getCandles().size(); k++) {
            double diff = result.getCandles().get(k).close();
            double predictedClose = lastClose * (1.0 + diff);
            lastClose = predictedClose;

            long time;
            int tIdx = idx + 1 + k;
            if (tIdx < candles.size()) {
                time = candles.get(tIdx).openTime();
            } else {
                time = baseTime + (k + 1L) * 60_000L;
            }
            predicted.add(new ChartUtils.ClosePredictionPoint(time, predictedClose));
        }

        List<ChartUtils.ClosePredictionPoint> actual = new ArrayList<>();
        for (int k = 0; k < safeHorizon; k++) {
            int tIdx = idx + 1 + k;
            if (tIdx >= candles.size()) {
                break;
            }
            Candle c = candles.get(tIdx);
            actual.add(new ChartUtils.ClosePredictionPoint(c.openTime(), c.close()));
        }

        ChartUtils.showCandlePredictionSnapshot(
                "Prediccion " + market.getSymbol(),
                lookbackCandles,
                predicted,
                actual,
                candles.get(idx).openTime()
        );
    }
}
