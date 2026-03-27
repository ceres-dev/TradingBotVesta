package xyz.cereshost.vesta.common.market;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

@Data
public class Candle extends BaseCandle implements CandleTemporality {

    private final long openTime;
    @Getter(AccessLevel.NONE)
    @NotNull private final TimeUnitMarket timeUnitMarket;
    @NotNull private final Volumen volumen;

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
