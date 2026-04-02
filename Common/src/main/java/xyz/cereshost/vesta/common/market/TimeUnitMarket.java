package xyz.cereshost.vesta.common.market;

import lombok.Getter;

import java.io.Closeable;
import java.util.concurrent.TimeUnit;

@Getter
public enum TimeUnitMarket {
    ONE_MINUTE(TimeUnit.MINUTES.toMillis(1), "1m"),
    THREE_MINUTES(TimeUnit.MINUTES.toMillis(3), "3m"),
    FIVE_MINUTE(TimeUnit.MINUTES.toMillis(5), "5m"),
    FIFTEEN_MINUTES(TimeUnit.MINUTES.toMillis(15), "15m"),
    THIRTY_MINUTES(TimeUnit.MINUTES.toMillis(30), "30m"),
    ONE_HOUR(TimeUnit.HOURS.toMillis(1), "1h"),
    TWO_HOURS(TimeUnit.HOURS.toMillis(2), "2h"),
    FOUR_HOURS(TimeUnit.HOURS.toMillis(4), "4h"),
    SIX_HOURS(TimeUnit.HOURS.toMillis(6), "6h"),
    TWELVE_HOURS(TimeUnit.HOURS.toMillis(12), "12h"),
    ONE_DAY(TimeUnit.DAYS.toMillis(1), "1d"),
    THREE_DAYS(TimeUnit.DAYS.toMillis(3), "3d"),
    ONE_WEEK(TimeUnit.DAYS.toMillis(7), "1w");

    private final long milliseconds;
    private final String keyName;

    TimeUnitMarket(long ms, String key){
        this.milliseconds = ms;
        this.keyName = key;
    }

    public static TimeUnitMarket parse(long timeOpen, long timeClose){
        int diff = Math.toIntExact(timeClose - timeOpen) + 1;
        for (TimeUnitMarket timeUnitMarket : TimeUnitMarket.values()) {
            if (diff == timeUnitMarket.milliseconds)
                return timeUnitMarket;
        }
        throw new IllegalArgumentException("Invalid time unit");
    }
}
