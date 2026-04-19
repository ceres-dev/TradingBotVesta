package xyz.cereshost.vesta.core.utils;

import ai.djl.util.Pair;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.common.market.Market;
import xyz.cereshost.vesta.common.market.Symbol;
import xyz.cereshost.vesta.common.market.TypeMarket;
import xyz.cereshost.vesta.core.Main;
import xyz.cereshost.vesta.core.ia.VestaEngine;
import xyz.cereshost.vesta.core.ia.utils.TrainingData;
import xyz.cereshost.vesta.core.io.IOMarket;
import xyz.cereshost.vesta.core.io.IOdata;
import xyz.cereshost.vesta.core.io.setup.LoadDataMethodLocalIndex;
import xyz.cereshost.vesta.core.utils.candle.CandleIndicators;
import xyz.cereshost.vesta.core.utils.candle.CandlesBuilder;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static xyz.cereshost.vesta.core.ia.VestaEngine.LOOK_BACK;

@SuppressWarnings("UnusedAssignment")
@UtilityClass
public class BuilderData {

    public static final int DEFAULT_FUTURE_WINDOW = 30;

    public static @NotNull TrainingData buildTrainingData(@NotNull List<TypeMarket> typeMarkets, int maxMonth, int offset, CandlesBuilder candlesBuilder) {
        List<PairCache> cacheEntries = new ArrayList<>();
        long time = System.currentTimeMillis();
        List<CompletableFuture<Object>> waitingCacheSave = new ArrayList<>();
        for (TypeMarket typeMarket : typeMarkets) {
            final Symbol symbol = typeMarket.symbol();
            try {
                SequenceCandles allCandlesForChart = SequenceCandles.empty();

                Vesta.info("Procesando símbolo (Relativo): " + typeMarket.symbol());

                // Procesar cada mes por separado SIN acumular
                List<Integer> months = IntStream.rangeClosed(1, maxMonth)
                        .boxed()
                        .toList();

                // Crear lista de futuros para procesar cada mes de forma asincrónica
                AtomicInteger totalDays = new AtomicInteger();
                AtomicInteger doneDays = new AtomicInteger();
                List<CompletableFuture<MonthMarketCache>> futures = new ArrayList<>();
                for (int month = maxMonth + offset; month > offset; month--) {
                    final int currentMonth = month;
                    YearMonth targetMonth = IOMarket.resolveMonthFromIndex(currentMonth);
                    int daysInMonth = targetMonth.lengthOfMonth();
                    totalDays.addAndGet(daysInMonth);
                    CompletableFuture<MonthMarketCache> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            System.gc();
                            Vesta.info("(idx:%d) Comenzado carga de datos", currentMonth);

                            SequenceCandles candlesThisMonth = CandlesBuilder.empty();
                            for (int day = 1; day <= daysInMonth; day++) {
                                LocalDate date = targetMonth.atDay(day);
                                int dayIndex = IOMarket.resolveDayIndex(date);
                                Vesta.info("(idx:%d) ⬆️ Cargando dia %s", currentMonth, date);
                                Market market = IOMarket.loadMarket(typeMarket, new LoadDataMethodLocalIndex(false, dayIndex));
                                if (market == null) {
                                    Vesta.warning("(idx:%d) Mercado vacio para %s", currentMonth, date);
                                    continue;
                                }
                                Vesta.info("(idx:%d) 📊 Convirtiendo velas (dia %s, C:%d)", currentMonth, date, market.getCandles().size());
                                SequenceCandles candlesDay = candlesBuilder.build(market);
                                market.clear();
                                if (!candlesDay.isEmpty()) {
                                    candlesThisMonth.addAll(candlesDay);
                                }
                                doneDays.getAndIncrement();
                                Vesta.info("(idx:%d) (%d/%d) (%d/%d) ✅ Dia cargado", currentMonth, day, daysInMonth, doneDays.get(), totalDays.get());
                            }
                            if (candlesThisMonth.size() <= LOOK_BACK + 2) {
                                Vesta.warning("(idx:%d) insuficiente historial: " + candlesThisMonth.size() + " velas", currentMonth);
                                return new MonthMarketCache(0, 0, 0, 0, candlesThisMonth, false);
                            }

                            Vesta.info("(idx:%d) 📦 Exportando Pair (C:%d)", currentMonth, candlesThisMonth.size());

                            Pair<float[][][], float[][]> pair = BuilderData.buildPair(candlesThisMonth, LOOK_BACK);
                            AtomicReference<float[][][]> Xraw = new AtomicReference<>(pair.getKey());
                            AtomicReference<float[][]> yraw = new AtomicReference<>(pair.getValue());

                            if (Xraw.get().length == 0) {
                                return new MonthMarketCache(0, 0, 0, 0, candlesThisMonth, false);
                            }

                            int samples = Xraw.get().length;
                            int seqLen = Xraw.get()[0].length;
                            int features = Xraw.get()[0][0].length;
                            int yCols = yraw.get()[0].length;
                            if (maxMonth < 6) {
                                candlesThisMonth.clear();
                            }
                            pair = null;
                            // Guarda el resultado del pair
                            Vesta.info("(idx:%d) 💿 Guardando resultado del Pair", currentMonth);
                            MonthMarketCache cache = new MonthMarketCache(samples, seqLen, features, yCols, candlesThisMonth, true);
                            waitingCacheSave.add(CompletableFuture.supplyAsync(() -> {
                                try {
                                    Path path = IOdata.saveTrainingCache(IOdata.createTrainingCacheDir(typeMarkets), typeMarket.symbol(), currentMonth, Xraw.get(), yraw.get(), false);
                                    cache.setCacheFile(path);
                                } catch (IOException ignored) {}
                                Arrays.fill(Xraw.get(), null);
                                Arrays.fill(yraw.get(), null);
                                Xraw.set(null);
                                yraw.set(null);
                                return new Object();
                            }, VestaEngine.EXECUTOR_WRITE_CACHE_BUILD));
                            return cache;
                        } catch (Exception e) {
                            Vesta.sendWaringException(String.format("(idx:%d) Error procesando mes: " + e.getMessage(), currentMonth), e);
                            return new MonthMarketCache(0, 0, 0, 0, CandlesBuilder.empty(), false);
                        }
                    }, VestaEngine.EXECUTOR_BUILD);

                    futures.add(future);
                }

                // Esperar a que todos los futuros completen y procesar en orden
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        MonthMarketCache result = futures.get(i).get(); // Bloquea para mantener orden
                        int month = months.get(i);

                        if (result.hasData) {
                            if (maxMonth < 6) {
                                allCandlesForChart.addAll(result.candles);
                            }

                            if (result.samples > 0) {
                                // Añadir objeto que indica que se guardó caché del pair
                                cacheEntries.add(result);
                                Vesta.info("(%d/%d) ✅ procesado: %d muestras (%s)", month, maxMonth, result.samples, symbol);
                            }else {
                                Vesta.warning("(%d/%d) ⚠️ No hay datos (%s)", month, maxMonth, symbol);
                            }
                        }
                    } catch (InterruptedException | ExecutionException e) {
                        Vesta.sendWaringException(String.format("(idx:%d) Error procesando mes para " + symbol + ": " + e.getMessage(), months.get(i)), e);
                        Thread.currentThread().interrupt();
                    }
                }

                if (!allCandlesForChart.isEmpty()) {
                    ChartUtils.showCandleChart("Mercado", allCandlesForChart, symbol);
                }
            } catch (Exception e) {
                Vesta.sendWaringException("Error construyendo data para " + symbol + ": " + e.getMessage(), e);
            }
        }

        Vesta.info("💤 Esperando que termine de escribir en disco");
        for (CompletableFuture<Object>  future : waitingCacheSave) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                Vesta.sendWaringException("Error al obtener mantener el hilo pausado", e);
            }
        }
        if (cacheEntries.isEmpty()) {
            throw new RuntimeException("No se generó data de entrenamiento válida.");
        }

        // Concatenar todos los símbolos
        int totalSamples = cacheEntries.stream().mapToInt(entry -> entry.samples).sum();
        int seqLen = cacheEntries.getFirst().seqLen;
        int features = cacheEntries.getFirst().features;
        int yCols = cacheEntries.getFirst().yCols;

        long delta = (System.currentTimeMillis() - time);
        Vesta.info("✅ Construcción completada de %s (S: %d) +T: %dm %ds", String.join(", ", typeMarkets.stream().map(TypeMarket::symbol).map(Symbol::toString).toList()), totalSamples, delta/60_000,((delta/1000)%60));

        System.gc();
        return new TrainingData(cacheEntries.stream().map(PairCache::getCacheFile).toList(), totalSamples, seqLen, features, yCols);

    }

    @Getter
    private static class MonthMarketCache extends PairCache {
        final SequenceCandles candles;
        final boolean hasData;

        MonthMarketCache(int samples, int seqLen, int features, int yCols, SequenceCandles candles, boolean hasData) {
            super(samples, seqLen, features, yCols);
            this.candles = candles;
            this.hasData = hasData;
        }
    }

    @Setter
    @Getter
    private static class PairCache {
        Path cacheFile;
        final int samples;
        final int seqLen;
        final int features;
        final int yCols;

        PairCache(int samples, int seqLen, int features, int yCols) {
            this.samples = samples;
            this.seqLen = seqLen;
            this.features = features;
            this.yCols = yCols;
        }
    }


    @Contract("_, _-> new")
    public static @NotNull Pair<float[][][], float[][]> buildPair(@NotNull SequenceCandles candles, int lookBack) {
        int n = candles.size();
        int samples = n - lookBack - 1;

        if (samples <= 0) return new Pair<>(new float[0][0][0], new float[0][0]);
        int validSamples = 0;
        for (int i = 0; i < samples; i++) {
            CandleIndicators cOld = candles.getCandle(i);
            CandleIndicators cNew = candles.getCandle(i + 1);
            // Aquí va la logica en caso de que se requiera descartar datos
            validSamples++;
        }
        if (validSamples <= 0) return new Pair<>(new float[0][0][0], new float[0][0]);

        float[][][] X = new float[validSamples][lookBack][FEATURES];
        float[][] y = new float[validSamples][5];
        int idx = 0;
        for (int i = 0; i < samples; i++) {
            CandleIndicators cOld = candles.getCandle(i);
            CandleIndicators cNew = candles.getCandle(i + 1);
            // X
            for (int j = 0; j < lookBack; j++)
                X[idx][j] = extractFeatures(
                        candles.getCandle(i + j + 1),
                        candles.getCandle(i + j)
                );
            // Y
            double delta = cNew.getDiffPercent()*4;
            y[idx] = new float[]{Math.clamp((delta > 0) ? (float) Math.log1p(delta) : (float) -Math.log1p(Math.abs(delta)), -1, 1)};
            idx++;
        }
        return new Pair<>(X, y);
    }

    public final static int FEATURES = 8;

    public static float @NotNull [] extractFeatures(@NotNull CandleIndicators curr, @NotNull CandleIndicators prev) {
        if (curr.getMetrics() == null || prev.getMetrics() == null) return new float[0];
        List<Float> fList = new ArrayList<>();
        fList.add(safeDiffPercent(curr.getClose(), prev.getClose()));
        fList.add(safeDiffPercent(curr.getHigh(), prev.getHighBody()));
        fList.add(safeDiffPercent(curr.getLow(), prev.getLowBody()));
        fList.add((float) Math.log(curr.getVolumen().baseVolume()));
        fList.add(safeDiffPercent(curr.getMetrics().getCountTopTradesLongShortRatio(), prev.getMetrics().getCountTopTradesLongShortRatio()));
        fList.add(safeDiffPercent(curr.getMetrics().getCountTradesLongShortRatio(), prev.getMetrics().getCountTradesLongShortRatio()));
        fList.add(safeDiffPercent(curr.getMetrics().getSumOpenInterest(), prev.getMetrics().getSumOpenInterest()));
        fList.add(safeDiffPercent(curr.get("obv"), prev.get("obv")));

        float[] f = new float[fList.size()];
        for (int i = 0; i < fList.size(); i++) {
            float v = fList.get(i);
            f[i] = Float.isFinite(v) ? v : 0f;
        }
        return f;
    }

    public CandlesBuilder getProfierCandlesBuilder(){
        return new CandlesBuilder().addOBVIndicator("obv");
    }

    public static double checkDouble(double d) throws IllegalArgumentException{
        if (Double.isInfinite(d) || Double.isNaN(d)) {
            throw new IllegalArgumentException("The input is infinite or NaN");
        }
        return d;
    }

    private static float safeLogRatio(double numerator, double denominator) {
        if (!Double.isFinite(numerator) || !Double.isFinite(denominator)) return 0f;
        if (numerator <= 0.0 || denominator <= 0.0) return 0f;
        double ratio = numerator / denominator;
        if (!Double.isFinite(ratio) || ratio <= 0.0) return 0f;
        double v = Math.log(ratio);
        return Double.isFinite(v) ? (float) v : 0f;
    }

    public static float safeDiffPercent(double nw, double old) {
        float v = (float) ((nw - old)/old);
        return Double.isFinite(v) ? v : 0f;
    }

    private static float safeLog1p(double value) {
        if (!Double.isFinite(value)) return 0f;
        if (value <= -1.0) return 0f;
        double v = Math.log1p(Math.max(0.0, value));
        return Double.isFinite(v) ? (float) v : 0f;
    }

    private static final double SAFE_EPS = 1e-8;

    private static float safeDiv(double numerator, double denominator) {
        if (!Double.isFinite(numerator) || !Double.isFinite(denominator)) return 0f;
        if (Math.abs(denominator) < SAFE_EPS) return 0f;
        double v = numerator / denominator;
        return Double.isFinite(v) ? (float) v : 0f;
    }

    private static float safeFloat(double value) {
        return Double.isFinite(value) ? (float) value : 0f;
    }
}





