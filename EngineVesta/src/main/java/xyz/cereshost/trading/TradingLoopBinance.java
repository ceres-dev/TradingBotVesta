package xyz.cereshost.trading;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.BinanceApi;
import xyz.cereshost.DataSource;
import xyz.cereshost.io.IOMarket;
import xyz.cereshost.io.IOdata;
import xyz.cereshost.utils.BuilderData;
import xyz.cereshost.common.Vesta;
import xyz.cereshost.common.market.Candle;
import xyz.cereshost.common.market.Market;
import xyz.cereshost.engine.PredictionEngine;
import xyz.cereshost.engine.VestaEngine;
import xyz.cereshost.strategy.TradingStrategy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public class TradingLoopBinance {

    private static final long CANDLE_MS = 60_000;
    private static final long OFFSET = 5_000;
    private final String symbol;
    @Getter
    private final Executor executor = Executors.newFixedThreadPool(6);
    @NotNull
    private final TradingBinance trading;
    @Nullable
    private final PredictionEngine engine;
    @Getter
    private final TradingStrategy strategy;

    private static final ExecutorService WORKERS = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r);
                t.setName("Candle-Worker");
                return t;
            });

    public TradingLoopBinance(String symbol, @Nullable PredictionEngine engine, TradingStrategy tradingStrategy) {
        this.symbol = symbol;
        this.engine = engine;
        this.strategy = tradingStrategy;
        try {
            IOdata.ApiKeysBinance apiKeysBinance = IOdata.loadApiKeysBinance();
            BinanceApi binanceApi = new BinanceApi(apiKeysBinance.key(), apiKeysBinance.secret(), true);
            binanceApi.setExceptionHandler(this::stop);
            trading = new TradingBinance(binanceApi, IOMarket.loadMarkets(DataSource.LOCAL_NETWORK_MINIMAL, symbol));
            trading.setTradingLoopBinance(this);
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isClose = false;

    public void startCandleLoop() {
        WORKERS.submit(() -> {
            while (!Thread.currentThread().isInterrupted() && !isClose) {
                try {
                    long serverTime = getBinanceServerTime() + OFFSET;
                    long nextCandle = ((serverTime / CANDLE_MS) + 1) * CANDLE_MS;
                    long sleep = Math.abs(nextCandle - (serverTime));

                    Vesta.info("💤 Tiempo de espera: %.2fs", (float) sleep/1000);
                    if (sleep > 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(sleep));
                    long time = System.currentTimeMillis();

                    performTick();

                    Vesta.info("🕑 Tiempo de procesamiento: %.2fss", (float) (System.currentTimeMillis() - time)/1000f);
                } catch (Exception e) {
                    e.printStackTrace();
                    LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
                }
            }
        });
    }



    public static long getBinanceServerTime() throws Exception {
        URL url = new URL("https://api.binance.com/api/v3/time");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(con.getInputStream()))) {

            String response = reader.readLine();
            return Long.parseLong(
                    response.replaceAll("\\D+", "")
            );
        }
    }

    private void performTick() throws InterruptedException {
        AtomicReference<Market> market = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(2);

        executor.execute(() -> {
            try {
                market.set(IOMarket.loadMarkets(DataSource.BINANCE, symbol));
            } catch (InterruptedException | IOException e) {
                stop(e);
            }
            latch.countDown();
        });

        executor.execute(() -> {
            trading.syncWithBinance();
            latch.countDown();
        });

        latch.await();

        List<Candle> allCandles = BuilderData.to1mCandles(market.get());
        List<Candle> visible = allCandles.subList(VestaEngine.LOOK_BACK, allCandles.size() - 1);
        PredictionEngine.PredictionResult result;
        if (engine != null) {
            result = engine.predictNextPriceDetail(visible, symbol);
        }else {
            result = null;
        }
        trading.getOpens().forEach(Trading.OpenOperation::next);
        trading.updateState(symbol);
        strategy.executeStrategy(result, visible, trading);
    }

    public void stop(Exception e){
        Vesta.sendErrorException("Deteniendo Loop por: ", e);
        isClose = true;
    }
}
