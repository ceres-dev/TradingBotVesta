package xyz.cereshost.exception;

public class BinanceApiRequestException extends RuntimeException {
    public BinanceApiRequestException(Exception e) {
        super(e);
    }
}
