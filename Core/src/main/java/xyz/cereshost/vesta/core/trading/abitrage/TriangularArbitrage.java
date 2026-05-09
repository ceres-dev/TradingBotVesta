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
import xyz.cereshost.vesta.core.trading.real.api.BinanceApi;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class TriangularArbitrage {

    private static final double PROFIT_EPSILON = 1e-12;
    private static final double DEFAULT_FEE_RATE = 0.001; // 0.1% aprox
    private static final int MIN_CYCLE_LENGTH = 3;
    private static final int MAX_CYCLE_LENGTH = 4;

    private final BinanceApi binanceApi;
    private final Consumer<List<TriangularArbitrageOpportunity>> onOpportunity;

    private volatile boolean started = false;

    private final BlockingQueue<Data> queue = new LinkedBlockingQueue<>(2);

    @Blocking
    public void startSearch(Executor executor) {
        started = true;
        executor.execute(() -> {
            while (started) {
                try {
                    CompletableFuture<Map<String, BinanceApi.BookTicker>> tickersFuture = CompletableFuture.supplyAsync(
                            () -> binanceApi.getBookTickers(null, false),
                            Main.EXECUTOR
                    );
                    CompletableFuture<BinanceApi.ExchangeInfo> exchangeInfoFuture = CompletableFuture.supplyAsync(
                            () -> binanceApi.getExchangeInfo(false),
                            Main.EXECUTOR
                    );
                    Data newData = new Data(
                            exchangeInfoFuture.get(),
                            tickersFuture.get()
                    );
                    queue.put(newData);
                } catch (InterruptedException | ExecutionException e) {
                    Vesta.sendWaringException("Error al hacer solicitud a bianance", e);
                }
            }
        });
        executor.execute(() -> {
            while (started) {
                try {
                    Data localData = queue.take();
                    onOpportunity.accept(findTriangularArbitrageOpportunities(localData.exchangeInfo(), localData.tickers()));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private void stopSearch() {
        started = false;
        queue.clear();
    }

    private record Data(
            BinanceApi.ExchangeInfo exchangeInfo,
            Map<String, BinanceApi.BookTicker> tickers
    ){}

    @SneakyThrows
    private synchronized @NotNull List<TriangularArbitrageOpportunity> findTriangularArbitrageOpportunities(
            BinanceApi.@NotNull ExchangeInfo  exchangeInfo,
            @NotNull Map<String, BinanceApi.BookTicker> tickers
    ) {

        Map<String, List<ArbitrageEdge>> outgoingByFromAsset = new HashMap<>();

        for (SymbolConfigurable symbolConfigurable : exchangeInfo.symbols()) {
            if (!MarketStatus.TRADING.equals(symbolConfigurable.getMarketStatus())) {
                continue;
            }

            // Solo spot para arbitraje triangular clásico
            if (!symbolConfigurable.isSpot()) {
                continue;
            }

            String symbolName = symbolConfigurable.name();
            BinanceApi.BookTicker ticker = tickers.get(symbolName);
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
                        baseAsset,
                        quoteAsset,
                        sellRate,
                        -Math.log(sellRate),
                        "SELL",
                        bid
                ));
            }

            if (buyRate > 0.0) {
                addEdge(outgoingByFromAsset, new ArbitrageEdge(
                        symbolName,
                        quoteAsset,
                        baseAsset,
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
                    startAsset,
                    startAsset,
                    outgoingByFromAsset,
                    path,
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
                .computeIfAbsent(edge.fromAsset(), key -> new ArrayList<>())
                .add(edge);
    }

    private void searchCyclesFrom(
            @NotNull String startAsset,
            @NotNull String currentAsset,
            @NotNull Map<String, List<ArbitrageEdge>> outgoingByFromAsset,
            @NotNull Deque<ArbitrageEdge> path,
            @NotNull Set<String> visitedAssets,
            @NotNull Set<String> seenCycles,
            @NotNull List<TriangularArbitrageOpportunity> opportunities
    ) {
        List<ArbitrageEdge> outgoing = outgoingByFromAsset.get(currentAsset);
        if (outgoing == null || outgoing.isEmpty()) {
            return;
        }

        for (ArbitrageEdge edge : outgoing) {
            int nextLength = path.size() + 1;
            boolean closesCycle = startAsset.equals(edge.toAsset());

            if (closesCycle) {
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
            if (visitedAssets.contains(edge.toAsset())) {
                continue;
            }

            path.addLast(edge);
            visitedAssets.add(edge.toAsset());
            searchCyclesFrom(
                    startAsset,
                    edge.toAsset(),
                    outgoingByFromAsset,
                    path,
                    visitedAssets,
                    seenCycles,
                    opportunities
            );
            visitedAssets.remove(edge.toAsset());
            path.removeLast();
        }
    }

    private @Nullable TriangularArbitrageOpportunity buildOpportunityFromEdges(@NotNull List<ArbitrageEdge> cycleEdges) {
        int cycleLength = cycleEdges.size();
        if (cycleLength < MIN_CYCLE_LENGTH || cycleLength > MAX_CYCLE_LENGTH) {
            return null;
        }

        ArbitrageEdge first = cycleEdges.getFirst();
        String startAsset = first.fromAsset();
        String currentAsset = startAsset;

        List<String> cycleAssets = new ArrayList<>(cycleLength + 1);
        cycleAssets.add(startAsset);

        Set<String> distinctAssets = new HashSet<>();
        distinctAssets.add(startAsset);

        double rateProduct = 1.0;
        double totalWeight = 0.0;

        for (int i = 0; i < cycleLength; i++) {
            ArbitrageEdge edge = cycleEdges.get(i);
            if (!currentAsset.equals(edge.fromAsset())) {
                return null;
            }

            currentAsset = edge.toAsset();
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
            String fromAsset,
            String toAsset,
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
}
