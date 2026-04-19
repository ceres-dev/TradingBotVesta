package xyz.cereshost.vesta.core.exception;

import xyz.cereshost.vesta.core.trading.TradingManager;

public class OperationFilled extends RuntimeException {
    public OperationFilled(String message) {
        super(message);
    }

    public OperationFilled(TradingManager.ClosePosition closeOperation) {
        super(String.format("La operacion %s ya cerrada", closeOperation.getUuid()));
    }
}
