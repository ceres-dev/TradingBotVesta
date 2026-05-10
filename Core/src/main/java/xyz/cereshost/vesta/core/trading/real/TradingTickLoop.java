package xyz.cereshost.vesta.core.trading.real;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.core.exception.BinanceCodeWeakException;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.io.IOMarket;
import xyz.cereshost.vesta.core.io.setup.LoadDataMethodBinance;
import xyz.cereshost.vesta.core.market.*;
import xyz.cereshost.vesta.core.message.MediaNotification;
import xyz.cereshost.vesta.core.message.Notifiable;
import xyz.cereshost.vesta.core.strategy.StrategyConfig;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.strategy.TradingStrategyConfigurable;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.trading.real.api.BinanceApi;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public final class TradingTickLoop implements Notifiable {

    private static final long OFFSET = 1_500;
    private static final long MAX_ALLOWED_GAP_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long RECENT_WINDOW_MS = TimeUnit.DAYS.toMillis(1);
    private static final int LOCAL_ZIP_WARMUP_DAYS = 5;

    private final TypeMarket typeMarket;
    @Getter
    private final Executor executor = Executors.newFixedThreadPool(6);
    @NotNull
    private final TradingManagerBinance manager;
    @Nullable
    private final PredictionEngine engine;
    @Getter
    private final TradingStrategy strategy;
    @NotNull @Getter @Setter
    private MediaNotification mediaNotification;
    @Nullable
    private final Market localWarmupMarket;
    @Nullable
    private final Market recentMarket;


    private static final ScheduledExecutorService WORKERS = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setName("Candle-Worker");
                return t;
            });

    public TradingTickLoop(@NotNull TypeMarket typeMarket,
                           @Nullable PredictionEngine engine,
                           @NotNull TradingStrategy tradingStrategy,
                           @NotNull BinanceApi binanceApi,
                           @Nullable MediaNotification mediaNotification
    ) {
        this.typeMarket = typeMarket;
        this.engine = engine;
        this.strategy = tradingStrategy;
        this.mediaNotification = Objects.requireNonNullElse(mediaNotification, MediaNotification.empty());
        typeMarket.symbol().configure(binanceApi);
        try {
            binanceApi.setExceptionHandler(this::stop);
            binanceApi.setMediaNotification(this.mediaNotification);

            Vesta.info("Estrategia: %s", strategy.getClass().getSimpleName());
            Market warmupMarket = IOMarket.loadMarketsRecentDays(typeMarket, LOCAL_ZIP_WARMUP_DAYS, true);
            if (warmupMarket.getCandles().isEmpty()) {
                this.localWarmupMarket = null;
                Vesta.warning("No se encontró histórico LOCAL_ZIP para warmup, usando solo BINANCE en vivo.");
            } else {
                this.localWarmupMarket = warmupMarket;
                Vesta.info("Warmup cargado desde LOCAL_ZIP: %d días (%d velas).",
                        LOCAL_ZIP_WARMUP_DAYS, warmupMarket.getCandles().size()
                );
            }
            Market recent = IOMarket.loadMarket(typeMarket, new LoadDataMethodBinance(1440, 1000, 100));
            this.recentMarket = recent.getCandles().isEmpty() ? null : recent;
            Market bootMarket = loadMarket();
            manager = new TradingManagerBinance(binanceApi, mediaNotification, bootMarket);
            manager.setTradingTickLoop(this);
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isClose = false;

    public void startCandleLoop() {
        if (typeMarket.symbol().isTradFi()){
            manager.signContract();
        }
        if (localWarmupMarket != null) {
            WORKERS.scheduleAtFixedRate(() -> {
                try {
                    synchronized (localWarmupMarket) {
                        localWarmupMarket.clear();
                        localWarmupMarket.concat(IOMarket.loadMarketsRecentDays(typeMarket, LOCAL_ZIP_WARMUP_DAYS, false));
                    }
                } catch (InterruptedException | IOException e) {
                    throw new RuntimeException(e);
                }
            }, 12, 12, TimeUnit.HOURS);
        }
        WORKERS.submit(() -> {
            while (!Thread.currentThread().isInterrupted() && !isClose) {
                try {
                    long serverTime = getBinanceServerTime();
                    long nextCandle = ((serverTime / typeMarket.timeFrameMarket().getMilliseconds()) + 1) * typeMarket.timeFrameMarket().getMilliseconds();
                    long targetTime = nextCandle + OFFSET;
                    long sleep = targetTime - serverTime;

                    Vesta.info("💤 Tiempo de espera: %.2fs", (float) sleep/1000);
                    if (sleep > 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(sleep));
                    long time = System.currentTimeMillis();

                    performTick();
                    updateStatus();

                    Vesta.info("🕑 Tiempo de procesamiento: %.2fss", (float) (System.currentTimeMillis() - time)/1000f);
                } catch (Exception e) {
                    Vesta.sendErrorException("Error en el loop", e);
                    LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
                }
            }
        });
    }

    public static long getBinanceServerTime() throws Exception {
        URL url = URI.create("https://api.binance.com/api/v3/time").toURL();
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

    private int counter = 0;

    private void performTick() throws InterruptedException, IOException {
        AtomicReference<Market> market = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(2);
        counter++;
        executor.execute(() -> {
            try {
                market.set(loadMarket());
            } catch (InterruptedException | IOException e) {
                stop(e);
            } finally {
                latch.countDown();
            }
        });

        executor.execute(() -> {
            manager.sync();
            latch.countDown();
        });

        latch.await();
        Market tickMarket = market.get();
        if (tickMarket == null) {
            Vesta.warning("No se pudo cargar mercado para este tick.");
            return;
        }

        StrategyConfig config;
        if (strategy instanceof TradingStrategyConfigurable configurable) {
            config = configurable.getStrategyConfig(manager);
        } else {
            config = StrategyConfig.builder().build();
        }

        if (hasLargeGap(tickMarket, MAX_ALLOWED_GAP_MS)) {
            if ((counter % 10) == 0 && localWarmupMarket != null) {
                synchronized (localWarmupMarket) {
                    localWarmupMarket.clear();
                    localWarmupMarket.concat(IOMarket.loadMarketsRecentDays(typeMarket, LOCAL_ZIP_WARMUP_DAYS, false));
                }
            }
            return;
        }
        int lookBack = engine != null ? engine.getLookBack() : config.getLookBack();
        SequenceCandles allCandles = strategy.getBuilder().build(tickMarket);
        if (allCandles.size() <= lookBack + 1) {
            Vesta.warning("Histórico insuficiente para tick: %d velas", allCandles.size());
            return;
        }
//        ChartUtils.showCandleChart("temporal", allCandles, "?");

        Vesta.info("💰 Precio del %s: %.2f", typeMarket.symbol(), allCandles.getCandleLast().getClose());

        Optional<PredictionEngine.SequenceCandlesPrediction> result;
        int endExclusive = allCandles.size() - 1;
        SequenceCandles visible = allCandles.subSequence(lookBack, endExclusive);

        if (engine != null) result = Optional.of(engine.predictNextPriceDetail(visible));
        else result = Optional.empty();

        manager.getOpenPosition().ifPresent(TradingManager.OpenPosition::nextStep);
        strategy.executeStrategy(result, allCandles, manager);
    }

    private static boolean hasLargeGap(@NotNull Market market, long maxGapMs) {
        Iterator<Candle> iterator = market.getCandles().iterator();
        if (!iterator.hasNext()) {
            return false;
        }
        long prev = iterator.next().getOpenTime();
        long maxGap = 0;
        while (iterator.hasNext()) {
            long current = iterator.next().getOpenTime();
            long gap = current - prev;
            if (gap > maxGap) {
                maxGap = gap;
            }
            prev = current;
        }
        if (maxGap >= maxGapMs) {
            Vesta.warning("Hueco temporal detectado: %.2f minutos (umbral %.2f). Se omite estrategia.",
                    maxGap / 60_000.0, maxGapMs / 60_000.0);
            return true;
        }
        return false;
    }

    private static void trimMarketToWindow(@NotNull Market market, long minOpenTime) {
        trimSetByTime(market.getCandles(), Candle::getOpenTime, minOpenTime);
        trimSetByTime(market.getTrades(), Trade::time, minOpenTime);
        trimSetByTime(market.getDepths(), Depth::getDate, minOpenTime);
    }

    private static <T> void trimSetByTime(@NotNull LinkedHashSet<T> set,
                                          @NotNull Market.TimeAccessor<T> accessor,
                                          long minTime
    ) {
        if (set.isEmpty()) {
            return;
        }
        Iterator<T> iterator = set.iterator();
        while (iterator.hasNext()) {
            T item = iterator.next();
            if (accessor.time(item) >= minTime) {
                break;
            }
            iterator.remove();
        }
    }

    public void updateStatus(){
        if (isClose) {
            updateStatusType(StatusType.STOPPED);
            updateStatus("Loop detenido");
            return;
        }
        Symbol symbol = typeMarket.symbol();
        Optional<TradingManager.OpenPosition> openPosition = manager.getOpenPosition();
        if (openPosition.isPresent()) {
            updateStatusType(StatusType.WAITING);
            TradingManager.OpenPosition open = openPosition.get();
            if (open.isUpDireccion()){
                updateStatus("Operando Long en %s", symbol);
            }else {
                updateStatus("Operando Short en %s", symbol);
            }
        }else {
            updateStatusType(StatusType.WAITING);
            updateStatus("Esperando el momento...");
        }
    }

    public void stop(Exception e){
        if (!(e instanceof BinanceCodeWeakException)) {
            Vesta.sendErrorException("Deteniendo Loop por: ", e);
            mediaNotification.critical(String.format("**Deteniendo Loop** por: %s. Revisar Consola para más información", e.getMessage()));
            updateStatus();
            isClose = true;
        }
    }

    @Contract(pure = true, value = "-> new")
    public Market loadMarket() throws IOException, InterruptedException {
        Market liveMarket = IOMarket.loadMarket(typeMarket, new LoadDataMethodBinance(5, 30, 10));
        if (recentMarket != null) {
            synchronized (recentMarket) {
                recentMarket.concat(liveMarket);
                trimMarketToWindow(recentMarket, System.currentTimeMillis() - RECENT_WINDOW_MS);
            }
        }

        Market merged = new Market(typeMarket);
        if (localWarmupMarket == null || localWarmupMarket.getCandles().isEmpty()) {
            if (recentMarket != null) {
                synchronized (recentMarket) {
                    merged.concat(recentMarket);
                }
                return merged;
            }
            return liveMarket;
        }else {
            synchronized (localWarmupMarket) {
                merged.concat(localWarmupMarket);
            }
            if (recentMarket != null) {
                synchronized (recentMarket) {
                    merged.concat(recentMarket);
                }
            } else {
                merged.concat(liveMarket);
            }
            return merged;
        }
    }
}
