package xyz.cereshost.vesta.core.trading;

public enum TypeOrder {
    LIMIT,
    MARKET,
    STOP_MARKET,
    STOP_LIMIT,
    TAKE_PROFIT_MARKET,
    TAKE_PROFIT_LIMIT,
    LIMIT_MAKER;

    public boolean isLimit() {
        return this == LIMIT || this == LIMIT_MAKER || this == TAKE_PROFIT_LIMIT || this == STOP_LIMIT;
    }
}
