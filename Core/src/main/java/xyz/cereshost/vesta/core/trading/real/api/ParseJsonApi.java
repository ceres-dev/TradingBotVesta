package xyz.cereshost.vesta.core.trading.real.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.market.MarketStatus;
import xyz.cereshost.vesta.core.market.SymbolConfigurable;
import xyz.cereshost.vesta.core.trading.RateLimitType;
import xyz.cereshost.vesta.core.trading.real.api.model.BookTicker;
import xyz.cereshost.vesta.core.trading.real.api.model.ExchangeInfo;
import xyz.cereshost.vesta.core.trading.real.api.model.RateLimit;

import java.util.*;
import java.util.concurrent.TimeUnit;

public abstract class ParseJsonApi {

    protected @NotNull ExchangeInfo parseExchangeInfo(@NotNull JsonNode node, @NotNull Boolean isFuture) {
        Set<SymbolConfigurable> symbols = new HashSet<>();
        for (JsonNode info : node.get("symbols")) {
            String symbol = info.get("symbol").asText();
            symbols.add(
                    isFuture ?
                            new SymbolConfigurable(
                                    symbol,
                                    info.asText().startsWith("TRADIFI_"),
                                    true,
                                    false,
                                    info.get("pricePrecision").asInt(),
                                    info.get("quotePrecision").asInt(),
                                    MarketStatus.valueOf(info.get("status").asText()),
                                    info.get("baseAsset").asText(),
                                    info.get("quoteAsset").asText()
                                    //info.get("isSpotTradingAllowed").booleanValue()
                            ) :
                            new SymbolConfigurable(
                                    symbol,
                                    false,
                                    false,
                                    true,
                                    Objects.requireNonNullElse(info.get("quoteAssetPrecision"), info.get("pricePrecision")).asInt(),
                                    info.get("quotePrecision").asInt(),
                                    MarketStatus.valueOf(info.get("status").asText()),
                                    info.get("baseAsset").asText(),
                                    info.get("quoteAsset").asText()
                                    //info.get("isSpotTradingAllowed").booleanValue()
                            )
            );
        }

        List<RateLimit> limits = new ArrayList<>();
        for (JsonNode info : node.get("rateLimits")) {
            limits.add(
                    new RateLimit(
                            RateLimitType.valueOf(info.get("rateLimitType").asText()),
                            // Se agrega una "S" por que la unidad que entrega binance es en sigular, pero el Emun trabaja en prural
                            TimeUnit.valueOf(info.get("interval").asText() + "S"),
                            info.get("intervalNum").asInt(),
                            info.get("limit").asInt()
                    )
            );
        }
        return new ExchangeInfo(limits, symbols);
    }

    public Map<String, BookTicker> parseBookTickers(JsonNode node) {
        Map<String, BookTicker> bookTickers = new HashMap<>();
        if (node.isArray()) {
            for (JsonNode ticker : node) {
                String s = ticker.get("symbol").asText();
                bookTickers.put(s, new BookTicker(s,
                        ticker.get("bidPrice").asDouble(),
                        ticker.get("bidQty").asDouble(),
                        ticker.get("askPrice").asDouble(),
                        ticker.get("askQty").asDouble())
                );
            }
        }else {
            String s = node.get("symbol").asText();
            bookTickers.put(s, new BookTicker(s,
                    node.get("bidPrice").asDouble(),
                    node.get("bidQty").asDouble(),
                    node.get("askPrice").asDouble(),
                    node.get("askQty").asDouble())
            );
        }
        return bookTickers;
    }


}
