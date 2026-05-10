package xyz.cereshost.vesta.core.trading;

public enum RateLimitType {
    REQUEST_WEIGHT,
    ORDERS,
    // Solo Spot
    RAW_REQUESTS,
    // Solo webSocket
    CONNECTIONS
}
