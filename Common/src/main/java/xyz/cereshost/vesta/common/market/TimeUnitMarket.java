package xyz.cereshost.vesta.common.market;

import lombok.Getter;

import java.util.concurrent.TimeUnit;

@Getter
public enum TimeUnitMarket {
    ONE_MINUTE(TimeUnit.MINUTES.toMillis(1)),
    FIVE_MINUTE(TimeUnit.MINUTES.toMillis(5));

    private final long milliseconds;
    TimeUnitMarket(long ms){
        this.milliseconds = ms;
    }
}
