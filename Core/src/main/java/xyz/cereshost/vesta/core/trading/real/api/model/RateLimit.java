package xyz.cereshost.vesta.core.trading.real.api.model;

import xyz.cereshost.vesta.core.trading.RateLimitType;

import java.util.concurrent.TimeUnit;

public record RateLimit(
        RateLimitType rateLimitType,
        TimeUnit interval,
        Integer intervalNum,
        Integer limit
) {
}
