package xyz.cereshost.vesta.core.trading.real.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.io.IOdata;
import xyz.cereshost.vesta.core.trading.Endpoints;

import java.net.http.HttpClient;
import java.util.function.Consumer;
@RequiredArgsConstructor
public abstract class BaseConnector {

    protected final Endpoints endpoint;


    @NotNull @Setter @Getter
    protected Consumer<Exception> exceptionHandler = e -> {};
    @NotNull protected final ObjectMapper mapper = new ObjectMapper();
    @NotNull protected final HttpClient client = HttpClient.newHttpClient();
    protected final IOdata.ApiKeysBinance apiKey = IOdata.loadApiKeysBinance();
}
