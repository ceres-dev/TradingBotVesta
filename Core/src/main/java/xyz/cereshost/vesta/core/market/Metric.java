package xyz.cereshost.vesta.core.market;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

@Data
public class Metric implements CandleTemporality {

    @Getter(AccessLevel.NONE)
    private final long createTime;
    private final double sumOpenInterest;
    private final double sumOpenInterestValue;
    private final double countTopTradesLongShortRatio;
    private final double countTradesLongShortRatio;

    @Override
    public long getOpenTime() {
        return createTime;
    }

    @Override
    public @NotNull TimeFrameMarket getTimeUnit() {
        return TimeFrameMarket.FIVE_MINUTE;
    }
}
