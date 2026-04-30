package xyz.cereshost.vesta.common.market;

import lombok.Data;

@Data
public abstract class BaseCandle{
    private final double open;
    private final double high;
    private final double low;
    private final double close;

    public double getDiffPercent(){
        return ((close - open)/open)*100;
    }

    public double getDiffPercentAbs(){
        return Math.abs(getDiffPercent());
    }

    public double getDiffPrice(){
        return (close - open);
    }

    public double getDiffPriceAbs(){
        return Math.abs(getDiffPrice());
    }

    public boolean isBullish(){
        return close > open;
    }

    public double getHighBody(){
        return Math.max(open, close);
    }
    public double getLowBody(){
        return Math.min(open, close);
    }

    public double getMidBody(){
        return (open + close)/2;
    }

    public double getMid(){
        return (open + close + low + high)/4;
    }
}
