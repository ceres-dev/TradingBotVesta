package xyz.cereshost.vesta.core.trading.real.api.model;

public record Ticker24H(
        String symbol,
        Double priceChange,
        Double priceChangePercent,
        Double quoteVolumen,
        Double baseVolumen
) {
}
