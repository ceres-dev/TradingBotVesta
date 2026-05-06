package xyz.cereshost.vesta.core.command.commnads;

import ai.djl.Device;
import ai.djl.Model;
import ai.djl.util.Pair;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.core.command.Arguments;
import xyz.cereshost.vesta.core.command.BaseCommand;
import xyz.cereshost.vesta.core.command.Flags;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.ia.VestaEngine;
import xyz.cereshost.vesta.core.ia.utils.XNormalizer;
import xyz.cereshost.vesta.core.ia.utils.YNormalizer;
import xyz.cereshost.vesta.core.io.IOMarket;
import xyz.cereshost.vesta.core.io.IOdata;
import xyz.cereshost.vesta.core.io.setup.LoadDataMethodBinance;
import xyz.cereshost.vesta.core.market.*;
import xyz.cereshost.vesta.core.utils.BuilderData;
import xyz.cereshost.vesta.core.utils.ChartUtils;
import xyz.cereshost.vesta.core.utils.Utils;
import xyz.cereshost.vesta.core.utils.candle.CandleIndicators;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.util.ArrayList;
import java.util.List;

public class FastSetupPrediction extends BaseCommand implements Flags {
    public FastSetupPrediction() {
        super("");
        addAlias("fsp");
    }

    @Override
    public void execute(Arguments arguments) throws Exception {
        String nameDevice = arguments.getFlagString("device", "GPU").toUpperCase();
        Model model;
        switch (nameDevice) {
            case "GPU" -> model = IOdata.loadModel(Device.gpu());
            case "CPU" -> model = IOdata.loadModel(Device.cpu());
            default -> throw new IllegalArgumentException("Flags Device invalida");
        }
        Pair<XNormalizer, YNormalizer> pair = IOdata.loadNormalizers();
        PredictionEngine engine = new PredictionEngine(pair.getKey(), pair.getValue(), model);

        Symbol symbol = Symbol.valueOf(arguments.getFlagString("symbol", "SOLUSDC"));
        TimeFrameMarket timeFrameMarket = TimeFrameMarket.parse(arguments.getFlagString("timeframe", "1m"));
        int limitTrade = arguments.getFlagInteger("limitTrade", 0);
        int limitCandles = Math.max(VestaEngine.LOOK_BACK, arguments.getFlagInteger("limitCandles", 900));
        int limitDepth = arguments.getFlagInteger("limitDepth", 0);

        Market market = IOMarket.loadMarket(new TypeMarket(symbol, timeFrameMarket),new LoadDataMethodBinance(limitCandles, limitTrade, limitDepth));
        market.sortd();

        int horizon = Math.max(1, arguments.getFlagInteger("maxHorizon", 15));
        int candlesAgo = arguments.getFlagInteger("offset", 0);
        showPredictionSnapshotRealMarket(market, engine, candlesAgo, horizon);
    }

    private static final String VALUE_SHOW = "close";


    @Override
    public List<Flag> getFlags() {
        return List.of(
                new Flag("device", TypeValue.STRING, "GPU", "CPU"),
                new Flag("symbol", TypeValue.STRING, Utils.enumsToStrings(Symbol.values())),
                new Flag("timeframe", TypeValue.STRING, Utils.enumsToStrings(TimeFrameMarket.values())),
                new Flag("limitTrade", TypeValue.INTEGER),
                new Flag("limitCandles", TypeValue.INTEGER),
                new Flag("limitDepth", TypeValue.INTEGER),
                new Flag("maxHorizon", TypeValue.INTEGER),
                new Flag("offset", TypeValue.INTEGER)
        );
    }

    public static void showPredictionSnapshotRealMarket(Market market, PredictionEngine engine, int candlesAgo, int horizon) {
        if (market == null || engine == null) {
            Vesta.error("Market o PredictionEngine es null");
            return;
        }

        SequenceCandles candles = BuilderData.getProfierCandlesBuilder().build(market);
        List<Candle> candleBases = candles.toCandlesSimple();
        if (candles.isEmpty() || candleBases.isEmpty()) {
            Vesta.error("No hay velas para mostrar");
            return;
        }

        int lookBack = engine.getLookBack();
        int safeHorizon = Math.max(1, horizon);
        int latestIndex = candles.size() - 1;
        int safeAgo = Math.max(0, candlesAgo);
        int idx = latestIndex - safeAgo;

        if (idx < lookBack) {
            int maxAgo = Math.max(0, latestIndex - lookBack);
            Vesta.error("index fuera de rango. Valor maximo permitido: %d (pedido: %d)", maxAgo, safeAgo);
            return;
        }

        int start = idx - lookBack + 1;
        List<Candle> lookbackCandles = candleBases.subList(start, idx + 1);

        SequenceCandles window = candles.subSequence(0, idx + 1);
        PredictionEngine.SequenceCandlesPrediction result = engine.predictNextPriceDetail(window, safeHorizon);
        if (result == null || result.isEmpty()) {
            Vesta.error("No se pudo generar prediccion");
            return;
        }

        long candleMs = market.getTimeFrameMarket().getMilliseconds();
        if (idx > 0) {
            long diff = candles.get(idx).getOpenTime() - candles.get(idx - 1).getOpenTime();
            if (diff > 0) {
                candleMs = diff;
            }
        }

        List<ChartUtils.ClosePredictionPoint> predicted = new ArrayList<>();
        double lastClose = candles.getCandle(idx).get(VALUE_SHOW);
        long baseTime = candles.get(idx).getOpenTime();
        for (int k = 0; k < result.size(); k++) {
            double diff = result.get(k).get(0);
            double predictedClose = lastClose * (1.0 + diff);
            lastClose = predictedClose;

            long time;
            int tIdx = idx + 1 + k;
            if (tIdx < candles.size()) {
                time = candles.get(tIdx).getOpenTime();
            } else {
                time = baseTime + (k + 1L) * candleMs;
            }
            predicted.add(new ChartUtils.ClosePredictionPoint(time, predictedClose));
        }

        List<ChartUtils.ClosePredictionPoint> actual = new ArrayList<>();
        for (int k = 0; k < safeHorizon; k++) {
            int tIdx = idx + 1 + k;
            if (tIdx >= candles.size()) {
                break;
            }
            CandleIndicators c = candles.getCandle(tIdx);
            actual.add(new ChartUtils.ClosePredictionPoint(c.getOpenTime(), c.get(VALUE_SHOW)));
        }

        Vesta.info("Prediction real snapshot -> typeMarkets=%s, index=%d (hace %d velas), horizon=%d, actualDisponibles=%d",
                market.getSymbol(), idx, safeAgo, safeHorizon, actual.size());

        ChartUtils.showCandlePredictionSnapshot(
                "Prediccion Real " + market.getSymbol() + " (index=" + safeAgo + ")",
                lookbackCandles,
                predicted,
                actual,
                candles.get(idx).getOpenTime()
        );
    }

}
