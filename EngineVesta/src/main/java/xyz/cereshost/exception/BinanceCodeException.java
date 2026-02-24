package xyz.cereshost.exception;

import com.fasterxml.jackson.databind.JsonNode;

public class BinanceCodeException extends RuntimeException {
    public BinanceCodeException(JsonNode rootNode) {
        super("Error Binance (" + rootNode.get("code") + "): " + rootNode.get("msg").asText());
    }
}
