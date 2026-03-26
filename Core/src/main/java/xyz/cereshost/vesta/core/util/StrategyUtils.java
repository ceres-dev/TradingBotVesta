package xyz.cereshost.vesta.core.util;

import lombok.experimental.UtilityClass;
import xyz.cereshost.vesta.common.market.Candle;

import java.util.ArrayList;
import java.util.List;

@UtilityClass
public class StrategyUtils {

    public record BosChochSignal(
            boolean valid,
            boolean bos,
            boolean choch,
            int direction,          // 1 long, -1 short, 0 neutral
            int previousStructure,  // 1 bullish, -1 bearish, 0 neutral
            int pivotIndex,
            double pivotPrice
    ) {
        public static BosChochSignal none() {
            return new BosChochSignal(false, false, false, 0, 0, -1, Double.NaN);
        }
    }

    public static boolean isPeek(List<Candle> visibleCandles, int lookback) {
        return isHigh(visibleCandles, lookback) || isLow(visibleCandles, lookback);
    }

    /**
     * Indica si la vela actual está en el máximo de la última hora (60 velas de 1m).
     */
    public static boolean isHigh(List<Candle> candles, int lookback) {
        if (candles == null || candles.isEmpty()) {
            return false;
        }
        double lastPrice = candles.getLast().highBody();
        for (int i = candles.size() - 2; i >= lookback; i--) {
            if (lastPrice < candles.get(i).highBody()) return false;
        }
        return true;
    }

    /**
     * Indica si la vela actual está en el mínimo de la última hora (60 velas de 1m).
     */
    public static boolean isLow(List<Candle> candles, int lookback) {
        if (candles == null || candles.isEmpty()) {
            return false;
        }
        double lastPrice = candles.getLast().lowBody();
        for (int i = candles.size() - 2; i >= candles.size() - lookback; i--) {
            if (lastPrice > candles.get(i).lowBody()) return false;
        }
        return true;
    }

    public static BosChochSignal detectBosChoch(
            List<Candle> candles,
            int pivotLeft,
            int pivotRight,
            int lookback,
            double minBreakPercent
    ) {
        if (candles == null || candles.size() < (pivotLeft + pivotRight + 6)) {
            return BosChochSignal.none();
        }

        int last = candles.size() - 1;
        int from = Math.max(pivotLeft, last - lookback - pivotRight);
        int to = last - pivotRight;
        if (to <= from) {
            return BosChochSignal.none();
        }

        List<Integer> highs = new ArrayList<>();
        List<Integer> lows = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            if (isPivotHigh(candles, i, pivotLeft, pivotRight)) highs.add(i);
            if (isPivotLow(candles, i, pivotLeft, pivotRight)) lows.add(i);
        }

        if (highs.isEmpty() || lows.isEmpty()) {
            return BosChochSignal.none();
        }

        int lastHighIdx = highs.getLast();
        int lastLowIdx = lows.getLast();
        int prevHighIdx = highs.size() > 1 ? highs.get(highs.size() - 2) : -1;
        int prevLowIdx = lows.size() > 1 ? lows.get(lows.size() - 2) : -1;

        int previousStructure = resolveStructure(candles, prevHighIdx, lastHighIdx, prevLowIdx, lastLowIdx);

        double close = candles.getLast().close();
        if (!Double.isFinite(close) || close <= 0.0) {
            return BosChochSignal.none();
        }

        double breakFactor = Math.max(0.0, minBreakPercent) / 100.0;
        double upLevel = candles.get(lastHighIdx).high() * (1.0 + breakFactor);
        double downLevel = candles.get(lastLowIdx).low() * (1.0 - breakFactor);

        boolean breakUp = close > upLevel;
        boolean breakDown = close < downLevel;
        if (breakUp == breakDown) {
            System.out.println(close + " " + upLevel + "  " + downLevel + " | " + breakUp + " " + breakDown);
            return BosChochSignal.none();
        }

        int direction = breakUp ? 1 : -1;
        int pivotIndex = breakUp ? lastHighIdx : lastLowIdx;
        double pivotPrice = breakUp ? candles.get(lastHighIdx).high() : candles.get(lastLowIdx).low();
        boolean choch = previousStructure != 0 && direction != previousStructure;
        boolean bos = !choch;

        return new BosChochSignal(true, bos, choch, direction, previousStructure, pivotIndex, pivotPrice);
    }

    public static boolean isPivotHigh(List<Candle> candles, int idx, int pivotLeft, int pivotRight) {
        if (candles == null || idx < 0 || idx >= candles.size()) return false;
        if (idx - pivotLeft < 0 || idx + pivotRight >= candles.size()) return false;

        double pivot = candles.get(idx).high();
        if (!Double.isFinite(pivot)) return false;

        for (int i = idx - pivotLeft; i <= idx + pivotRight; i++) {
            if (i == idx) continue;
            double other = candles.get(i).high();
            if (!Double.isFinite(other) || other >= pivot) return false;
        }
        return true;
    }

    public static boolean isPivotLow(List<Candle> candles, int idx, int pivotLeft, int pivotRight) {
        if (candles == null || idx < 0 || idx >= candles.size()) return false;
        if (idx - pivotLeft < 0 || idx + pivotRight >= candles.size()) return false;

        double pivot = candles.get(idx).low();
        if (!Double.isFinite(pivot)) return false;

        for (int i = idx - pivotLeft; i <= idx + pivotRight; i++) {
            if (i == idx) continue;
            double other = candles.get(i).low();
            if (!Double.isFinite(other) || other <= pivot) return false;
        }
        return true;
    }

    private static int resolveStructure(List<Candle> candles, int prevHighIdx, int lastHighIdx, int prevLowIdx, int lastLowIdx) {
        if (prevHighIdx < 0 || prevLowIdx < 0 || lastHighIdx < 0 || lastLowIdx < 0) return 0;

        double prevHigh = candles.get(prevHighIdx).high();
        double lastHigh = candles.get(lastHighIdx).high();
        double prevLow = candles.get(prevLowIdx).low();
        double lastLow = candles.get(lastLowIdx).low();

        if (!Double.isFinite(prevHigh) || !Double.isFinite(lastHigh) || !Double.isFinite(prevLow) || !Double.isFinite(lastLow)) {
            return 0;
        }

        if (lastHigh > prevHigh && lastLow > prevLow) return 1;
        if (lastHigh < prevHigh && lastLow < prevLow) return -1;
        return 0;
    }

    public static double[] buildCloseSeries(List<Candle> candles, boolean applyEma, int emaPeriod) {
        int n = candles.size();
        double[] src = new double[n];
        for (int i = 0; i < n; i++) {
            src[i] = candles.get(i).close();
        }
        if (applyEma) {
            applyEmaInPlace(src, emaPeriod);
        }
        return src;
    }

    public static void applyEmaInPlace(double[] values, int period) {
        if (values.length < period || period <= 0) {
            return;
        }
        double sum = 0.0;
        for (int i = 0; i < period; i++) {
            sum += values[i];
        }
        double ema = sum / period;
        values[period - 1] = ema;
        double alpha = 2.0 / (period + 1.0);
        for (int i = period; i < values.length; i++) {
            double v = values[i];
            ema = alpha * (v - ema) + ema;
            values[i] = ema;
        }
    }

    public static double highest(double[] values, int endIndex, int length) {
        if (endIndex < length - 1) {
            return Double.NaN;
        }
        double max = -Double.MAX_VALUE;
        for (int i = endIndex - length + 1; i <= endIndex; i++) {
            double v = values[i];
            if (!Double.isFinite(v)) {
                return Double.NaN;
            }
            if (v > max) {
                max = v;
            }
        }
        return max;
    }

    public static double highestHigh(List<Candle> candles, int endIndex, int length) {
        if (candles == null || endIndex < length - 1 || endIndex >= candles.size()) {
            return Double.NaN;
        }
        double max = -Double.MAX_VALUE;
        for (int i = endIndex - length + 1; i <= endIndex; i++) {
            double v = candles.get(i).high();
            if (!Double.isFinite(v)) {
                return Double.NaN;
            }
            if (v > max) {
                max = v;
            }
        }
        return max;
    }

    public static double lowest(double[] values, int endIndex, int length) {
        if (endIndex < length - 1) {
            return Double.NaN;
        }
        double min = Double.MAX_VALUE;
        for (int i = endIndex - length + 1; i <= endIndex; i++) {
            double v = values[i];
            if (!Double.isFinite(v)) {
                return Double.NaN;
            }
            if (v < min) {
                min = v;
            }
        }
        return min;
    }

    public static double lowestLow(List<Candle> candles, int endIndex, int length) {
        if (candles == null || endIndex < length - 1 || endIndex >= candles.size()) {
            return Double.NaN;
        }
        double min = Double.MAX_VALUE;
        for (int i = endIndex - length + 1; i <= endIndex; i++) {
            double v = candles.get(i).low();
            if (!Double.isFinite(v)) {
                return Double.NaN;
            }
            if (v < min) {
                min = v;
            }
        }
        return min;
    }

    public static boolean crossover(double prev, double curr, double level) {
        return prev <= level && curr > level;
    }

    public static boolean crossunder(double prev, double curr, double level) {
        return prev >= level && curr < level;
    }

    public static boolean cross(double prev, double curr, double level) {
        return crossover(prev, curr, level) || crossunder(prev, curr, level);
    }

    public static double computeAtrRma(List<Candle> candles, int endIndex, int length) {
        if (candles == null || endIndex <= 0 || length <= 0 || endIndex >= candles.size()) {
            return Double.NaN;
        }

        double[] tr = new double[endIndex + 1];
        for (int i = 0; i <= endIndex; i++) {
            Candle curr = candles.get(i);
            Candle prev = i > 0 ? candles.get(i - 1) : curr;
            double highLow = curr.high() - curr.low();
            double highClose = Math.abs(curr.high() - prev.close());
            double lowClose = Math.abs(curr.low() - prev.close());
            tr[i] = Math.max(highLow, Math.max(highClose, lowClose));
        }
        return computeRmaFromSeries(tr, endIndex, length);
    }

    public static double computeRmaFromSeries(double[] values, int endIndex, int length) {
        if (endIndex < length - 1) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (int i = 0; i < length; i++) {
            double v = values[i];
            if (!Double.isFinite(v)) {
                return Double.NaN;
            }
            sum += v;
        }
        double rma = sum / length;
        double alpha = 1.0 / length;
        for (int i = length; i <= endIndex; i++) {
            double v = values[i];
            if (!Double.isFinite(v)) {
                return Double.NaN;
            }
            rma = alpha * (v - rma) + rma;
        }
        return rma;
    }

    public static double priceDistanceToPercent(double referencePrice, double distance) {
        if (!Double.isFinite(referencePrice) || referencePrice <= 0.0 || !Double.isFinite(distance) || distance <= 0.0) {
            return Double.NaN;
        }
        return (distance / referencePrice) * 100.0;
    }

}
