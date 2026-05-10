package xyz.cereshost.vesta.core.trading.real.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.core.exception.BinanceApiRequestException;
import xyz.cereshost.vesta.core.io.IOdata;
import xyz.cereshost.vesta.core.market.DireccionOperation;
import xyz.cereshost.vesta.core.market.Symbol;
import xyz.cereshost.vesta.core.market.SymbolConfigurable;
import xyz.cereshost.vesta.core.message.MediaNotification;
import xyz.cereshost.vesta.core.trading.Endpoints;
import xyz.cereshost.vesta.core.trading.TimeInForce;
import xyz.cereshost.vesta.core.trading.TypeOrder;
import xyz.cereshost.vesta.core.trading.real.api.model.BookTicker;
import xyz.cereshost.vesta.core.trading.real.api.model.ExchangeInfo;
import xyz.cereshost.vesta.core.trading.real.api.model.OrderData;
import xyz.cereshost.vesta.core.trading.real.api.model.PositionData;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class BinanceWebSocket extends ParseJsonApi implements BinanceApi {

    private final String apiKey;
    private final String secretKey;
//    private final Endpoints futureBaseUrl;
    private final Endpoints spotBaseUrl;

    @NotNull @Getter @Setter private MediaNotification mediaNotification = MediaNotification.empty();
    @NotNull @Setter private Consumer<Exception> exceptionHandler = e -> {};
    @NotNull private final WebSocket webSocket;
    @NotNull private final ObjectMapper objectMapper = new ObjectMapper();

    @NotNull private final ConcurrentMap<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    @NotNull private final Object incomingLock = new Object();
    @NotNull private final StringBuilder incomingMessage = new StringBuilder();
    private static final long REQUEST_TIMEOUT_SECONDS = 15L;

    @Nullable private ExchangeInfo exchangeInfoSpot = null;

    public BinanceWebSocket(boolean isTestNet) throws IOException {
        IOdata.ApiKeysBinance apiKeysBinance = IOdata.loadApiKeysBinance();
        this.apiKey = apiKeysBinance.key();
        this.secretKey = apiKeysBinance.secret();
//        this.futureBaseUrl = isTestNet ? Endpoints.W : Endpoints.FAPI;
        this.spotBaseUrl = isTestNet ? Endpoints.API_WSS_TEST : Endpoints.API_WSS;

        webSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(
                        URI.create(spotBaseUrl.getEndpoint()),
                        new WebSocket.Listener() {

                            @Override
                            public void onOpen(WebSocket webSocket) {
                                webSocket.request(1);
                                WebSocket.Listener.super.onOpen(webSocket);
                            }

                            @Override
                            public CompletionStage<?> onText(
                                    WebSocket webSocket,
                                    CharSequence data,
                                    boolean last
                            ) {
                                String contentToParse = null;
                                synchronized (incomingLock) {
                                    incomingMessage.append(data);
                                    if (last) {
                                        contentToParse = incomingMessage.toString();
                                        incomingMessage.setLength(0);
                                    }
                                }
                                if (contentToParse != null) {
                                    try {
                                        JsonNode response = objectMapper.readTree(contentToParse);
                                        JsonNode idNode = response.get("id");
                                        if (idNode != null) {
                                            CompletableFuture<JsonNode> pending = pendingRequests.remove(idNode.asText());
                                            if (pending != null) {
                                                pending.complete(response);
                                            }
                                        }
                                    } catch (Exception e) {
                                        failPendingRequests(e);
                                        exceptionHandler.accept(e);
                                    }
                                }
                                webSocket.request(1);
                                return WebSocket.Listener.super.onText(webSocket, data, last);
                            }

                            @Override
                            public void onError(WebSocket webSocket, Throwable error) {
                                Exception exception = error instanceof Exception e ? e : new RuntimeException(error);
                                failPendingRequests(exception);
                                exceptionHandler.accept(exception);
                                WebSocket.Listener.super.onError(webSocket, error);
                            }

                            @Override
                            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                                failPendingRequests(new IllegalStateException("WebSocket cerrado: " + statusCode + " " + reason));
                                return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                            }
                        }
                ).join();
    }

    @Override
    public Long placeAlgoOrder(@NotNull Symbol symbol, @NotNull DireccionOperation side, @NotNull TypeOrder type, @Nullable TimeInForce timeInForce, @Nullable Double quantityLeverageCoin, @NotNull Double trigger, @NotNull Boolean reduceOnly, @NotNull Boolean closePosition) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Long placeOrder(@NotNull Symbol symbol, @NotNull DireccionOperation side, @NotNull TypeOrder type, @Nullable TimeInForce timeInForce, @NotNull Double quantityLeverageCoin, @Nullable Double trigger, @NotNull Boolean reduceOnly, @NotNull Boolean closePosition) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void cancelOrder(@NotNull Symbol symbol, @NotNull Long orderId, @NotNull Boolean isAlgoOrder) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void closeAll(@NotNull Symbol symbol) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void changeLeverage(@NotNull Symbol symbol, @NotNull Integer leverage) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void invalidedCache() {
        exchangeInfoSpot = null;
    }

    @Override
    public @NotNull List<OrderData> getAllOrdersFuture(@NotNull Symbol symbol) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public @Nullable PositionData getPosition(@NotNull Symbol symbol) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public @NotNull Double getTickerPrice(@NotNull Symbol symbol) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public @NotNull Map<String, BookTicker> getBookTickers(@Nullable Symbol symbol, @Nullable Boolean isFuture) {
        boolean future = resolveFuture(symbol, isFuture);
        if (future) {
            throw new UnsupportedOperationException("BinanceWebSocket solo soporta Spot para getBookTickers.");
        }

        Map<String, Object> params = new HashMap<>();
        if (symbol != null) {
            params.put("symbol", symbol.name());
        }
        JsonNode result = sendPublicRequest("ticker.book", params);
        return parseBookTickers(result);
    }

    @Override
    public @NotNull ExchangeInfo getExchangeInfo(@NotNull Boolean isFuture) {
        if (isFuture) {
            throw new UnsupportedOperationException("BinanceWebSocket solo soporta Spot para getExchangeInfo.");
        }

        if (exchangeInfoSpot != null) {
            return exchangeInfoSpot;
        }

        JsonNode result = sendPublicRequest("exchangeInfo", Map.of());
        ExchangeInfo exchangeInfo = parseExchangeInfo(result, false);
        exchangeInfoSpot = exchangeInfo;
        return exchangeInfo;
    }

    @Override
    public @NotNull SymbolConfigurable getSymbolConfigured(@NotNull String symbol, @NotNull Boolean shouldFuture) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public @NotNull Double getBalance(@NotNull Symbol symbol) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void signContract() {

    }

    private boolean resolveFuture(@Nullable Symbol symbol, @Nullable Boolean isFuture) {
        Boolean future = null;
        if (isFuture != null) future = isFuture;
        if (symbol != null) future = symbol.isFuture();
        if (future == null) {
            throw new IllegalArgumentException("Symbol future is null");
        }
        return future;
    }

    private void failPendingRequests(@NotNull Exception exception) {
        pendingRequests.forEach((id, future) -> future.completeExceptionally(exception));
        pendingRequests.clear();
    }

    private @NotNull synchronized JsonNode sendPublicRequest(@NotNull String method, @NotNull Map<String, Object> params) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> responseFuture = new CompletableFuture<>();
        pendingRequests.put(id, responseFuture);

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", id);
            payload.put("method", method);
            if (!params.isEmpty()) {
                payload.set("params", objectMapper.valueToTree(params));
            }

            webSocket.sendText(payload.toString(), true).join();
            JsonNode response = responseFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            validateWsResponse(response, method);
            return response.get("result");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exceptionHandler.accept(e);
            throw new BinanceApiRequestException(e);
        } catch (Exception e) {
            exceptionHandler.accept(e);
            throw new BinanceApiRequestException(e);
        } finally {
            pendingRequests.remove(id);
        }
    }

    private void validateWsResponse(@NotNull JsonNode response, @NotNull String method) {
        int status = response.has("status") ? response.get("status").asInt() : 0;
        if (status == 200 && response.has("result")) {
            return;
        }

        String errorMessage = response.has("error")
                ? response.get("error").toString()
                : response.toString();
        IllegalStateException exception = new IllegalStateException("Error Binance WS (" + method + "): " + errorMessage);
        exceptionHandler.accept(exception);
        throw exception;
    }

}
