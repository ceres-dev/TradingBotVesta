package xyz.cereshost.vesta.core.utils.candle;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Delegate;
import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.common.market.Candle;

import java.util.*;

public class SequenceCandles implements List<SequenceCandles.CandleContainer> {

    private final HashMap<Byte, String> dictionaryByte;
    private final HashMap<String, Byte> dictionaryString;
    @Delegate
    private final List<CandleContainer> candlesContainer;

    public SequenceCandles(HashMap<String, Byte> dictionaryString, List<CandleContainer> candlesContainer) {
        this.dictionaryString = dictionaryString;
        HashMap<Byte, String> dictionaryByte = new HashMap<>();
        for (Map.Entry<String, Byte> entry : dictionaryString.entrySet()) dictionaryByte.put(entry.getValue(), entry.getKey());
        this.dictionaryByte = dictionaryByte;
        this.candlesContainer = candlesContainer;
    }

    public CandleIndicators getCandleLast() {
        return getCandle(candlesContainer.size() - 1);
    }

    public CandleIndicators getCandle(int index) {
        CandleContainer candleContainer = candlesContainer.get(index);
        HashMap<String, Double> map = new HashMap<>();
        for (byte key = 0; key < candleContainer.indicador().length; key++) {
            map.put(dictionaryByte.get(key), candleContainer.indicador()[key]);
        }
        return new CandleIndicators(candleContainer, map, this);
    }

    public SequenceCandles copy(){
        return new SequenceCandles(dictionaryString, candlesContainer);
    }

    public SequenceCandles subSequence(int fromIndex, int toIndex) {
        return new SequenceCandles(dictionaryString, candlesContainer.subList(fromIndex, toIndex));
    }

    public List<Candle> toCandlesSimple() {
        return new ArrayList<>(candlesContainer);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends CandleContainer> c) {
        if (c instanceof SequenceCandles sequenceCandles) {
            this.dictionaryByte.putAll(sequenceCandles.dictionaryByte);
            this.dictionaryString.putAll(sequenceCandles.dictionaryString);
        }
        return candlesContainer.addAll(c);
    }

    public static SequenceCandles empty(){
        return new SequenceCandles(new HashMap<>(), new ArrayList<>(5_000));
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static final class CandleContainer extends Candle {
        @Getter(AccessLevel.NONE)
        private final double[] indicador;

        public CandleContainer(Candle candle, double[] indicador) {
            super(candle.getTimeUnit(), candle.getOpenTime(), candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(), candle.getVolumen());
            this.indicador = indicador;
        }

        public double[] indicador() {
            return indicador;
        }

    }
}