package xyz.cereshost.vesta.core.strategy.strategys;

import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.strategy.TradingStrategy;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.trading.TradingManager;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.ToDoubleFunction;

public class EtaStrategy implements TradingStrategy {

    private static final int EMA_LEN = 9;
    private static final double TP_PERCENT = 0.3;
    private static final double SL_PERCENT = 0.1;
    private static final int LEVERAGE = 4;
    private static final double ORDER_BALANCE_FRACTION = 0.5;

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final long START_TIME = LocalDate.of(2020, 7, 1)
            .atStartOfDay(ZONE).toInstant().toEpochMilli();
    private static final long END_TIME = LocalDate.of(2027, 12, 31)
            .atStartOfDay(ZONE).toInstant().toEpochMilli();

    @Override
    public void executeStrategy(PredictionEngine.@Nullable PredictionResult pred, List<Candle> visibleCandles, TradingManager operations) {
        if (visibleCandles == null || visibleCandles.size() < EMA_LEN) {
            return;
        }

        long currentTime = operations.getCurrentTime();
        boolean inDateRange = currentTime >= START_TIME && currentTime < END_TIME;

        boolean hasLong = false;
        boolean hasShort = false;
        for (TradingManager.OpenOperation open : operations.getOpens()) {
            if (open.getDireccion() == DireccionOperation.LONG) {
                hasLong = true;
            } else if (open.getDireccion() == DireccionOperation.SHORT) {
                hasShort = true;
            }
        }

        if (!inDateRange) {
            for (TradingManager.OpenOperation open : operations.getOpens()) {
                operations.close(TradingManager.ExitReason.STRATEGY, open);
            }
            return;
        }

        Candle current = visibleCandles.getLast();
        double close = current.close();
        if (!isFinite(close) || close <= 0.0) {
            return;
        }

        double emaHigh = computeEma(visibleCandles, EMA_LEN, Candle::high);
        double emaLow = computeEma(visibleCandles, EMA_LEN, Candle::low);
        if (!isFinite(emaHigh) || !isFinite(emaLow)) {
            return;
        }

        boolean buySignal = close > emaHigh;
        boolean sellSignal = close < emaLow;

        if (buySignal && !hasLong) {
            if (hasShort) {
                for (TradingManager.OpenOperation open : operations.getOpens()) {
                    if (open.getDireccion() == DireccionOperation.SHORT) {
                        operations.close(TradingManager.ExitReason.STRATEGY_INVERSION, open);
                    }
                }
            }
            open(operations, DireccionOperation.LONG);
        } else if (sellSignal && !hasShort) {
            if (hasLong) {
                for (TradingManager.OpenOperation open : operations.getOpens()) {
                    if (open.getDireccion() == DireccionOperation.LONG) {
                        operations.close(TradingManager.ExitReason.STRATEGY_INVERSION, open);
                    }
                }
            }
            open(operations, DireccionOperation.SHORT);
        }

    }

    private int StrikeLoess;

    @Override
    public void closeOperation(TradingManager.CloseOperation closeOperation, TradingManager operations) {

    }

    private void open(TradingManager operations, DireccionOperation direction) {
        double availableBalance = operations.getAvailableBalance();
        if (!isFinite(availableBalance) || availableBalance <= 0.0) {
            return;
        }
        double amount = availableBalance * ORDER_BALANCE_FRACTION;
        if (!isFinite(amount) || amount <= 0.0) {
            return;
        }
        operations.open(TP_PERCENT, SL_PERCENT, direction, amount, LEVERAGE);
    }

    private static double computeEma(List<Candle> candles, int length, ToDoubleFunction<Candle> value) {
        if (candles.size() < length) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (int i = 0; i < length; i++) {
            double v = value.applyAsDouble(candles.get(i));
            if (!isFinite(v)) return Double.NaN;
            sum += v;
        }
        double ema = sum / length;
        double alpha = 2.0 / (length + 1.0);
        for (int i = length; i < candles.size(); i++) {
            double v = value.applyAsDouble(candles.get(i));
            if (!isFinite(v)) return Double.NaN;
            ema = ema + alpha * (v - ema);
        }
        return ema;
    }

    private static boolean isFinite(double v) {
        return Double.isFinite(v);
    }
}
