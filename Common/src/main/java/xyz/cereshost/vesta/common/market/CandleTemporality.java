package xyz.cereshost.vesta.common.market;

import org.jetbrains.annotations.NotNull;

public interface CandleTemporality {

    long getOpenTime();

    @NotNull TimeUnitMarket getTimeUnit();

    default long getCloseTime() {
        return getOpenTime() + getTimeUnit().getMilliseconds();
    }
}
