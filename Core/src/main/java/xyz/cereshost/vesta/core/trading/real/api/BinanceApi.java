package xyz.cereshost.vesta.core.trading.real.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.market.Symbol;
import xyz.cereshost.vesta.core.exception.BinanceCodeException;
import xyz.cereshost.vesta.core.message.Notifiable;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TimeInForce;
import xyz.cereshost.vesta.core.trading.TypeOrder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;

public interface BinanceApi extends Notifiable {

    Long placeAlgoOrder(@NotNull Symbol symbol,
                        @NotNull DireccionOperation side,
                        @NotNull TypeOrder type,
                        @Nullable TimeInForce timeInForce,
                        @Nullable Double quantity,
                        @NotNull Double stopPrice,
                        @NotNull Boolean reduceOnly
    );

    Long placeOrder(@NotNull Symbol symbol,
                    @NotNull DireccionOperation side,
                    @NotNull TypeOrder type,
                    @Nullable TimeInForce timeInForce,
                    @NotNull Double quantityLeverageCoin,
                    @Nullable Double trigger,
                    @NotNull Boolean reduceOnly,
                    @NotNull Boolean closePosition
    );

    void cancelOrder(@NotNull Symbol symbol, @NotNull Long orderId, @NotNull Boolean isAlgoOrder);

    void closeAll(Symbol symbol);

    List<OrderData> getAllOrders(Symbol symbol);

    @Nullable PositionData getPosition(Symbol symbol);

    void changeLeverage(Symbol symbol, int leverage);

    double getTickerPrice(Symbol symbol);

    double getBalance(Symbol symbol);

    void checkRepose(Symbol symbol, JsonNode node, String method, String endpoint) throws BinanceCodeException;

    void setExceptionHandler(Consumer<Exception> consumer);

    @NotNull JsonNode sendSignedRequest(String method, String endpoint, TreeMap<String, String> params);

    @NotNull JsonNode sendRequest(String method, String endpoint, TreeMap<String, String> params);

    default String buildQueryString(@NotNull TreeMap<String, String> params) {
        StringJoiner sj = new StringJoiner("&");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            sj.add(entry.getKey() + "=" + entry.getValue());
        }
        return sj.toString();
    }

    default String hmacSha256(@NotNull String data, @NotNull String secret) throws NoSuchAlgorithmException, InvalidKeyException {
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

    default String formatQuantity(@NotNull String symbol, double qty) {
        if (symbol.startsWith("BTC")) return String.format(Locale.US, "%.3f", qty);
        if (symbol.startsWith("ETH")) return String.format(Locale.US, "%.2f", qty);
        if (symbol.startsWith("XRP")) return String.format(Locale.US, "%.1f", qty);
        if (symbol.startsWith("SOL")) return String.format(Locale.US, "%.2f", qty);
        if (symbol.startsWith("XAU")) return String.format(Locale.US, "%.3f", qty);


        return String.format(Locale.US, "%.0f", qty); // Default int
    }

    default String formatPrice(@NotNull String symbol, double price) {
        if (symbol.startsWith("BTC")) return String.format(Locale.US, "%.1f", price);
        if (symbol.startsWith("XRP")) return String.format(Locale.US, "%.4f", price);
        if (symbol.startsWith("SOL")) return String.format(Locale.US, "%.2f", price);
        if (symbol.startsWith("XAU")) return String.format(Locale.US, "%.2f", price);

        return String.format(Locale.US, "%.2f", price);
    }

    record OrderData(
            long orderID,
            Double price,
            Double triggerPrice,
            Double quantity,
            Boolean isAlgoOrder,
            TimeInForce  timeInForce,
            TypeOrder type,
            DireccionOperation direccionOperation
    ) {}

    record PositionData(
            Double entryPrice,
            Double margen,
            Integer leverage,
            DireccionOperation direccionOperation
    ){}

}
