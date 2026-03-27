package xyz.cereshost.vesta.common.market;

import lombok.Getter;

import java.io.Closeable;
import java.util.concurrent.TimeUnit;

@Getter
public enum TimeUnitMarket {
    ONE_MINUTE(TimeUnit.MINUTES.toMillis(1)),
    FIVE_MINUTE(TimeUnit.MINUTES.toMillis(5));

    private final long milliseconds;
    TimeUnitMarket(long ms){
        this.milliseconds = ms;
    }

    public static TimeUnitMarket parse(long timeOpen, long timeClose){
        int diff = Math.toIntExact(timeClose - timeOpen);
        return switch (diff) {
            case 59_999 -> ONE_MINUTE;
            case 299_999 -> FIVE_MINUTE;
            default -> throw new IllegalStateException("Unexpected value: " + diff);
        };
    }
}
