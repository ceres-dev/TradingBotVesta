package xyz.cereshost.vesta.core.utils.candle;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Delegate;
import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.common.market.Candle;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Una lista de {@link CandleContainer} con indicadores técnicos ya computados.
 * <p>
 * Ya que los indicadores técnicos se guardan en un {@code double[]} para no acceder de un índice crea un diccionario que
 * guarda un {@link String} como key y un {@link Byte} como valor y hay otro diccionario inverso donde el {@link String}
 * es el valor y él {@link Byte}, esto permite que se pueda acceder a un indicador técnico a través del {@link String} que
 * se asignó previamente en {@link CandlesBuilder}.
 * </p>
 * La implementación de {@link List} depende de {@link CandlesBuilder} al crear la instancia.
 *
 * @see CandlesBuilder
 * @see CandleIndicators
 * @see CandleContainer
 *
 * @author Ceres
 */
public class SequenceCandles implements List<SequenceCandles.CandleContainer> {

    private final ConcurrentHashMap<Byte, String> dictionaryByte;
    private final ConcurrentHashMap<String, Byte> dictionaryString;
    @Delegate
    private final List<CandleContainer> candlesContainer;

    public SequenceCandles(ConcurrentHashMap<String, Byte> dictionaryString, List<CandleContainer> candlesContainer) {
        this.dictionaryString = dictionaryString;
        ConcurrentHashMap<Byte, String> dictionaryByte = new ConcurrentHashMap<>();
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
        return new SequenceCandles(new ConcurrentHashMap<>(), new ArrayList<>(5_000));
    }

    /**
     * Está clase está diseñada contener {@link Candle} y {@code double[]} pensado en la optimización de la ram
     * <p>
     * No está pensado para un uso en {@link xyz.cereshost.vesta.core.strategy.TradingStrategy TradingStrategy} ni en
     * gráficas, solo para guardar el OHLCV y los indicadores técnicos, en caso de utilización de indicadores usa {@link CandleIndicators}.
     * </p>
     *
     * @see SequenceCandles
     * @see CandleIndicators
     */

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