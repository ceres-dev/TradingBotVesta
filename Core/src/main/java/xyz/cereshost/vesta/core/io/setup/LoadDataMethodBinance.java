package xyz.cereshost.vesta.core.io.setup;

import lombok.Getter;

@Getter
public class LoadDataMethodBinance {

    private final int limitCandle;
    private final int limitTrade;
    private final int limitDepth;

    public LoadDataMethodBinance(int limitCandle, int limitTrade, int limitDepth) {
        this.limitCandle = limitCandle;
        this.limitTrade = limitTrade;
        this.limitDepth = limitDepth;
    }
}
