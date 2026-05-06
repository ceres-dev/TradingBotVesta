package xyz.cereshost.vesta.core.market;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.trading.real.api.BinanceApi;

import java.util.Locale;

public interface Symbol {

    @Contract(pure = true)
    @NotNull String name();

    @Contract(pure = true)
    @NotNull Boolean isQuoteUSDT();

    @Contract(pure = true)
    @NotNull Boolean isQuoteUSDC();

    @Contract(pure = true)
    @NotNull Boolean isTradFi();

    @Contract(pure = true)
    default @NotNull Boolean isFuture() {
        return isQuoteUSDC() || isQuoteUSDT();
    }

    @Contract(pure = true)
    default @NotNull Boolean isSpot(){
        return true;
    }

    @NotNull Integer getPricePrecision();

    @NotNull Integer getQuantityPrecision();

    @Contract(pure = true)
    @NotNull String getQuoteAsset();

    @Contract(pure = true)
    @NotNull String getBaseAsset();

    @Contract(pure = true)
    @NotNull MarketStatus getMarketStatus();

    void configure(BinanceApi binanceApi);

    default String formatPrice(@NotNull Double price) {
        String s = "%." + getPricePrecision() + "f";
        return String.format(Locale.US, s, price);
    }

    default String formatQuantity(@NotNull Double quantity) {
        String s = "%." + getQuantityPrecision() + "f";
        return String.format(Locale.US, s, quantity);
    }

    static Symbol valueOf(@NotNull String symbol) {
        return new SymbolConfigurable(symbol);
    }

}
