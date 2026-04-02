package xyz.cereshost.vesta.core.strategy;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

@Getter
@Setter
@RequiredArgsConstructor
public class StrategyConfig {

    @Nullable
    private final HowUseIA howUseIA;
    private final int futurePredict;
    private final int lookBack;


    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private HowUseIA howUseIA;
        private int futurePredict = 20;
        private int lookBack = 250;


        private Builder() {}

        @Contract(value = "_ -> this")
        public Builder condicionUseModelIA(HowUseIA howUseIA) {
            this.howUseIA = howUseIA;
            return this;
        }

        @Contract(value = "_ -> this")
        public Builder futurePredict(Integer futurePredict) {
            this.futurePredict = futurePredict;
            return this;
        }

        @Contract(value = "_ -> this")
        public Builder lookBack(int lookBack) {
            this.lookBack = lookBack;
            return this;
        }

        public StrategyConfig build() {
            return new StrategyConfig(howUseIA, futurePredict, lookBack);
        }


    }

    public interface HowUseIA{
        boolean useModelIA();
    }
}
