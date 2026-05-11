package xyz.cereshost.vesta.core.trading.abitrage;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.core.Main;
import xyz.cereshost.vesta.core.market.MarketStatus;
import xyz.cereshost.vesta.core.market.SymbolConfigurable;
import xyz.cereshost.vesta.core.trading.real.api.BinanceWebSocketFull;
import xyz.cereshost.vesta.core.trading.real.api.model.BookTicker;
import xyz.cereshost.vesta.core.trading.real.api.model.ExchangeInfo;
import xyz.cereshost.vesta.core.trading.real.api.model.Ticker24H;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class TriangularArbitrage {

    private static final double PROFIT_EPSILON = 1e-12;
    private static final double DEFAULT_FEE_RATE = 0.001; // 0.1% aprox
    private static final int MIN_CYCLE_LENGTH = 3;
    private static final int MAX_CYCLE_LENGTH = 3;

    private final BinanceWebSocketFull binanceApi;
    private final Consumer<List<TriangularArbitrageOpportunity>> onOpportunity;

    private volatile boolean started = false;
    @Nullable private volatile ExchangeInfo exchangeInfoSpot = null;
    @Nullable private volatile Consumer<BookTicker> streamListener = null;
    @NotNull private final ConcurrentMap<String, BookTicker> liveTickers = new ConcurrentHashMap<>();
    @NotNull private final AtomicBoolean calculationInProgress = new AtomicBoolean(false);
    @NotNull private final AtomicBoolean calculationRequested = new AtomicBoolean(false);
    @Nullable private volatile Executor calculationExecutor = null;

    @Blocking
    public void startSearch(Executor executor) {
        if (started) {
            return;
        }
        started = true;
        calculationExecutor = executor;

        CompletableFuture<ExchangeInfo> exchangeInfoFuture = CompletableFuture.supplyAsync(
                () -> binanceApi.getRequest().getExchangeInfo(false),
                Main.EXECUTOR
        );
        CompletableFuture<Map<String, BookTicker>> tickersFuture = CompletableFuture.supplyAsync(
                () -> binanceApi.getRequest().getBookTickers(null, false),
                Main.EXECUTOR
        );

        executor.execute(() -> {
            try {
                exchangeInfoSpot = exchangeInfoFuture.get();
                liveTickers.clear();
                liveTickers.putAll(tickersFuture.get());

                Set<Ticker24H> ticker24H = binanceApi.getRequest().getTicker24H(null);
                HashMap<String, Ticker24H> bookTicker24H = new HashMap<>();
                for (Ticker24H ticker : ticker24H) {
                    bookTicker24H.put(ticker.symbol(), ticker);
                }

                Set<String> symbolsToSubscribe = getSpotTradingSymbols(Objects.requireNonNull(exchangeInfoSpot), bookTicker24H);
                liveTickers.keySet().retainAll(symbolsToSubscribe);
                binanceApi.getStream().subscribeIndividualSymbolBookTickerStreams(
                        symbolsToSubscribe,
                        this::onBookTickerUpdate
                );
                requestCalculation();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopSearch();
                Vesta.sendWaringException("Error iniciando stream de arbitraje", e);
            } catch (ExecutionException e) {
                stopSearch();
                Vesta.sendWaringException("Error al hacer solicitud a binance", e);
            } catch (Exception e) {
                stopSearch();
                Vesta.sendWaringException("Error suscribiendo streams de bookTicker", e);
            }
        });
    }

    private void stopSearch() {
        started = false;
        Consumer<BookTicker> listener = streamListener;
        if (listener != null) {
            binanceApi.getStream().removeBookTickerListener(listener);
        }
        streamListener = null;
        exchangeInfoSpot = null;
        liveTickers.clear();
        calculationRequested.set(false);
    }

    private void onBookTickerUpdate(@NotNull BookTicker bookTicker) {
        if (!started) {
            return;
        }
        liveTickers.put(bookTicker.symbol(), bookTicker);
        requestCalculation();
    }

    private void requestCalculation() {
        calculationRequested.set(true);
        tryStartCalculationLoop();
    }

    private void tryStartCalculationLoop() {
        Executor executor = calculationExecutor;
        if (executor == null) {
            return;
        }
        if (!calculationInProgress.compareAndSet(false, true)) {
            return;
        }
        executor.execute(this::runCalculationLoop);
    }

    private void runCalculationLoop() {
        try {
            while (started && calculationRequested.getAndSet(false)) {
                ExchangeInfo exchangeInfo = exchangeInfoSpot;
                if (exchangeInfo == null) {
                    continue;
                }
                List<TriangularArbitrageOpportunity> list = findTriangularArbitrageOpportunities(
                        exchangeInfo,
                        new HashMap<>(liveTickers)
                );
                onOpportunity.accept(list);
            }
        } catch (Exception e) {
            Vesta.sendWaringException("Error calculando arbitrajes triangulares", e);
        } finally {
            calculationInProgress.set(false);
            if (started && calculationRequested.get()) {
                tryStartCalculationLoop();
            }
        }
    }

    private @NotNull Set<String> getSpotTradingSymbols(@NotNull ExchangeInfo exchangeInfo, @NotNull HashMap<String, Ticker24H> bookTicker24H) {
        Map<String, List<AssetRate>> conversionGraph = buildAssetConversionGraph(exchangeInfo);
        List<SymbolVolume> candidates = new ArrayList<>();

        for (SymbolConfigurable symbolConfigurable : exchangeInfo.symbols()) {
            if (!symbolConfigurable.getIsSpot()) continue;
            if (!MarketStatus.TRADING.equals(symbolConfigurable.getMarketStatus())) continue;
            if (!symbolConfigurable.getIsAllowTrading()) continue;

            Ticker24H ticker24H = bookTicker24H.get(symbolConfigurable.name());
            if (ticker24H == null) continue;

            double quoteVolume = ticker24H.quoteVolumen() == null ? 0.0 : ticker24H.quoteVolumen();
            double baseVolume = ticker24H.baseVolumen() == null ? 0.0 : ticker24H.baseVolumen();
            double volumeUsdt = 0.0;

            if (quoteVolume > 0.0) {
                volumeUsdt = convertAssetAmountToUsdt(symbolConfigurable.getQuoteAsset(), quoteVolume, conversionGraph);
            }
            if (volumeUsdt <= 0.0 && baseVolume > 0.0) {
                volumeUsdt = convertAssetAmountToUsdt(symbolConfigurable.getBaseAsset(), baseVolume, conversionGraph);
            }

            candidates.add(new SymbolVolume(symbolConfigurable.name(), volumeUsdt));
        }

        candidates.sort((a, b) -> Double.compare(b.volumeUsdt(), a.volumeUsdt()));
        int limit = Math.min(1000, candidates.size());
        Set<String> result = new HashSet<>(limit);
        for (int i = 0; i < limit; i++) {
            result.add(candidates.get(i).symbol());
        }

        return result;
    }

    private @NotNull Map<String, List<AssetRate>> buildAssetConversionGraph(@NotNull ExchangeInfo exchangeInfo) {
        Map<String, List<AssetRate>> graph = new HashMap<>();
        for (SymbolConfigurable symbolConfigurable : exchangeInfo.symbols()) {
            if (!symbolConfigurable.getIsSpot()) continue;
            if (!MarketStatus.TRADING.equals(symbolConfigurable.getMarketStatus())) continue;

            BookTicker ticker = liveTickers.get(symbolConfigurable.name());
            if (ticker == null || ticker.bidPrice() == null || ticker.askPrice() == null) continue;

            double bid = ticker.bidPrice();
            double ask = ticker.askPrice();
            if (bid <= 0.0 || ask <= 0.0) continue;

            double midPrice = (bid + ask) / 2.0;
            if (midPrice <= 0.0) continue;

            String base = symbolConfigurable.getBaseAsset();
            String quote = symbolConfigurable.getQuoteAsset();
            graph.computeIfAbsent(base, k -> new ArrayList<>()).add(new AssetRate(quote, midPrice));
            graph.computeIfAbsent(quote, k -> new ArrayList<>()).add(new AssetRate(base, 1.0 / midPrice));
        }
        return graph;
    }

    private double convertAssetAmountToUsdt(
            @NotNull String asset,
            double amount,
            @NotNull Map<String, List<AssetRate>> conversionGraph
    ) {
        if (amount <= 0.0) return 0.0;
        if ("USDT".equalsIgnoreCase(asset)) return amount;

        record Node(String asset, double amount) {}

        Deque<Node> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new Node(asset, amount));
        visited.add(asset);

        while (!queue.isEmpty()) {
            Node current = queue.poll();
            List<AssetRate> rates = conversionGraph.get(current.asset());
            if (rates == null) continue;

            for (AssetRate rate : rates) {
                double convertedAmount = current.amount() * rate.rate();
                if (convertedAmount <= 0.0) continue;

                if ("USDT".equalsIgnoreCase(rate.toAsset())) {
                    return convertedAmount;
                }
                if (visited.add(rate.toAsset())) {
                    queue.add(new Node(rate.toAsset(), convertedAmount));
                }
            }
        }

        return 0.0;
    }

    private record AssetRate(
            String toAsset,
            double rate
    ) {}

    private record SymbolVolume(
            String symbol,
            double volumeUsdt
    ) {}

    @SneakyThrows
    public synchronized @NotNull List<TriangularArbitrageOpportunity> findTriangularArbitrageOpportunities(
            @NotNull ExchangeInfo exchangeInfo,
            @NotNull Map<String, BookTicker> tickers
    ) {

        Map<String, List<ArbitrageEdge>> outgoingByFromAsset = new HashMap<>();

        for (SymbolConfigurable symbolConfigurable : exchangeInfo.symbols()) {
            if (!MarketStatus.TRADING.equals(symbolConfigurable.getMarketStatus())) {
                continue;
            }

            // Solo spot para arbitraje triangular clásico
            if (!symbolConfigurable.getIsSpot()) {
                continue;
            }

            String symbolName = symbolConfigurable.name();
            BookTicker ticker = tickers.get(symbolName);
            if (ticker == null) {
                continue;
            }

            Double bidObj = ticker.bidPrice();
            Double askObj = ticker.askPrice();
            if (bidObj == null || askObj == null) {
                continue;
            }

            double bid = bidObj;
            double ask = askObj;
            if (bid <= 0.0 || ask <= 0.0) {
                continue;
            }

            String baseAsset = symbolConfigurable.getBaseAsset();
            String quoteAsset = symbolConfigurable.getQuoteAsset();
            if (baseAsset.equals("?") || quoteAsset.equals("?")) {
                continue;
            }

            double sellRate = bid * (1.0 - DEFAULT_FEE_RATE);
            double buyRate = (1.0 / ask) * (1.0 - DEFAULT_FEE_RATE);

            if (sellRate > 0.0) {
                addEdge(outgoingByFromAsset, new ArbitrageEdge(
                        symbolName,
                        new NameSymbol(baseAsset),
                        new NameSymbol(quoteAsset),
                        sellRate,
                        -Math.log(sellRate),
                        "SELL",
                        bid
                ));
            }

            if (buyRate > 0.0) {
                addEdge(outgoingByFromAsset, new ArbitrageEdge(
                        symbolName,
                        new NameSymbol(quoteAsset),
                        new NameSymbol(baseAsset),
                        buyRate,
                        -Math.log(buyRate),
                        "BUY",
                        ask
                ));
            }
        }

        if (outgoingByFromAsset.size() < MIN_CYCLE_LENGTH) {
            return List.of();
        }

        Set<String> seenCycles = new HashSet<>();
        List<TriangularArbitrageOpportunity> opportunities = new ArrayList<>();

        for (String startAsset : outgoingByFromAsset.keySet()) {
            Deque<ArbitrageEdge> path = new ArrayDeque<>(MAX_CYCLE_LENGTH);
            Set<String> visitedAssets = new HashSet<>();
            visitedAssets.add(startAsset);
            searchCyclesFrom(
                    new NameSymbol(startAsset),
                    new NameSymbol(startAsset),
                    outgoingByFromAsset,
                    path,
                    new IntegerAtomic(0),
                    visitedAssets,
                    seenCycles,
                    opportunities
            );
        }

        opportunities.sort(Comparator.comparingDouble(TriangularArbitrageOpportunity::profitPercent).reversed());
        return opportunities;
    }

    private void addEdge(@NotNull Map<String, List<ArbitrageEdge>> outgoingByFromAsset,
                         @NotNull ArbitrageEdge edge) {
        outgoingByFromAsset
                .computeIfAbsent(edge.fromAsset().symbol, key -> new ArrayList<>())
                .add(edge);
    }


    private void searchCyclesFrom(
            @NotNull NameSymbol startAsset,
            @NotNull NameSymbol currentAsset,
            @NotNull Map<String, List<ArbitrageEdge>> outgoingByFromAsset,
            @NotNull Deque<ArbitrageEdge> path,
            @NotNull IntegerAtomic sizePath,
            @NotNull Set<String> visitedAssets,
            @NotNull Set<String> seenCycles,
            @NotNull List<TriangularArbitrageOpportunity> opportunities
    ) {
        List<ArbitrageEdge> outgoing = outgoingByFromAsset.get(currentAsset.symbol);
        if (outgoing == null || outgoing.isEmpty()) {
            return;
        }

        for (ArbitrageEdge edge : outgoing) {
            int nextLength = sizePath.get() + 1;


            if ((startAsset.hash
                    -
                    edge.toAsset.hash) == 0) {
                if (nextLength < MIN_CYCLE_LENGTH || nextLength > MAX_CYCLE_LENGTH) {
                    continue;
                }

                path.addLast(edge);
                TriangularArbitrageOpportunity opportunity = buildOpportunityFromEdges(new ArrayList<>(path));
                path.removeLast();

                if (opportunity == null) {
                    continue;
                }

                String canonicalKey = canonicalCycleKey(opportunity.assetsCycle());
                if (seenCycles.add(canonicalKey)) {
                    opportunities.add(opportunity);
                }
                continue;
            }

            if (nextLength >= MAX_CYCLE_LENGTH) {
                continue;
            }
            if (visitedAssets.contains(edge.toAsset().symbol)) {
                continue;
            }

            path.addLast(edge);
            sizePath.increment();
            visitedAssets.add(edge.toAsset().symbol);
            searchCyclesFrom(
                    startAsset,
                    edge.toAsset(),
                    outgoingByFromAsset,
                    path,
                    sizePath,
                    visitedAssets,
                    seenCycles,
                    opportunities
            );
            visitedAssets.remove(edge.toAsset().symbol);
            sizePath.decrement();
            path.removeLast();
        }
    }

    private @Nullable TriangularArbitrageOpportunity buildOpportunityFromEdges(@NotNull List<ArbitrageEdge> cycleEdges) {
        int cycleLength = cycleEdges.size();
        if (cycleLength < MIN_CYCLE_LENGTH || cycleLength > MAX_CYCLE_LENGTH) {
            return null;
        }

        ArbitrageEdge first = cycleEdges.getFirst();
        String startAsset = first.fromAsset().symbol;
        String currentAsset = startAsset;

        List<String> cycleAssets = new ArrayList<>(cycleLength + 1);
        cycleAssets.add(startAsset);

        Set<String> distinctAssets = new HashSet<>();
        distinctAssets.add(startAsset);

        double rateProduct = 1.0;
        double totalWeight = 0.0;

        for (int i = 0; i < cycleLength; i++) {
            ArbitrageEdge edge = cycleEdges.get(i);
            if (!currentAsset.equals(edge.fromAsset().symbol)) {
                return null;
            }

            currentAsset = edge.toAsset().symbol;
            cycleAssets.add(currentAsset);

            rateProduct *= edge.rate();
            totalWeight += edge.weight();

            if (i < cycleLength - 1 && !distinctAssets.add(currentAsset)) {
                return null;
            }
        }

        if (!startAsset.equals(currentAsset)) {
            return null;
        }
        if (distinctAssets.size() != cycleLength) {
            return null;
        }
        if (rateProduct <= 1.0 + PROFIT_EPSILON) {
            return null;
        }
        if (totalWeight >= -PROFIT_EPSILON) {
            return null;
        }

        return new TriangularArbitrageOpportunity(
                cycleAssets,
                List.copyOf(cycleEdges),
                rateProduct,
                (rateProduct - 1.0) * 100.0,
                totalWeight
        );
    }

    private @NotNull String canonicalCycleKey(@NotNull List<String> cycleAssets) {
        List<String> raw = new ArrayList<>(cycleAssets.subList(0, cycleAssets.size() - 1));
        int size = raw.size();

        List<String> best = null;
        for (int shift = 0; shift < size; shift++) {
            List<String> rotated = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                rotated.add(raw.get((shift + i) % size));
            }

            if (best == null || compareLex(rotated, best) < 0) {
                best = rotated;
            }
        }

        return String.join("->", best) + "->" + best.getFirst();
    }

    private int compareLex(@NotNull List<String> a, @NotNull List<String> b) {
        for (int i = 0; i < a.size(); i++) {
            int cmp = a.get(i).compareTo(b.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    public record ArbitrageEdge(
            String symbol,
            NameSymbol fromAsset,
            NameSymbol toAsset,
            double rate,
            double weight,
            String action,
            double referencePrice
    ) {}

    public record TriangularArbitrageOpportunity(
            List<String> assetsCycle,
            List<ArbitrageEdge> edges,
            double rateProduct,
            double profitPercent,
            double totalWeight
    ) {}

    public static class NameSymbol{
        public final String symbol;
        public final int hash;

        public NameSymbol(String symbol) {
            this.symbol = symbol;
            int h = 0;
            for (byte b : symbol.getBytes(StandardCharsets.UTF_8)) {
                h = 7 * ((h + 37) << b);
            }
            this.hash = h;
        }
    }

    private static class IntegerAtomic {
        public int value;
        public IntegerAtomic(int value) {
            this.value = value;
        }

        public void increment() {
            value++;
        }

        public void decrement() {
            value--;
        }

        public int get() {
            return value;
        }
    }
}
