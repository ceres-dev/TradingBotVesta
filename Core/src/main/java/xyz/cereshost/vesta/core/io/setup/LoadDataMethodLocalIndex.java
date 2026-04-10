package xyz.cereshost.vesta.core.io.setup;

import lombok.Getter;

@Getter
public class LoadDataMethodLocalIndex extends LoadDataMethodLocal {

    private final int indexDay;

    public LoadDataMethodLocalIndex(boolean loadTrades, int indexDay) {
        super(loadTrades);
        this.indexDay = indexDay;
    }
}
