package xyz.cereshost.vesta.core.trading.real.api.model;

public record BookTicker(
        String symbol,
        Double bidPrice,
        Double bidQty,
        Double askPrice,
        Double askQty
) {
}
