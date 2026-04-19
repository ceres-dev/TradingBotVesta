package xyz.cereshost.vesta.core.strategy;

import xyz.cereshost.vesta.core.trading.TradingManager;

public interface TradingStrategyConfigurable {

    StrategyConfig getStrategyConfig(TradingManager tradingManager);
}
