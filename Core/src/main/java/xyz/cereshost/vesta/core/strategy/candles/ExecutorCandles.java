package xyz.cereshost.vesta.core.strategy.candles;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.trading.TradingManager;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public interface ExecutorCandles {

    @Contract(value = "_ -> this")
    ExecutorCandles pause(long milliseconds);

    @Contract(value = "_ -> this")
    ExecutorCandles setStep(String step);

    @Contract(value = "_ -> this")
    ExecutorCandles move(String step);

    @Contract(value = " -> this")
    ExecutorCandles died();

    @Contract(value = "_ -> this")
    ExecutorCandles execute(Consumer<TradingManager> consumer);

    @Contract(value = "_ -> this")
    ExecutorCandles executeReturnStep(Function<TradingManager, Optional<String>> function);

    void executeStack(TradingManager tradingManager);

    @Contract(value = " -> new", pure = true)
    static @NotNull ExecutorCandles empty() {
        return new ExecutorCandles(){
            @Override
            public ExecutorCandles pause(long milliseconds) {
                return this;
            }

            @Override
            public ExecutorCandles setStep(String step) {
                return this;
            }

            @Override
            public ExecutorCandles move(String step) {
                return this;
            }

            @Override
            public ExecutorCandles died() {
                return this;
            }

            @Override
            public ExecutorCandles execute(Consumer<TradingManager> consumer) {
                return this;
            }

            @Override
            public ExecutorCandles executeReturnStep(Function<TradingManager, Optional<String>> function) {
                return this;
            }

            @Override
            public void executeStack(TradingManager tradingManager) {

            }
        };
    }

}
