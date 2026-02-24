package xyz.cereshost.exception;

public class BinanceApiSignedRequestException extends BinanceApiRequestException {
    public BinanceApiSignedRequestException(Exception e) {
        super(e);
    }
}
