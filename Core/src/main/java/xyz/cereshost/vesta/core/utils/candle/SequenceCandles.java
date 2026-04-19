package xyz.cereshost.vesta.core.utils.candle;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Delegate;
import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.core.utils.ConcurrentHashBiDictionary;
import xyz.cereshost.vesta.core.utils.BiDictionary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

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

    private final BiDictionary<String, Byte> dictionary;

    @Delegate
    private final List<CandleContainer> candlesContainer;

    public SequenceCandles(BiDictionary<String, Byte> dictionary, List<CandleContainer> candlesContainer) {
        this.dictionary = dictionary;
        this.candlesContainer = candlesContainer;
    }

    public CandleContainer getLast(int i) {
        return candlesContainer.get(candlesContainer.size() - (1 + i));
    }

    public CandleIndicators getCandleLast() {
        return getCandle(candlesContainer.size() - 1);
    }

    public CandleIndicators getCandleLast(int i){
        return getCandle(candlesContainer.size() - (1 + i));
    }

    public CandleIndicators getCandle(int index) {
        CandleContainer candleContainer = candlesContainer.get(index);
        HashMap<String, Double> map = new HashMap<>();
        for (byte key = 0; key < candleContainer.indicador().length; key++) {
            map.put(dictionary.getLeft(key), candleContainer.indicador()[key]);
        }
        return new CandleIndicators(candleContainer, map, this);
    }

    public SequenceCandles copy(){
        return new SequenceCandles(dictionary, candlesContainer);
    }

    public SequenceCandles subSequence(int fromIndex, int toIndex) {
        return new SequenceCandles(dictionary, candlesContainer.subList(fromIndex, toIndex));
    }

    public List<Candle> toCandlesSimple() {
        return new ArrayList<>(candlesContainer);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends CandleContainer> c) {
        if (c instanceof SequenceCandles sequenceCandles) {
            this.dictionary.addAll(sequenceCandles.dictionary);
            this.dictionary.addAll(sequenceCandles.dictionary);
        }
        return candlesContainer.addAll(c);
    }

    public static SequenceCandles empty(){
        return new SequenceCandles(new ConcurrentHashBiDictionary<>(), new ArrayList<>(5_000));
    }

    /**
     * Está clase está diseñada contener {@link Candle} y {@code double[]} pensado en la optimización de la ram
     * <p>
     * No está pensado para un uso en {@link TradingStrategySimple TradingStrategy} ni en
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
            super(candle);
            this.indicador = indicador;
        }

        public double[] indicador() {
            return indicador;
        }

    }
}