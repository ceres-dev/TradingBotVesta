package xyz.cereshost.vesta.core.market;

import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static xyz.cereshost.vesta.core.market.TimeFrameMarket.*;

public class Market {

    public Market(@NotNull Symbol symbol) {
        this(symbol, ONE_MINUTE);
    }

    public Market(@NotNull Symbol symbol, TimeFrameMarket timeFrameMarket) {
        this.symbol = symbol;
        this.timeFrameMarket = timeFrameMarket;
        this.trades = new LinkedHashSet<>(10_000);
        this.depths = new LinkedHashSet<>();
        this.candles = new LinkedHashSet<>(1_000);
        this.metrics = new LinkedHashSet<>();
    }

    public Market(@NotNull TypeMarket typeMarket) {
        this(typeMarket.symbol(),  typeMarket.timeFrameMarket());
    }

    @Getter
    private final TimeFrameMarket timeFrameMarket;
    @NotNull
    @Getter
    private final Symbol symbol;
    @Getter
    private LinkedHashSet<Trade> trades;
    @Getter
    private LinkedHashSet<Candle> candles;
    @Getter
    private LinkedHashSet<Depth> depths;
    @Getter
    private LinkedHashSet<Metric> metrics;


    public void concat(@NotNull Market market) {
        if (!this.symbol.equals(market.symbol)) {
            throw new IllegalArgumentException("Symbols don't match");
        }
        this.trades.addAll(market.trades);
        this.depths.addAll(market.depths);
        this.candles.addAll(market.candles);
        this.metrics.addAll(market.metrics);
    }

    public synchronized void addTrade(Collection<Trade> trade) {
        Iterator<Trade> iterator = trade.iterator();
        while (iterator.hasNext()) {
            this.trades.add(iterator.next());
            iterator.remove();
        }
    }

    public synchronized void setTrade(LinkedHashSet<Trade> trades) {
        this.trades = trades;
    }

    public synchronized void addDepth(Depth tickMarker) {
        this.depths.add(tickMarker);
    }

    public synchronized void addCandles(Collection<Candle> candle) {
        Iterator<Candle> iterator = candle.iterator();
        while (iterator.hasNext()) {
            this.candles.add(iterator.next());
            iterator.remove();
        }
    }

    public synchronized void setCandles(LinkedHashSet<Candle> candle) {
        this.candles = candle;
    }

    public synchronized void addMetrics(Collection<Metric> metrics) {
        Iterator<Metric> iterator = metrics.iterator();
        while (iterator.hasNext()) {
            this.metrics.add(iterator.next());
            iterator.remove();
        }
    }

    public synchronized void setMetrics(LinkedHashSet<Metric> metrics) {
        this.metrics = metrics;
    }

    public synchronized void sortd(){
        int chunkSize = 10_000;
        trades = sortd(trades, chunkSize, Trade::time);
        depths = sortd(depths, chunkSize, Depth::getDate);
        candles = sortd(candles, chunkSize, Candle::getOpenTime);
        metrics = sortd(metrics, chunkSize, Metric::getOpenTime);
    }

    public interface TimeAccessor<T> {
        long time(T item);
    }

    @Contract(pure = true)
    public static <T> LinkedHashSet<T> sortd(Collection<T> source, int chunkSize, TimeAccessor<T> accessor) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashSet<>();
        }

        int safeChunkSize = Math.max(1, chunkSize);
        List<T> all = new ArrayList<>(source.size());
        Deque<T> batch = new ArrayDeque<>();

        for (Iterator<T> it = source.iterator(); it.hasNext(); ) {
            batch.add(it.next());
            it.remove();
            if (batch.size() >= safeChunkSize) {
                all.addAll(batch);
                batch.clear();
            }
        }
        // No debería quedar nada
        source.clear();
        if (!batch.isEmpty()) {
            all.addAll(batch);
            batch.clear();
        }

        all.sort(Comparator.comparingLong(accessor::time));

        LinkedHashSet<T> sorted = new LinkedHashSet<>(Math.max(16, (int) (all.size() / 0.75f) + 1));
        for (int i = 0; i < all.size(); i++) {
            sorted.add(all.get(i));
            all.set(i, null);
        }
        all.clear();
        return sorted;
    }

    @Getter private transient NavigableMap<Long, List<Trade>> tradesByTimeFrame;
    @Getter private transient NavigableMap<Long, Depth> depthByTimeFrame;
    @Getter private transient NavigableMap<Long, Metric> metricByTimeFrame;

    public void buildTradeCache() {
        if ((tradesByTimeFrame == null || tradesByTimeFrame.isEmpty()) && !trades.isEmpty()) {
            final LinkedHashMap<Long, List<Trade>> map = new LinkedHashMap<>();
            Iterator<Trade> it = trades.iterator();
            while (it.hasNext()) {
                Trade t = it.next();

                long minute = (t.time() / timeFrameMarket.getMilliseconds()) * timeFrameMarket.getMilliseconds();
                map.computeIfAbsent(minute, k -> new ArrayList<>(20)).add(t);
                it.remove();
            }
            tradesByTimeFrame = new TreeMap<>();
            tradesByTimeFrame.putAll(map);
        }
    }

    public void buildDepthCache() {
        if ((depthByTimeFrame == null || depthByTimeFrame.isEmpty()) && !depths.isEmpty()){
            NavigableMap<Long, Depth> depthByMinute = new TreeMap<>();
            for (Depth d : getDepths()) {
                // Depth llega con sello posterior al cierre de la vela; lo alineamos al minuto previo.
                long shifted = d.getDate() - timeFrameMarket.getMilliseconds();
                long minute = (Math.max(0L, shifted) / timeFrameMarket.getMilliseconds()) * timeFrameMarket.getMilliseconds();
                depthByMinute.put(minute, d);
            }
            depthByTimeFrame = new TreeMap<>();
            depthByTimeFrame.putAll(depthByMinute);
        }
    }

    public void buildMetricsCache() {
        if ((metricByTimeFrame == null || metricByTimeFrame.isEmpty()) && !metrics.isEmpty()) {
            long sourceMs = FIVE_MINUTE.getMilliseconds();
            long targetMs = timeFrameMarket.getMilliseconds();

            NavigableMap<Long, Metric> metricByFiveMinute = new TreeMap<>();
            for (Metric metric : metrics) {
                long aligned = (metric.getOpenTime() / sourceMs) * sourceMs;
                metricByFiveMinute.put(aligned, copyMetricWithTime(metric, aligned));
            }

            NavigableMap<Long, Metric> targetMap = new TreeMap<>();
            if (targetMs == sourceMs) {
                targetMap.putAll(metricByFiveMinute);
                metricByTimeFrame = targetMap;
                return;
            }

            if (targetMs > sourceMs) {
                Map<Long, MetricAccumulator> accumulators = new LinkedHashMap<>();
                for (Map.Entry<Long, Metric> entry : metricByFiveMinute.entrySet()) {
                    long bucket = (entry.getKey() / targetMs) * targetMs;
                    accumulators.computeIfAbsent(bucket, ignored -> new MetricAccumulator()).add(entry.getValue());
                }
                for (Map.Entry<Long, MetricAccumulator> entry : accumulators.entrySet()) {
                    targetMap.put(entry.getKey(), entry.getValue().buildAverage(entry.getKey()));
                }
            } else {
                long start = metricByFiveMinute.firstKey();
                long endExclusive = metricByFiveMinute.lastKey() + sourceMs;
                for (long time = start; time < endExclusive; time += targetMs) {
                    Map.Entry<Long, Metric> floor = metricByFiveMinute.floorEntry(time);
                    if (floor == null) {
                        continue;
                    }
                    if (time >= floor.getKey() + sourceMs) {
                        continue;
                    }
                    targetMap.put(time, copyMetricWithTime(floor.getValue(), time));
                }
            }
            metricByTimeFrame = targetMap;
        }
    }

    public List<Trade> getTradesInWindow(long startTime, long endTime) {
        if (tradesByTimeFrame == null) {
            throw new IllegalStateException("TradesByMinuteCache has not been initialized");
        }
        // Devuelve todos los trades que ocurrieron en ese minuto
        // subMap devuelve una vista, values() la colección, y flatMap las une
        return tradesByTimeFrame.subMap(startTime, true, endTime, false)
                .values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparingLong(Trade::time)) // Asegurar orden cronológico
                .toList();
    }

    public List<Candle> candleList = null;

    public List<Candle> cacheCandlesToArray() {
        if (candleList == null) {
            candleList = new ArrayList<>(candles);
        }
        return candleList;
    }

    public void resetCandleSimpleList() {
        candleList = null;
    }

    /**
     * Devuelve una copia del mercado equivalente a un subList por indice de minuto.
     * El indice se aplica sobre las velas 1m ordenadas por openTime.
     */
    @Contract(pure = true, value = "_, _ -> new")
    public synchronized @NotNull Market subList(int fromMinuteIndex, int toMinuteIndex) {
        List<Candle> orderedCandles = candles.stream()
                .sorted(Comparator.comparingLong(Candle::getOpenTime))
                .toList();

        int size = orderedCandles.size();
        int from = Math.max(0, fromMinuteIndex);
        int to = Math.min(size, toMinuteIndex);

        Market copy = new Market(symbol);
        if (from >= to) {
            return copy;
        }

        List<Candle> selectedCandles = orderedCandles.subList(from, to);
        long startTime = selectedCandles.getFirst().getOpenTime();
        long endTimeExclusive = selectedCandles.getLast().getOpenTime() + timeFrameMarket.getMilliseconds();

        copy.candles = new LinkedHashSet<>(selectedCandles);
        copy.trades = new LinkedHashSet<>(
                trades.stream()
                        .filter(t -> t.time() >= startTime && t.time() < endTimeExclusive)
                        .sorted(Comparator.comparingLong(Trade::time))
                        .toList()
        );
        copy.depths = new LinkedHashSet<>(
                depths.stream()
                        .filter(d -> d.getDate() >= startTime && d.getDate() < endTimeExclusive)
                        .sorted(Comparator.comparingLong(Depth::getDate))
                        .toList()
        );

        return copy;
    }

    public void clear(){
        trades.clear();
        depths.clear();
        candles.clear();
        metrics.clear();
        if (tradesByTimeFrame != null) tradesByTimeFrame.clear();
        if (depthByTimeFrame != null) depthByTimeFrame.clear();
        if (metricByTimeFrame != null) metricByTimeFrame.clear();
    }

    public double getFeedTaker(){
        if (symbol.isQuoteUSDT()) {
            return 0.0005;
        } else {
            return 0.0004;
        }
    }

    public double getFeedMaker(){
        if (symbol.isQuoteUSDT()) {
            return 0.0002;
        }else {
            return 0;
        }
    }

    public double getFeed(){
        return getFeedTaker() + getFeedMaker();
    }

    public double getFeedPercent(){
        return getFeed() *100;
    }

    private static @NotNull Metric copyMetricWithTime(@NotNull Metric source, long createTime) {
        return new Metric(
                createTime,
                source.getSumOpenInterest(),
                source.getSumOpenInterestValue(),
                source.getCountTopTradesLongShortRatio(),
                source.getCountTradesLongShortRatio()
        );
    }

    private static final class MetricAccumulator {
        private double sumOpenInterest;
        private double sumOpenInterestValue;
        private double countTopTradesLongShortRatio;
        private double countTradesLongShortRatio;
        private int count;

        void add(@NotNull Metric metric) {
            sumOpenInterest += metric.getSumOpenInterest();
            sumOpenInterestValue += metric.getSumOpenInterestValue();
            countTopTradesLongShortRatio += metric.getCountTopTradesLongShortRatio();
            countTradesLongShortRatio += metric.getCountTradesLongShortRatio();
            count++;
        }

        @NotNull Metric buildAverage(long createTime) {
            int divisor = Math.max(1, count);
            return new Metric(
                    createTime,
                    sumOpenInterest / divisor,
                    sumOpenInterestValue / divisor,
                    countTopTradesLongShortRatio / divisor,
                    countTradesLongShortRatio / divisor
            );
        }
    }
}
