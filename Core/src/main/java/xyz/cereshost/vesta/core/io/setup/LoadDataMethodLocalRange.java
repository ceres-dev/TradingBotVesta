package xyz.cereshost.vesta.core.io.setup;

import lombok.Getter;

@Getter
public class LoadDataMethodLocalRange extends LoadDataMethodLocal {

    private final int startDay;
    private final int endDay;

    public LoadDataMethodLocalRange(boolean loadTrades, int startDay, int endDay) {
        super(loadTrades);
        this.startDay = startDay;
        this.endDay = endDay;
    }
}
