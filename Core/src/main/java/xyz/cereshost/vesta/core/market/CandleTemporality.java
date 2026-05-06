package xyz.cereshost.vesta.core.market;

import org.jetbrains.annotations.NotNull;

public interface CandleTemporality {

    long getOpenTime();

    @NotNull TimeFrameMarket getTimeUnit();

    default long getCloseTime() {
        return getOpenTime() + getTimeUnit().getMilliseconds();
    }
}
