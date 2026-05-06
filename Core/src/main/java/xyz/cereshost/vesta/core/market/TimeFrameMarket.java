package xyz.cereshost.vesta.core.market;

import lombok.Getter;

import java.util.concurrent.TimeUnit;

@Getter
public enum TimeFrameMarket {
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

    TimeFrameMarket(long ms, String key){
        this.milliseconds = ms;
        this.keyName = key;
    }

    public static TimeFrameMarket parse(long timeOpen, long timeClose){
        int delta = Math.toIntExact(timeClose - timeOpen) + 1;
        for (TimeFrameMarket timeFrameMarket : TimeFrameMarket.values()) {
            if (delta == timeFrameMarket.milliseconds)
                return timeFrameMarket;
        }
        throw new IllegalArgumentException("Invalid time unit: " + delta);
    }

    public static TimeFrameMarket parse(String keyName){
        for (TimeFrameMarket timeFrameMarket : TimeFrameMarket.values()) {
            if (timeFrameMarket.keyName.equals(keyName))
                return timeFrameMarket;
        }
        throw new IllegalArgumentException("Invalid time unit: " + keyName);
    }
}
