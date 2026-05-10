package xyz.cereshost.vesta.core.trading.real.api.model;

import xyz.cereshost.vesta.core.market.DireccionOperation;

public record PositionData(
        Double entryPrice,
        Double margen,
        Integer leverage,
        DireccionOperation direccionOperation
) {
}
