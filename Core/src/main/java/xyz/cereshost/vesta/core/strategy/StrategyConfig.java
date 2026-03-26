package xyz.cereshost.vesta.core.strategy;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Getter
@Setter
@RequiredArgsConstructor
public class StrategyConfig {

    @Nullable
    private final HowUseIA howUseIA;


    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private HowUseIA howUseIA;


        private Builder() {}

        @Contract(value = "_ -> this")
        public Builder condicionUseModelIA(HowUseIA howUseIA) {
            this.howUseIA = howUseIA;
            return this;
        }
        public StrategyConfig build() {
            return new StrategyConfig(howUseIA);
        }
    }

    public interface HowUseIA{
        boolean useModelIA();
    }
}
