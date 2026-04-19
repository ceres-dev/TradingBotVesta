package xyz.cereshost.vesta.core.strategy;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.strategy.candles.ExecutorCandles;
import xyz.cereshost.vesta.core.trading.TradingManager;

public interface TradingStrategyExecutor {

    @NotNull ExecutorCandles getExecutorCandles(@NotNull TradingManager tradingManager);
}
