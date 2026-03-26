package xyz.cereshost.vesta.core.exception;

public class BinanceApiSignedRequestException extends BinanceApiRequestException {
    public BinanceApiSignedRequestException(Exception e, int code) {
        super(e);
    }
}
