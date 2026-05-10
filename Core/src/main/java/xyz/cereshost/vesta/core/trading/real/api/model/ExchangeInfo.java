package xyz.cereshost.vesta.core.trading.real.api.model;

import xyz.cereshost.vesta.core.market.SymbolConfigurable;

import java.util.List;
import java.util.Set;

public record ExchangeInfo(
        List<RateLimit> rateLimits,
        Set<SymbolConfigurable> symbols
) {
}
