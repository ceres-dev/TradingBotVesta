package xyz.cereshost.vesta.core.trading;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Contract;

@Getter
@RequiredArgsConstructor
public enum Endpoints {
    API("https://api.binance.com", false, false),
    API1("https://api1.binance.com", false, false),
    API2("https://api2.binance.com", false, false),
    API3("https://api3.binance.com", false, false),
    API4("https://api4.binance.com", false, false),
    API_GCP("https://api-gcp.binance.com", false, false),
    TESTNET("https://testnet.binance.vision", true, false),
    API_TESTNET("https://api1.testnet.binance.vision", true, false),
    FAPI("https://fapi.binance.com", false, true),
    DEMO_FAPI("https://demo-fapi.binance.com", true, true),;

    private final String endpoint;
    private final boolean isTest;
    private final boolean isFutures;

    @Contract(pure = true)
    public boolean isSpot() {
        return !isFutures;
    }
    @Contract(pure = true)
    public boolean isReal() {
        return !isTest;
    }
}
