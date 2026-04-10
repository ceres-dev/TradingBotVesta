package xyz.cereshost.vesta.core.utils.candle;

import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.MMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.averages.WMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.MedianPriceIndicator;
import org.ta4j.core.indicators.supertrend.SuperTrendIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.common.market.Depth;
import xyz.cereshost.vesta.common.market.Market;
import xyz.cereshost.vesta.core.utils.BuilderData;
import xyz.cereshost.vesta.core.utils.ConcurrentHashBiDictionary;
import xyz.cereshost.vesta.core.utils.ProgressBar;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;

@Getter
@SuppressWarnings("unused")
public class CandlesBuilder {

    private final HashMap<String, BiFunction<IndicatorData, Collection<AbstractIndicator<Num>>, Indicator<Num>>> indicators = new HashMap<>();

    @Contract(value = "_, _ -> this")
    public synchronized CandlesBuilder addIndicator(String key, BiFunction<IndicatorData, Collection<AbstractIndicator<Num>>, Indicator<Num>> indicator) {
        indicators.put(key, indicator);
        return this;
    }

    @Contract(value = "_, _ -> this")
    public synchronized CandlesBuilder addSMAIndicator(String key, int barCount) {
        indicators.put(key, (data, indicators) -> new SMAIndicator(data.closes(), barCount));
        return this;
    }

    @Contract(value = "_, _ -> this")
    public synchronized CandlesBuilder addVWAIndicator(String key, int barCount) {
        indicators.put(key, (data, indicators) -> new VWAPIndicator(data.series(), barCount));
        return this;
    }

    @Contract(value = "_, _, _ -> this")
    public synchronized CandlesBuilder addKalmanFilterIndicator(String key, double processNoise, double measurementNoise) {
        indicators.put(key, (data, indicators) -> new KalmanFilterIndicator(data.closes(), processNoise, measurementNoise));
        return this;
    }

    @Contract(value = "_, _, -> this")
    public synchronized CandlesBuilder addEMAIndicator(String key, int barCount) {
        indicators.put(key, (data, indicators) -> new EMAIndicator(data.closes(), barCount));
        return this;
    }

    @Contract(value = "_, _ -> this")
    public synchronized CandlesBuilder addWMAIndicator(String key, int barCount) {
        indicators.put(key, (data, indicators) -> new WMAIndicator(data.closes(), barCount));
        return this;
    }

    @Contract(value = "_, _ -> this")
    public synchronized CandlesBuilder addATRIndicator(String key, int barCount) {
        indicators.put(key, (data, indicators) -> new ATRIndicator(data.series(), barCount));
        return this;
    }

    @Contract(value = "_, _ -> this")
    public synchronized CandlesBuilder addRSIIndicator(String key, int barCount) {
        indicators.put(key, (data, indicators) -> new RSIIndicator(data.closes(), barCount));
        return this;
    }

    @Contract(value = "_, _, _ -> this")
    public synchronized CandlesBuilder addSuperTrendIndicator(String key, int barCount, float multiplier) {
        indicators.put(key, (data, indicators) -> new SuperTrendIndicator(data.series(), barCount, multiplier));
        return this;
    }

    @Contract(value = "_, _, _ -> this")
    public synchronized CandlesBuilder addMACDIndicator(String key, int shortBarCount, int longBarCount) {
        indicators.put(key, (data, indicators) -> new MACDIndicator(data.closes(), shortBarCount, longBarCount));
        return this;
    }

    @Contract(value = "_, _ -> this")
    public synchronized CandlesBuilder addMACDHistogramIndicator(String key, int barCount) {
        indicators.put(key, (data, indicators) -> searchIndicador(indicators, MACDIndicator.class).getHistogram(barCount));
        return this;
    }

    @Contract(value = "_, _ -> this")
    public synchronized CandlesBuilder addMACDSignalIndicator(String key, int barCount) {
        indicators.put(key, (data, indicators) -> searchIndicador(indicators, MACDIndicator.class).getSignalLine(barCount));
        return this;
    }

    /**
     * Crea una instancia de {@link SequenceCandles} a partir de {@link Market}
     * (se debe asegurar el orden temporal antes de llamar el tiempo)
     * {@link SequenceCandles} se le incluye los indicadores técnicos agregados fueron previamente
     * safe-thread
     * @param market Mercado que se va a usar para calcular los indicadores técnicos
     * @return una instancia nueva mutable con los indicadores técnicos agregados
     */

    @NotNull
    @Contract(pure = true, value = "_, -> new")
    public SequenceCandles build(@NotNull Market market) {
        BaseBarSeries series = new BaseBarSeriesBuilder().withName(market.getSymbol().toString()).build();
        NavigableMap<Long, Candle> candleByUnitTime = new TreeMap<>();
        long ms = market.getTimeFrameMarket().getMilliseconds();

        for (Candle cs : market.getCandles()) {
            long minute = (cs.getOpenTime() / ms) * ms;
            candleByUnitTime.put(minute, cs);
        }

        market.buildDepthCache();

        // Crea las barras de la libreria ta4j
        for (Map.Entry<Long, Candle> entry : candleByUnitTime.entrySet()) {
            long minute = entry.getKey();
            Candle cs = entry.getValue();
            try {
                series.addBar(new BaseBar(Duration.ofMillis(ms),
                        Instant.ofEpochMilli(minute),
                        Instant.ofEpochMilli(minute + ms),
                        DecimalNum.valueOf(cs.getOpen()),
                        DecimalNum.valueOf(cs.getHigh()),
                        DecimalNum.valueOf(cs.getLow()),
                        DecimalNum.valueOf(cs.getClose()),
                        DecimalNum.valueOf(cs.getVolumen().baseVolume()),
                        DecimalNum.valueOf(cs.getVolumen().quoteVolume()),
                        0
                ));
            } catch (Exception e) {
                Vesta.sendErrorException("Error al crear la velas", e);
            }
        }

        ClosePriceIndicator closes = new ClosePriceIndicator(series);

        // Crea un diccionario para optimizar espacio en memoria asignada un String a un byte
        byte byteKey = 0;
        ConcurrentHashBiDictionary<String, Byte> dictionary = new ConcurrentHashBiDictionary<>();
        synchronized (indicators) {
            for (String key : indicators.keySet()) {
                dictionary.add(key, byteKey);
                byteKey++;
            }
        }

        long startMinute = candleByUnitTime.firstKey();
        long endMinute = candleByUnitTime.lastKey();
        long step = market.getTimeFrameMarket().getMilliseconds();

        List<SequenceCandles.CandleContainer> candles = new ArrayList<>();
        int index = 0;
        ProgressBar progressBar = new ProgressBar((int) ((endMinute - startMinute) / step));
        progressBar.setEachPrint(100);
        // Crear una vez la instancia de los indicadores técnicos
        Map<String, Indicator<Num>> indicatorsInstanced = new HashMap<>();
        synchronized (indicators) {
            for (Map.Entry<String, BiFunction<IndicatorData, Collection<AbstractIndicator<Num>>, Indicator<Num>>> entry : this.indicators.entrySet()) {
                indicatorsInstanced.put(entry.getKey(), entry.getValue().apply(
                        new IndicatorData(series, closes),
                        // Colección de indicadores
                        indicatorsInstanced.values().stream().filter(indicators ->
                                // Primero filtra las instancias
                                indicators instanceof AbstractIndicator<Num>).map(indicators ->
                                // Realiza el Cast
                                (AbstractIndicator<Num>) indicators).toList()
                ));
            }
        }
        for (long minute = startMinute; minute <= endMinute; minute += step) {
            progressBar.increaseValue();
            if (progressBar.getFinalValue() > 5_000){
                progressBar.print();
            }
            Candle cs = candleByUnitTime.get(minute);
            if (cs != null){
                if (market.getDepthByMinuteCache() != null){
                    Map.Entry<Long, Depth> floor = market.getDepthByMinuteCache().floorEntry(minute);
                    Depth depth = floor != null ? floor.getValue() : null;
                    if (depth != null) {
                        double bidLiq, askLiq, mid , spread;
                        bidLiq = depth.getBids().stream()
                                .mapToDouble(o -> o.price() * o.qty())
                                .sum();

                        askLiq = depth.getAsks().stream()
                                .mapToDouble(o -> o.price() * o.qty())
                                .sum();

                        if (!depth.getBids().isEmpty() && !depth.getAsks().isEmpty()) {
                            double bestBid = depth.getBids().peekFirst().price();
                            double bestAsk = depth.getAsks().peekFirst().price();
                            mid = (bestBid + bestAsk) / 2.0;
                            spread = bestAsk - bestBid;
                            cs.setDepth(new Candle.DepthCandle(bestBid, bestAsk, bidLiq, askLiq, mid, spread));
                        }
                    }
                }

                double[] indicator = new double[indicatorsInstanced.size()];
                try {
                    for (Map.Entry<String, Indicator<Num>> entry : indicatorsInstanced.entrySet()) {
                        byte key = dictionary.getRight(entry.getKey());
                        indicator[key] = checkDouble(entry.getValue().getValue(index).doubleValue());
                    }
                    candles.add(new SequenceCandles.CandleContainer(cs, indicator));
                } catch (IllegalArgumentException ignored) {}
                finally {
                    index++;
                }
            }
        }
        return new SequenceCandles(dictionary, candles);
    }

    private double checkDouble(double d) throws IllegalArgumentException{
        return BuilderData.checkDouble(d);
    }

    @Contract(" -> new")
    public static @NotNull SequenceCandles empty(){
        return new SequenceCandles(new ConcurrentHashBiDictionary<>(), new ArrayList<>(5_000));
    }

    private static <T extends Indicator<Num>> T searchIndicador(@NotNull Collection<AbstractIndicator<Num>> collection, @NotNull Class<T> clazz){
        for (AbstractIndicator<Num> indicator : collection) {
            if (clazz.isInstance(indicator)){
                return clazz.cast(indicator);
            }
        }
        throw new IllegalArgumentException("No se encontró el indicador " + clazz.getSimpleName());
    }

    public record IndicatorData(BaseBarSeries series, ClosePriceIndicator closes){}
}
