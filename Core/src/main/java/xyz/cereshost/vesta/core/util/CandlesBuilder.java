package xyz.cereshost.vesta.core.util;

import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.AbstractIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.common.market.CandleSimple;
import xyz.cereshost.vesta.common.market.Market;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;

@Getter
@SuppressWarnings("unused")
public class CandlesBuilder {

    private final HashMap<String, BiFunction<BaseBarSeries, ClosePriceIndicator, AbstractIndicator<Num>>> indicators = new HashMap<>();

    @Contract(value = "_, _ -> this")
    public CandlesBuilder addIndicator(String key, BiFunction<BaseBarSeries, ClosePriceIndicator, AbstractIndicator<Num>> indicator) {
        indicators.put(key, indicator);
        return this;
    }

    @Contract(value = "_, _ -> this")
    public CandlesBuilder addSMAIndicator(String key, int barCount) {
        indicators.put(key,(series, closes) -> new SMAIndicator(closes, barCount));
        return this;
    }

    @Contract(value = "_, _ -> this")
    public CandlesBuilder addATRIndicator(String key, int barCount) {
        indicators.put(key, (series, closes) ->  new ATRIndicator(series, barCount));
        return this;
    }

    @Contract(value = "_, _ -> this")
    public CandlesBuilder addRSIIndicator(String key, int barCount) {
        indicators.put(key, (series, closes) ->  new RSIIndicator(closes, barCount));
        return this;
    }

    public SequenceCandles build(Market market) {
        BaseBarSeries series = new BaseBarSeriesBuilder().withName(market.getSymbol()).build();
        NavigableMap<Long, CandleSimple> candleByUnitTime = new TreeMap<>();
        long ms = market.getTimeUnitMarket().getMilliseconds();

        for (CandleSimple cs : market.getCandleSimples()) {
            long minute = (cs.openTime() / ms) * ms;
            candleByUnitTime.put(minute, cs);
        }

        // Crea las barras de la libreria ta4j
        for (Map.Entry<Long, CandleSimple> entry : candleByUnitTime.entrySet()) {
            long minute = entry.getKey();
            CandleSimple cs = entry.getValue();
            try {
                series.addBar(new BaseBar(Duration.ofMillis(ms),
                        Instant.ofEpochMilli(minute),
                        Instant.ofEpochMilli(minute + ms),
                        DecimalNum.valueOf(cs.open()),
                        DecimalNum.valueOf(cs.high()),
                        DecimalNum.valueOf(cs.low()),
                        DecimalNum.valueOf(cs.close()),
                        DecimalNum.valueOf(cs.volumen().baseVolume()),
                        DecimalNum.valueOf(cs.volumen().quoteVolume()),
                        0
                ));
            } catch (Exception e) {
                Vesta.sendErrorException("Error al crear la velas", e);
            }
        }

        ClosePriceIndicator closes = new ClosePriceIndicator(series);

        // Crea un diccionario para optimizar espacio en memoria asignada un String a un byte
        byte byteKey = 0;
        HashMap<String, Byte> dictionary = new HashMap<>();
        for (String key : indicators.keySet()) {
            dictionary.put(key, byteKey);
            byteKey++;
        }

        long startMinute = candleByUnitTime.firstKey();
        long endMinute = candleByUnitTime.lastKey();
        long step = market.getTimeUnitMarket().getMilliseconds();

        List<CandleComplete> candles = new ArrayList<>();
        int index = 0;
        ProgressBar progressBar = new ProgressBar((int) (endMinute/step));

        // Crear una vez la instancia de los indicadores técnicos
        Map<String, AbstractIndicator<Num>> indicatorsInstanced = new HashMap<>();
        for (Map.Entry<String, BiFunction<BaseBarSeries, ClosePriceIndicator, AbstractIndicator<Num>>> entry : this.indicators.entrySet()) {
            indicatorsInstanced.put(entry.getKey(), entry.getValue().apply(series, closes));
        }

        for (long minute = startMinute; minute <= endMinute; minute += step) {
            progressBar.increaseValue();
            if (progressBar.getFinalValue() > 50_000){
                progressBar.print();
            }
            CandleSimple cs = candleByUnitTime.get(minute);
            if (cs != null){
                HashMap<Byte, Double> indicatorsResult = new HashMap<>();
                try {
                    for (Map.Entry<String, AbstractIndicator<Num>> entry : indicatorsInstanced.entrySet()) {
                        byte key = dictionary.get(entry.getKey());
                        indicatorsResult.put(key, checkDouble(entry.getValue().getValue(index).doubleValue()));
                    }
                    candles.add(new CandleComplete(cs, indicatorsResult));
                } catch (IllegalArgumentException ignored) {}
                finally {
                    index++;
                }
            }
        }
        return new SequenceCandles(dictionary, candles);
    }

    public double checkDouble(double d) throws IllegalArgumentException{
        return BuilderData.checkDouble(d);
    }

    public static class SequenceCandles {

        private final HashMap<String, Byte> dictionary;
        private final List<CandleComplete> candlesIndicator;

        private SequenceCandles(HashMap<String, Byte> dictionary, List<CandleComplete> candlesIndicator) {
            this.dictionary = dictionary;
            this.candlesIndicator = candlesIndicator;
        }

        public Double getIndicador(String key, int index) {
            byte d = dictionary.get(key);
            return candlesIndicator.get(index).indicador.get(d);
        }

        public Double getIndicadorLast(String key) {
            byte d = dictionary.get(key);
            return candlesIndicator.getLast().indicador.get(d);
        }

        public CandleSimple getCandle(int index) {
            return candlesIndicator.get(index).candleSimple;
        }

        public CandleSimple getCandleLast() {
            return candlesIndicator.getLast().candleSimple;
        }
    }

    private record CandleComplete(CandleSimple candleSimple, HashMap<Byte, Double> indicador){}
}
