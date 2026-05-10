package xyz.cereshost.vesta.core.trading.real.api.model;

import xyz.cereshost.vesta.core.market.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TimeInForce;
import xyz.cereshost.vesta.core.trading.TypeOrder;

public record OrderData(
        Long orderID,
        Double price,
        Double triggerPrice,
        Double quantity,
        Boolean isAlgoOrder,
        TimeInForce timeInForce,
        TypeOrder type,
        DireccionOperation direccionOperation
) {
}
