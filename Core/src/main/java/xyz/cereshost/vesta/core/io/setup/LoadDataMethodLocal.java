package xyz.cereshost.vesta.core.io.setup;

import lombok.Getter;

@Getter
public abstract class LoadDataMethodLocal {

    private final boolean loadTrades;

    public LoadDataMethodLocal(boolean loadTrades) {
        this.loadTrades = loadTrades;
    }
}
