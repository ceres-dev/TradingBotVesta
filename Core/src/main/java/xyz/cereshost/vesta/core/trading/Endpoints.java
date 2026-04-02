package xyz.cereshost.vesta.core.trading;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum Endpoints{
    API("https://api.binance.com"),
    API_GCP("https://api-gcp.binance.com"),
    API1("https://api1.binance.com"),
    API2("https://api2.binance.com"),
    API3("https://api3.binance.com"),
    API4("https://api4.binance.com"),
    FAPI("https://fapi.binance.com");

    private final String endpoint;
}
