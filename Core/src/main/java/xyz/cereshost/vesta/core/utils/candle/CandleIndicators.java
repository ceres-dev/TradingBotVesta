package xyz.cereshost.vesta.core.utils.candle;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import xyz.cereshost.vesta.common.market.Candle;

import java.util.HashMap;

@Data
@EqualsAndHashCode(callSuper = true)
public final class CandleIndicators extends Candle {
    @Getter(AccessLevel.NONE)
    private final HashMap<String, Double> indicador;
    @Getter
    private final SequenceCandles sequenceCandles;

    public CandleIndicators(Candle candle, HashMap<String, Double> indicador, SequenceCandles sequenceCandles) {
        super(candle.getTimeUnit(), candle.getOpenTime(), candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(), candle.getVolumen());
        this.indicador = indicador;
        this.sequenceCandles = sequenceCandles;
    }

    public double get(String keyIndicador){
        return switch (keyIndicador){
            case "open" -> getOpen();
            case "high" -> getHigh();
            case "low" -> getLow();
            case "close" -> getClose();
            default -> indicador.getOrDefault(keyIndicador, 0.0);
        };
    }

}
