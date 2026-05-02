package xyz.cereshost.vesta.core.utils;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.core.trading.TradingManager;

@UtilityClass
public class StrategyUtils {

    @Contract(pure = true)
    public @NotNull Double getPricePercent(@NotNull Double currentPrice, @NotNull Double percent){
        return currentPrice + (currentPrice * (percent / 100));
    }

    @Contract(pure = true)
    public @NotNull Double getPricePercent(@NotNull TradingManager manager, @NotNull Double percent){
        return getPricePercent(manager.getCurrentPrice(), percent);
    }

}
