package xyz.cereshost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import xyz.cereshost.common.Vesta;
import xyz.cereshost.exception.BinanceApiRequestException;
import xyz.cereshost.exception.BinanceCodeException;
import xyz.cereshost.exception.BinanceApiSignedRequestException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;

@Getter
@Setter
public final class BinanceApi {

    private final String apiKey;
    private final String secretKey;
    private final String baseUrl;
    private final List<Integer> weakErrosCode = List.of(-2021);
    private final HttpClient client = HttpClient.newHttpClient();

    @NotNull
    private Consumer<Exception> exceptionHandler = e -> {};

    public BinanceApi(String apiKey, String secretKey, boolean isTestNet) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.baseUrl = isTestNet ? "https://testnet.binancefuture.com" : "https://fapi.binance.com";
    }

    public long placeOrder(String symbol,
                           String side,
                           String type,
                           String quantity,
                           Double stopPrice,
                           boolean reduceOnly,
                           boolean closePosition
    ) {
        // Para órdenes no condicionales (MARKET, LIMIT), seguir usando el endpoint tradicional
        TreeMap<String, String> params = new TreeMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", type);
        if (closePosition) {
            params.put("closePosition", "true");
        } else if (quantity != null) {
            params.put("quantity", quantity);
            if (reduceOnly) params.put("reduceOnly", "true");
        }
        if (stopPrice != null) params.put("stopPrice", formatPrice(symbol, stopPrice));

        Vesta.info("Enviando orden REST: " + type + " " + side +
                " Qty:" + (quantity != null ? quantity : "null") +
                " Stop:" + stopPrice +
                " reduceOnly:" + reduceOnly +
                " closePosition:" + closePosition);

        JsonNode root = sendSignedRequest("POST", "/fapi/v1/order", params);
        if (root.has("orderId")) {
            return root.get("orderId").asLong();
        } else {
            throw new RuntimeException("Respuesta desconocida: " + root);
        }
    }

    public long placeAlgoOrder(String symbol,
                               String side,
                               String type,
                               String quantity,
                               Double stopPrice,
                                boolean reduceOnly,
                               boolean closePosition
    ) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("algoType", "CONDITIONAL");          // Obligatorio para órdenes condicionales
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", type);                       // STOP_MARKET, TAKE_PROFIT_MARKET
        params.put("timeInForce", "GTC");               // Recomendado para condicionales

        // Si se quiere cerrar la posición completa, se usa closePosition y NO se envía quantity
        if (closePosition) {
            params.put("closePosition", "true");
        } else if (quantity != null) {
            params.put("quantity", quantity);
            if (reduceOnly) {
                params.put("reduceOnly", "true");
            }
        }

        // Precio de activación (obligatorio para STOP_MARKET/TAKE_PROFIT_MARKET)
        if (stopPrice != null) {
            params.put("triggerPrice", formatPrice(symbol, stopPrice));
        }

        // WorkingType (opcional, pero recomendado)
        params.put("workingType", "MARK_PRICE");

        // Usar el endpoint de órdenes algorítmicas
        JsonNode root = sendSignedRequest("POST", "/fapi/v1/algoOrder", params);


        if (root.has("algoId")) {
            return root.get("algoId").asLong();   // ¡Devuelve el algoId, no orderId!
        } else {
            throw new RuntimeException("Respuesta desconocida: " + root);
        }
    }

    public void cancelOrder(String symbol, long orderId, boolean isAlgoOrder) {
        try {
            if (orderId == 0) return;
            TreeMap<String, String> params = new TreeMap<>();
            params.put("symbol", symbol);
            if (isAlgoOrder) {
                params.put("algoId", String.valueOf(orderId));
                sendSignedRequest("DELETE", "/fapi/v1/algoOrder", params);
            } else {
                params.put("orderId", String.valueOf(orderId));
                sendSignedRequest("DELETE", "/fapi/v1/order", params);
            }
        } catch (Exception e) {
            Vesta.warning("No se pudo cancelar orden " + orderId + ": " + e.getMessage());  // CORREGIDO
        }
    }

    public boolean checkOrderFilled(String symbol, long orderId, boolean isAlgoOrder) {
        if (orderId == 0) return false;

        if (isAlgoOrder) {
            // Para órdenes algorítmicas
            TreeMap<String, String> params = new TreeMap<>();
            params.put("symbol", symbol);
            params.put("algoId", String.valueOf(orderId));

            JsonNode root = sendSignedRequest("GET", "/fapi/v1/algoOrder", params);
            // El estado puede ser "FILLED" o "FINISHED" para órdenes ejecutadas
            if (root.has("algoStatus")) {
                String status = root.get("algoStatus").asText();
                return "FILLED".equals(status) || "FINISHED".equals(status);
            }
            return false;
        } else {
            // Para órdenes normales
            TreeMap<String, String> params = new TreeMap<>();
            params.put("symbol", symbol);
            params.put("orderId", String.valueOf(orderId));

            JsonNode root = sendSignedRequest("GET", "/fapi/v1/order", params);
            if (root.has("status")) {
                return "FILLED".equals(root.get("status").asText());
            }
            return false;
        }
    }

    public void closeAll(String symbol) {
        try {
            // 1. Obtener posiciones actuales
            TreeMap<String, String> params = new TreeMap<>();
            params.put("symbol", symbol);
            JsonNode positions = sendSignedRequest("GET", "/fapi/v2/positionRisk", params);

            for (JsonNode position : positions) {
                String posSymbol = position.get("symbol").asText();
                if (symbol.equals(posSymbol)) {
                    double positionAmt = position.get("positionAmt").asDouble();
                    if (Math.abs(positionAmt) > 0) {
                        Vesta.warning("Cerrando posición existente: " + positionAmt + " " + symbol);

                        String side = positionAmt > 0 ? "SELL" : "BUY";
                        TreeMap<String, String> closeParams = new TreeMap<>();
                        closeParams.put("symbol", symbol);
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
            cancelParams.put("symbol", symbol);
            sendSignedRequest("DELETE", "/fapi/v1/allOpenOrders", cancelParams);
        } catch (Exception e) {
            Vesta.error("Error cerrando posiciones existentes: " + e.getMessage());
        }
    }

    public void changeLeverage(String symbol, int leverage) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("symbol", symbol);
        params.put("leverage", String.valueOf(leverage));
        sendSignedRequest("POST", "/fapi/v1/leverage", params);
    }

    public double getTickerPrice(String symbol) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("symbol", symbol);
        JsonNode root = sendRequest("GET", "/fapi/v1/ticker/price", params); // Public endpoint
        return root.get("price").asDouble();
    }

    public JsonNode sendSignedRequest(String method, String endpoint, TreeMap<String, String> params) throws BinanceApiSignedRequestException {
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
            // Parsear la respuesta
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.body());
            checkRepose(root, method, endpoint);
            return root;
        }catch (Exception e) {
            exceptionHandler.accept(e);
            throw new BinanceApiSignedRequestException(e);
        }
    }

    public JsonNode sendRequest(String method, String endpoint, TreeMap<String, String> params) throws BinanceApiRequestException {
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
            checkRepose(root, method, endpoint);
            return root;
        } catch (Exception e) {
            exceptionHandler.accept(e);
            throw new BinanceApiRequestException(e);
        }
    }

    public String buildQueryString(TreeMap<String, String> params) {
        StringJoiner sj = new StringJoiner("&");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            sj.add(entry.getKey() + "=" + entry.getValue());
        }
        return sj.toString();
    }

    public String hmacSha256(String data, String secret) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256_HMAC.init(secret_key);
        byte[] raw = sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(2 * raw.length);
        for (byte b : raw) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    // TODO: hacer que esté método obtenga el formato automáticamente
    public String formatQuantity(String symbol, double qty) {
        if (symbol.startsWith("BTC")) return String.format(Locale.US, "%.3f", qty);
        if (symbol.startsWith("ETH")) return String.format(Locale.US, "%.2f", qty);
        if (symbol.startsWith("XRP")) return String.format(Locale.US, "%.1f", qty);

        return String.format(Locale.US, "%.0f", qty); // Default int
    }

    public String formatPrice(String symbol, double price) {
        if (symbol.startsWith("BTC")) return String.format(Locale.US, "%.1f", price);
        if (symbol.startsWith("XRP")) return String.format(Locale.US, "%.4f", price);

        return String.format(Locale.US, "%.2f", price);
    }

    private void checkRepose(JsonNode node, String method, String endpoint) throws BinanceCodeException {
        if (node.has("code") && node.get("code").asInt() != 0) {
            if (node.get("code").asInt() == 200) {
                Vesta.info("✅ operacion Ok (%s:%s)", method, endpoint);
            }else {
                if (weakErrosCode.contains(node.get("code").asInt())) {
                    try {
                        throw new BinanceCodeException(node);
                    }catch (BinanceCodeException e){
                        Vesta.sendWaringException("Error al hacer una petición" , e);
                    }
                }else {
                    BinanceCodeException exception = new BinanceCodeException(node);
                    exceptionHandler.accept(exception);
                    throw exception;
                }
            }
        }
    }
}