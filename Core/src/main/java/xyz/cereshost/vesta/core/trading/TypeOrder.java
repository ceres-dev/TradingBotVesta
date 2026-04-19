package xyz.cereshost.vesta.core.trading;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;

@RequiredArgsConstructor
public enum TypeOrder {
    LIMIT(false, true),
    MARKET(false, false),
    STOP_MARKET(true, false),
    STOP(true, true),
    TAKE_PROFIT_MARKET(true, false),
    TAKE_PROFIT(true, true),
    TRAILING_STOP_MARKET(false, true),;

    private final boolean requiredStopPrice;
    private final boolean requiredPrice;


    public boolean isValidValue(@Nullable Double stopPrice, @Nullable Double price) {
        if (requiredStopPrice) {
            if (stopPrice == null) return false;
        }
        if (requiredPrice) {
            return price != null;
        }
        return true;
    }

    public boolean isLimit() {
        return this == LIMIT || this == TRAILING_STOP_MARKET || this == TAKE_PROFIT || this == STOP;
    }

    public boolean isMarket() {
        return this == MARKET || this == STOP_MARKET || this == TAKE_PROFIT_MARKET;
    }

    public boolean isAlgo() {
        return this == STOP || this == TAKE_PROFIT || this == STOP_MARKET || this == TAKE_PROFIT_MARKET;
    }

    public boolean isTakeProfit() {
        return this == TAKE_PROFIT_MARKET || this == TAKE_PROFIT;
    }

    public boolean isStopLoss() {
        return this == STOP_MARKET || this == STOP;
    }

    public boolean isAllowClosePosition() {
        return this == STOP_MARKET || this == TAKE_PROFIT_MARKET;
    }
}
