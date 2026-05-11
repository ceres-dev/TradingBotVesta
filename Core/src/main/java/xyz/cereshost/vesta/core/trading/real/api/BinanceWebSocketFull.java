package xyz.cereshost.vesta.core.trading.real.api;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

@Getter
public class BinanceWebSocketFull {

    private final BinanceWebSocketRequest request;
    private final BinanceWebSocketStream stream;

    public BinanceWebSocketFull(@NotNull Boolean isTestNet) {
        this.request = new BinanceWebSocketRequest(isTestNet);
        this.stream = new BinanceWebSocketStream(isTestNet);
    }
}
