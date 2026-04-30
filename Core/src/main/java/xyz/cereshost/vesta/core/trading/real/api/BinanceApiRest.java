package xyz.cereshost.vesta.core.trading.real.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.common.market.Symbol;
import xyz.cereshost.vesta.core.exception.BinanceApiRequestException;
import xyz.cereshost.vesta.core.exception.BinanceApiSignedRequestException;
import xyz.cereshost.vesta.core.exception.BinanceCodeException;
import xyz.cereshost.vesta.core.exception.BinanceCodeWeakException;
import xyz.cereshost.vesta.core.io.IOdata;
import xyz.cereshost.vesta.core.message.MediaNotification;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TimeInForce;
import xyz.cereshost.vesta.core.trading.TypeOrder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.function.Consumer;

/**
 * <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/general-api-information">ApiRest de Binance</a>
 */

@Getter
@Setter
public final class BinanceApiRest implements BinanceApi {

    private final String apiKey;
    private final String secretKey;
    private final String baseUrl;
    // Errores "idempotentes" que no deben detener el loop (ej: cancelar una orden ya cerrada).
    private static final List<Integer> WEAK_ERROS_CODE = List.of(-2021, -2011, -5022);
    private final HttpClient client = HttpClient.newHttpClient();

    @NotNull private MediaNotification mediaNotification = MediaNotification.empty();
    @NotNull private Consumer<Exception> exceptionHandler = e -> {};

    public BinanceApiRest(boolean isTestNet) throws IOException {
        IOdata.ApiKeysBinance apiKeysBinance = IOdata.loadApiKeysBinance();
        this.apiKey = apiKeysBinance.key();
        this.secretKey = apiKeysBinance.secret();
        this.baseUrl = isTestNet ? "https://testnet.binancefuture.com" : "https://fapi.binance.com";
    }

    public BinanceApiRest(String apiKey, String secretKey, boolean isTestNet) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.baseUrl = isTestNet ? "https://testnet.binancefuture.com" : "https://fapi.binance.com";
    }

    @Override
    public Long placeAlgoOrder(@NotNull Symbol symbol,
                               @NotNull DireccionOperation side,
                               @NotNull TypeOrder type,
                               @Nullable TimeInForce timeInForce,
                               @Nullable Double quantity,
                               @NotNull Double stopPrice,
                               @NotNull Boolean reduceOnly,
                               @NotNull Boolean closePosition
    ) {
//        if (!type.isValidValue(stopPrice, stopPrice)) throw new IllegalArgumentException();
        TreeMap<String, String> params = new TreeMap<>();
        params.put("algoType", "CONDITIONAL");          // Obligatorio para órdenes condicionales
        params.put("symbol", symbol.name());
        params.put("side", side.getSide());
        params.put("type", type.name());
        params.put("price", formatPrice(symbol.name(), stopPrice));
        if (type.isAlgo()) throw new IllegalArgumentException("Se requiere TimeInForce para ordenes Limites");
        if (type.isLimit()) {
            if (timeInForce == null) throw new IllegalArgumentException("Se requiere TimeInForce para ordenes Limites");
            params.put("timeInForce", timeInForce.name());
            if (reduceOnly) params.put("reduceOnly", "true");
        }
        if (closePosition) {
            params.put("closePosition", "true");
        }else {
            if (quantity == null) throw new IllegalArgumentException("Se requiere quantity para ordenes Limites");
            params.put("quantity", formatQuantity(symbol.name(), quantity));
        }

        // Si se quiere cerrar la posición completa, se usa closePosition y NO se envía quantity

        // Precio de activación (obligatorio para STOP_MARKET/TAKE_PROFIT_MARKET)
        params.put("triggerPrice", formatPrice(symbol.name(), stopPrice));
        // WorkingType (opcional, pero recomendado)
        params.put("workingType", "MARK_PRICE");

        // Usar el endpoint de órdenes algorítmicas
        JsonNode root = sendSignedRequest("POST", "/fapi/v1/algoOrder", params);
        return root.get("algoId").asLong();
    }


    @Override
    public Long placeOrder(@NotNull Symbol symbol,
                           @NotNull DireccionOperation side,
                           @NotNull TypeOrder type,
                           @Nullable TimeInForce timeInForce,
                           @NotNull Double quantityLeverageCoin,
                           @Nullable Double price,
                           @NotNull Boolean reduceOnly,
                           @NotNull Boolean closePosition
    ) {
        // Para órdenes no condicionales (MARKET, LIMIT), seguir usando el endpoint tradicional
        if (!type.isValidValue(null, price)) throw new IllegalArgumentException();
        TreeMap<String, String> params = new TreeMap<>();
        params.put("symbol", symbol.name());
        params.put("side", side.getSide());
        params.put("type", type.name());
        params.put("quantity", formatQuantity(symbol.name(), quantityLeverageCoin));
        params.put("closePosition", String.valueOf(closePosition));
        if (type.isLimit()) {
            if (timeInForce == null) throw new IllegalArgumentException("Se requiere TimeInForce para ordenes Limites");
            if (price == null) throw new IllegalArgumentException("Se requiere Price para ordenes Limites");

            params.put("timeInForce", timeInForce.name());
            params.put("price", formatPrice(symbol.name(), price));

        }
        if (reduceOnly) params.put("reduceOnly", "true");

        JsonNode root = sendSignedRequest("POST", "/fapi/v1/order", params);
        return root.get("orderId").asLong();
    }
    @Override
    public void cancelOrder(@NotNull Symbol symbol, @NotNull Long orderId, @NotNull Boolean isAlgoOrder) {
        try {
            if (orderId == 0) return;
            TreeMap<String, String> params = new TreeMap<>();
            params.put("symbol", symbol.toString());
            if (isAlgoOrder) {
                params.put("algoId", String.valueOf(orderId));
                sendSignedRequest("DELETE", "/fapi/v1/algoOrder", params);
            } else {
                params.put("orderId", String.valueOf(orderId));
                sendSignedRequest("DELETE", "/fapi/v1/order", params);
            }
        } catch (Exception e) {
            Vesta.warning("No se pudo cancelar orden " + orderId + ": " + e.getMessage());
        }
    }

    @Override
    public List<OrderData> getAllOrders(Symbol symbol) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("symbol", symbol.toString());
        ArrayList<OrderData> operations = new ArrayList<>();
        List<JsonNode> nodes = new ArrayList<>();
        for (JsonNode node :  sendSignedRequest("GET", "/fapi/v1/openOrders", params)) nodes.add(node);
        for (JsonNode node :  sendSignedRequest("GET", "/fapi/v1/openAlgoOrders", params)) nodes.add(node);
        for (JsonNode node : nodes) {
            long orderId = node.get("orderId") == null ?  node.get("algoId").asLong() : node.get("orderId").asLong();
            Double price = node.get("price").asDouble();
            Double triggerPrice = node.get("triggerPrice") == null ? null : node.get("triggerPrice").asDouble();
            Double quantity = node.get("origQty") == null ? node.get("quantity").asDouble() : node.get("origQty").asDouble();
            TimeInForce timeInForce = TimeInForce.valueOf(node.get("timeInForce").asText());
            TypeOrder typeOrder = TypeOrder.valueOf(node.get("type") == null ? node.get("orderType").asText() : node.get("type").asText());
            DireccionOperation side = DireccionOperation.parse(node.get("side").asText());
            Boolean isAlgoOrder = node.get("algoType") != null;
            operations.add(new OrderData(orderId,
                    price,
                    triggerPrice,
                    quantity,
                    isAlgoOrder,
                    timeInForce,
                    typeOrder,
                    side
            ));
        }

        return operations;
    }

    @Override
    public PositionData getPosition(Symbol symbol) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("symbol", symbol.toString());
        JsonNode operationsV2 = sendSignedRequest("GET", "/fapi/v2/positionRisk", params);
        JsonNode operationsV3 = sendSignedRequest("GET", "/fapi/v3/positionRisk", params);
        JsonNode operationV2 = Objects.requireNonNull(operationsV2).get(0);
        JsonNode operationV3 = Objects.requireNonNull(operationsV3).get(0);
        PositionData positionData;
        DireccionOperation direccionOperation = DireccionOperation.parse(operationV2.get("positionAmt").asDouble());
        if (direccionOperation != DireccionOperation.NEUTRAL){
            positionData = new PositionData(
                    Double.valueOf(operationV2.get("entryPrice").asText()),
                    Double.valueOf(operationV3.get("positionInitialMargin").asText()),
                    Integer.valueOf(operationV2.get("leverage").asText()),
                    direccionOperation
            );
        }else {
            positionData = null;
        }
        return positionData;
    }

    @Override
    public void closeAll(Symbol symbol) {
        try {
            // 1. Obtener posiciones actuales
            TreeMap<String, String> params = new TreeMap<>();
            params.put("symbol", symbol.toString());
            JsonNode positions = sendSignedRequest("GET", "/fapi/v2/positionRisk", params);

            for (JsonNode position : Objects.requireNonNull(positions)) {
                String posSymbol = position.get("symbol").asText();
                if (symbol.toString().equals(posSymbol)) {
                    double positionAmt = position.get("positionAmt").asDouble();
                    if (Math.abs(positionAmt) > 0) {
                        Vesta.warning("Cerrando posición existente: " + positionAmt + " " + symbol);

                        String side = positionAmt > 0 ? "SELL" : "BUY";
                        TreeMap<String, String> closeParams = new TreeMap<>();
                        closeParams.put("symbol", symbol.toString());
                        closeParams.put("side", side);
                        closeParams.put("type", "MARKET");
                        closeParams.put("quantity", String.valueOf(Math.abs(positionAmt)));
                        closeParams.put("reduceOnly", "true");

                        sendSignedRequest("POST", "/fapi/v1/order", closeParams);
                    }
                }
            }

            // 2. Cancelar todas las órdenes abiertas
            TreeMap<String, String> cancelParams = new TreeMap<>();
            cancelParams.put("symbol", symbol.toString());
            sendSignedRequest("DELETE", "/fapi/v1/allOpenOrders", cancelParams);
        } catch (Exception e) {
            Vesta.error("Error cerrando posiciones existentes: " + e.getMessage());
        }
    }

    @Override
    public void changeLeverage(Symbol symbol, int leverage) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("symbol", symbol.toString());
        params.put("leverage", String.valueOf(leverage));
        sendSignedRequest("POST", "/fapi/v1/leverage", params);
    }

    @Override
    public double getTickerPrice(Symbol symbol) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("symbol", symbol.toString());
        JsonNode root = sendRequest("GET", "/fapi/v1/ticker/price", params); // Public endpoint
        return root.get("price").asDouble();
    }

    @Override
    public double getBalance(Symbol symbol) {
        // 1. Consultar cuenta (v3 devuelve el objeto con el campo 'assets')
        JsonNode root = sendSignedRequest("GET", "/fapi/v3/account", new TreeMap<>());
        // 2. Determinar qué moneda base estamos usando (USDT o USDC)
        // Si el símbolo es "BNBUSDC", buscamos "USDC". Si es "BNBUSDT", buscamos "USDT".
        String quoteAsset = symbol.getQuoteAsset();

        // 3. Acceder al array de 'assets'
        if (Objects.requireNonNull(root).has("assets") && root.get("assets").isArray()) {
            JsonNode assets = root.get("assets");

            for (JsonNode assetNode : assets) {
                String assetName = assetNode.get("asset").asText();

                if (quoteAsset.equalsIgnoreCase(assetName)) {
                    double balance = assetNode.get("availableBalance").asDouble();
                    Vesta.info("💰 Balance detectado para " + quoteAsset + ": " + balance);
                    return balance;
                }
            }
        }

        // 4. Backup: Si por alguna razón no se encuentra en el array,
        // intentar tomar el availableBalance general del root
        if (root.has("availableBalance")) {
            return root.get("availableBalance").asDouble();
        }
        return 0.0;
    }

    @Override
    public @NotNull JsonNode sendSignedRequest(String method, String endpoint, TreeMap<String, String> params) throws BinanceApiSignedRequestException {
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        params.put("recvWindow", "20000");
        try {
            String queryString = buildQueryString(params);
            String signature = hmacSha256(queryString, secretKey);
            String finalUrl = baseUrl + endpoint + "?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .header("X-MBX-APIKEY", apiKey)
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            ObjectMapper mapper = new ObjectMapper();
            // El EndPoint /fapi/v1/stock/contract solo retornar un "SUCCESS" en texto plano (no json)
            if (response.body().equals("SUCCESS")) {
                return mapper.readTree("{}");
            }
            JsonNode root = mapper.readTree(response.body());
            String symbolName = params.get("symbol");
            checkRepose(symbolName == null ? null : Symbol.valueOf(symbolName), root, method, endpoint);
            return root;
        }catch (Exception e) {
            exceptionHandler.accept(e);
            if (e instanceof BinanceCodeException binanceCodeException) {
                throw new BinanceApiSignedRequestException(e, binanceCodeException.getCode());
            }else {
                throw new BinanceApiSignedRequestException(e, -1);
            }
        }
    }

    @Override
    public @NotNull JsonNode sendRequest(String method, String endpoint, TreeMap<String, String> params) throws BinanceApiRequestException {
        try {
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            params.put("recvWindow", "20000");
            String queryString = buildQueryString(params);
            String finalUrl = baseUrl + endpoint + "?" + queryString;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
            checkRepose(Symbol.valueOf(params.get("symbol")), root, method, endpoint);
            return root;
        } catch (Exception e) {
            exceptionHandler.accept(e);
            throw new BinanceApiRequestException(e);
        }
    }

    @Override
    public void checkRepose(Symbol symbol, @NotNull JsonNode node, @NotNull String method, @NotNull String endpoint) throws BinanceCodeException {
        if (node.has("code") && node.get("code").asInt() != 0) {
            int code = node.get("code").asInt();
            if (code == 200) {
                Vesta.info("✅ operacion Ok (%s:%s)", method, endpoint);
            }else {
                if (WEAK_ERROS_CODE.contains(code)) {
                    if (code == -2021 && symbol != null) closeAll(symbol);
                    BinanceCodeWeakException e = new BinanceCodeWeakException(node, method, endpoint);
                    mediaNotification.waring("Error al hacer una petición: **%s**. Revisa la consola para más información", e.getMessage());
                    Vesta.sendWaringException("Error al hacer una petición" , e);
                    throw e;
                }else {
                    if (symbol != null) closeAll(symbol);
                    BinanceCodeException exception = new BinanceCodeException(node, method, endpoint);
                    mediaNotification.error("Error al hacer una petición: **%s**. Revisa la consola para más información", exception.getMessage());
                    exceptionHandler.accept(exception);
                    throw exception;
                }
            }
        }
    }
}
