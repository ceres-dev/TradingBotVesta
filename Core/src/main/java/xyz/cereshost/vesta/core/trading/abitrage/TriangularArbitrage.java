package xyz.cereshost.vesta.core.trading.abitrage;

import kotlin.collections.ArrayDeque;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.core.Main;
import xyz.cereshost.vesta.core.market.MarketStatus;
import xyz.cereshost.vesta.core.market.SymbolConfigurable;
import xyz.cereshost.vesta.core.trading.real.api.BinanceApi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
public class TriangularArbitrage {

    private static final double RELAX_EPSILON = 1e-12;
    private static final boolean NATIVE_AVAILABLE = loadNativeLibrary();

    private final BinanceApi binanceApi;

    private static native int[] detectTriangularCyclesNative(int assetCount,
                                                             int[] edgeFrom,
                                                             int[] edgeTo,
                                                             double[] edgeWeights);

    private static boolean loadNativeLibrary() {
        List<Path> candidates = List.of(
                Path.of("Core", "native", "cmake-build-debug", "libTradingBotVesta.dll"),
                Path.of("native", "cmake-build-debug", "libTradingBotVesta.dll")
        );

        for (Path candidate : candidates) {
            Path absolutePath = candidate.toAbsolutePath().normalize();
            if (!Files.isRegularFile(absolutePath)) {
                continue;
            }

            try {
                System.load(absolutePath.toString());
                return true;
            } catch (UnsatisfiedLinkError ignored) {
                ignored.printStackTrace();
            }
        }

        try {
            System.loadLibrary("TradingBotVesta");
            return true;
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    private int registerAsset(@NotNull List<String> assets, @NotNull String asset) {
        assets.add(asset);
        return assets.size() - 1;
    }

    private List<TriangularArbitrageOpportunity> detectFromSource(@NotNull List<String> assets,
                                                                  @NotNull List<ArbitrageEdge> edges,
                                                                  int[] edgeFrom,
                                                                  int[] edgeTo,
                                                                  double[] edgeWeights,
                                                                  @NotNull Set<String> seenCycles) {
        if (NATIVE_AVAILABLE) {
            return detectFromSourceNative(assets, edges, edgeFrom, edgeTo, edgeWeights, seenCycles);
        }else {
            return detectFromSourceJava(edges, edgeFrom, edgeTo, edgeWeights, seenCycles);
        }
    }

    private List<TriangularArbitrageOpportunity> detectFromSourceNative(@NotNull List<String> assets,
                                                                        @NotNull List<ArbitrageEdge> edges,
                                                                        int[] edgeFrom,
                                                                        int[] edgeTo,
                                                                        double[] edgeWeights,
                                                                        @NotNull Set<String> seenCycles) {
        int[] nativeCycles = detectTriangularCyclesNative(assets.size(), edgeFrom, edgeTo, edgeWeights);
        return buildOpportunitiesFromNativeResult(edges, nativeCycles, seenCycles);
    }

    private List<TriangularArbitrageOpportunity> detectFromSourceJava(@NotNull List<ArbitrageEdge> edges,
                                                                      int[] edgeFrom,
                                                                      int[] edgeTo,
                                                                      double[] edgeWeights,
                                                                      @NotNull Set<String> seenCycles) {
        int assetCount = 0;
        for (int i = 0; i < edgeFrom.length; i++) {
            assetCount = Math.max(assetCount, Math.max(edgeFrom[i], edgeTo[i]) + 1);
        }

        List<TriangularArbitrageOpportunity> opportunities = new ArrayList<>();
        for (int source = 0; source < assetCount; source++) {
            double[] distances = new double[assetCount];
            Arrays.fill(distances, Double.POSITIVE_INFINITY);
            distances[source] = 0.0;

            int[] predecessor = new int[assetCount];
            int[] predecessorEdge = new int[assetCount];
            Arrays.fill(predecessor, -1);
            Arrays.fill(predecessorEdge, -1);

            for (int i = 0; i < assetCount - 1; i++) {
                boolean relaxed = false;
                for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
                    int from = edgeFrom[edgeIndex];
                    int to = edgeTo[edgeIndex];
                    if (Double.isInfinite(distances[from])) {
                        continue;
                    }

                    double candidate = distances[from] + edgeWeights[edgeIndex];
                    if (candidate + RELAX_EPSILON < distances[to]) {
                        distances[to] = candidate;
                        predecessor[to] = from;
                        predecessorEdge[to] = edgeIndex;
                        relaxed = true;
                    }
                }
                if (!relaxed) {
                    break;
                }
            }

            for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
                int from = edgeFrom[edgeIndex];
                int to = edgeTo[edgeIndex];
                if (Double.isInfinite(distances[from])) {
                    continue;
                }

                if (distances[from] + edgeWeights[edgeIndex] + RELAX_EPSILON < distances[to]) {
                    predecessor[to] = from;
                    predecessorEdge[to] = edgeIndex;
                    int[] cycleEdgeIndexes = extractTriangularCycleEdgeIndexes(to, assetCount, predecessor, predecessorEdge);
                    if (cycleEdgeIndexes == null) {
                        continue;
                    }

                    TriangularArbitrageOpportunity opportunity = buildOpportunityFromCycleEdges(edges, cycleEdgeIndexes);
                    if (opportunity == null) {
                        continue;
                    }

                    String canonicalCycle = canonicalCycleKey(opportunity.assetsCycle());
                    if (seenCycles.add(canonicalCycle)) {
                        opportunities.add(opportunity);
                    }
                }
            }
        }

        return opportunities;
    }

    private int @Nullable [] extractTriangularCycleEdgeIndexes(int startVertex,
                                                               int assetCount,
                                                               int[] predecessor,
                                                               int[] predecessorEdge) {
        int vertex = startVertex;
        for (int i = 0; i < assetCount; i++) {
            vertex = predecessor[vertex];
            if (vertex < 0) {
                return null;
            }
        }

        List<Integer> cycleVertices = new ArrayDeque<>();
        int current = vertex;
        do {
            cycleVertices.add(current);
            current = predecessor[current];
            if (current < 0) {
                return null;
            }
        } while (current != vertex && cycleVertices.size() <= assetCount + 1);
        cycleVertices.add(vertex);
        Collections.reverse(cycleVertices);

        if (cycleVertices.size() != 4) {
            return null;
        }

        int[] cycleEdgeIndexes = new int[3];
        for (int i = 1; i < cycleVertices.size(); i++) {
            int edgeIndex = predecessorEdge[cycleVertices.get(i)];
            if (edgeIndex < 0) {
                return null;
            }
            cycleEdgeIndexes[i - 1] = edgeIndex;
        }

        return cycleEdgeIndexes;
    }

    private @NotNull List<TriangularArbitrageOpportunity> buildOpportunitiesFromNativeResult(@NotNull List<ArbitrageEdge> edges,
                                                                                             int[] nativeCycles,
                                                                                             @NotNull Set<String> seenCycles) {
        if (nativeCycles.length == 0) {
            return List.of();
        }

        List<TriangularArbitrageOpportunity> opportunities = new ArrayList<>();
        for (int offset = 0; offset + 2 < nativeCycles.length; offset += 3) {
            int[] cycleEdgeIndexes = new int[]{nativeCycles[offset], nativeCycles[offset + 1], nativeCycles[offset + 2]};
            TriangularArbitrageOpportunity opportunity = buildOpportunityFromCycleEdges(edges, cycleEdgeIndexes);
            if (opportunity == null) {
                continue;
            }

            String canonicalCycle = canonicalCycleKey(opportunity.assetsCycle());
            if (seenCycles.add(canonicalCycle)) {
                opportunities.add(opportunity);
            }
        }

        return opportunities;
    }

    private @Nullable TriangularArbitrageOpportunity buildOpportunityFromCycleEdges(@NotNull List<ArbitrageEdge> edges,
                                                                                    int[] cycleEdgeIndexes) {
        if (cycleEdgeIndexes.length != 3) {
            return null;
        }

        ArbitrageEdge first = getEdge(edges, cycleEdgeIndexes[0]);
        ArbitrageEdge second = getEdge(edges, cycleEdgeIndexes[1]);
        ArbitrageEdge third = getEdge(edges, cycleEdgeIndexes[2]);
        if (first == null || second == null || third == null) {
            return null;
        }

        if (!first.toAsset().equals(second.fromAsset())
                || !second.toAsset().equals(third.fromAsset())
                || !third.toAsset().equals(first.fromAsset())) {
            return null;
        }

        List<String> cycleAssets = List.of(
                first.fromAsset(),
                first.toAsset(),
                second.toAsset(),
                third.toAsset()
        );
        if (!cycleAssets.get(0).equals(cycleAssets.get(3))) {
            return null;
        }

        Set<String> distinctAssets = new HashSet<>(cycleAssets.subList(0, cycleAssets.size() - 1));
        if (distinctAssets.size() != 3) {
            return null;
        }

        List<ArbitrageEdge> cycleEdges = List.of(first, second, third);
        double rateProduct = first.rate() * second.rate() * third.rate();
        double totalWeight = first.weight() + second.weight() + third.weight();
        if (rateProduct <= 1.0 || totalWeight >= 0.0) {
            return null;
        }

        return new TriangularArbitrageOpportunity(
                cycleAssets,
                cycleEdges,
                rateProduct,
                (rateProduct - 1.0) * 100.0,
                totalWeight
        );
    }

    private @Nullable ArbitrageEdge getEdge(@NotNull List<ArbitrageEdge> edges, int index) {
        if (index < 0 || index >= edges.size()) {
            return null;
        }
        return edges.get(index);
    }

    private @NotNull String canonicalCycleKey(@NotNull List<String> cycleAssets) {
        List<String> assetsWithoutClosing = new ArrayList<>(cycleAssets.subList(0, cycleAssets.size() - 1));
        int bestIndex = 0;
        for (int i = 1; i < assetsWithoutClosing.size(); i++) {
            String current = assetsWithoutClosing.get(i);
            String best = assetsWithoutClosing.get(bestIndex);
            if (current.compareTo(best) < 0) {
                bestIndex = i;
            }
        }

        List<String> rotated = new ArrayList<>();
        for (int i = 0; i < assetsWithoutClosing.size(); i++) {
            rotated.add(assetsWithoutClosing.get((bestIndex + i) % assetsWithoutClosing.size()));
        }
        rotated.add(rotated.getFirst());
        return String.join("->", rotated);
    }

    @SneakyThrows
    public List<TriangularArbitrageOpportunity> findTriangularArbitrageOpportunities() {

        CompletableFuture<Map<String, BinanceApi.BookTicker>> tickersBookTicker = CompletableFuture.supplyAsync(() -> binanceApi.getBookTickers(null, false), Main.EXECUTOR);

        CompletableFuture<BinanceApi.ExchangeInfo> futureInfo = CompletableFuture.supplyAsync(()->binanceApi.getExchangeInfo(false), Main.EXECUTOR);
        BinanceApi.ExchangeInfo exchangeInfo = futureInfo.get();
        Map<String, BinanceApi.BookTicker> tickers = tickersBookTicker.get();

        Map<String, Integer> assetIndex = new HashMap<>();
        List<String> assets = new ArrayList<>();
        List<ArbitrageEdge> edges = new ArrayList<>();

        for (SymbolConfigurable symbolConfigurable : exchangeInfo.symbols()) {
            if (!symbolConfigurable.getMarketStatus().equals(MarketStatus.TRADING)) {
                continue;
            }

            String symbolName = symbolConfigurable.name();
            BinanceApi.BookTicker ticker = tickers.get(symbolName);
            if (ticker == null) {
                continue;
            }

            double bid = ticker.bidPrice();
            double ask = ticker.askPrice();
            if (bid <= 0.0 || ask <= 0.0) {
                continue;
            }

            String baseAsset = symbolConfigurable.getBaseAsset();
            String quoteAsset = symbolConfigurable.getQuoteAsset();
            assetIndex.computeIfAbsent(baseAsset, key -> registerAsset(assets, key));
            assetIndex.computeIfAbsent(quoteAsset, key -> registerAsset(assets, key));
            double fee = 0.001; // o dinámico
            double sellRate = bid * (1 - fee);
            double buyRate = (1.0 / ask) * (1 - fee);
            edges.add(new ArbitrageEdge(
                    symbolName,
                    baseAsset,
                    quoteAsset,
                    sellRate,
                    -Math.log(sellRate),
                    "SELL",
                    bid
            ));
            edges.add(new ArbitrageEdge(
                    symbolName,
                    quoteAsset,
                    baseAsset,
                    buyRate,
                    -Math.log(buyRate),
                    "BUY",
                    ask
            ));
        }

        if (assets.size() < 3 || edges.size() < 3) {
            return List.of();
        }

        int[] edgeFrom = new int[edges.size()];
        int[] edgeTo = new int[edges.size()];
        double[] edgeWeights = new double[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            ArbitrageEdge edge = edges.get(i);
            edgeFrom[i] = assetIndex.get(edge.fromAsset());
            edgeTo[i] = assetIndex.get(edge.toAsset());
            edgeWeights[i] = edge.weight();
        }

        Set<String> seenCycles = new HashSet<>();
        List<TriangularArbitrageOpportunity> opportunities = detectFromSource(
                assets,
                edges,
                edgeFrom,
                edgeTo,
                edgeWeights,
                seenCycles
        );
        opportunities.sort(Comparator.comparingDouble(TriangularArbitrageOpportunity::profitPercent).reversed());
        return opportunities;
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
