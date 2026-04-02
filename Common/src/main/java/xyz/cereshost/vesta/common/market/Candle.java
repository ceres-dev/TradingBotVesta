package xyz.cereshost.vesta.common.market;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

@Data
public class Candle extends BaseCandle implements CandleTemporality {

    private final long openTime;
    @Getter(AccessLevel.NONE)
    @NotNull private final TimeUnitMarket timeUnitMarket;
    @NotNull private final Volumen volumen;
    @Nullable private Candle.DepthCandle depth;

    public Candle(@NotNull TimeUnitMarket timeUnitMarket,
                  long openTime,
                  double open,
                  double high,
                  double low,
                  double close,
                  @NotNull Volumen volumen
    ) {
        super(open, high, low, close);
        this.volumen = volumen;
        this.openTime = openTime;
        this.timeUnitMarket = timeUnitMarket;
    }

    public Candle(@NotNull Candle candle){
        this(candle.getTimeUnit(),
                candle.getOpenTime(),
                candle.getOpen(),
                candle.getHigh(),
                candle.getLow(),
                candle.getClose(),
                candle.getVolumen()
        );
        setDepth(candle.getDepth());
    }

    public record DepthCandle(double bidPrice, double askPrice, double bidLiq, double askLiq, double mid, double spread) {}

    @Override
    public int hashCode() {
        int hash = 7;
        return 89 * hash + Objects.hashCode(this.openTime);
    }

    @Override
    public long getOpenTime() {
        return openTime;
    }

    @Override
    public @NotNull TimeUnitMarket getTimeUnit() {
        return timeUnitMarket;
    }
}
