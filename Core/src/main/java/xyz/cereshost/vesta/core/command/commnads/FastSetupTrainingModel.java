package xyz.cereshost.vesta.core.command.commnads;

import xyz.cereshost.vesta.core.market.Symbol;
import xyz.cereshost.vesta.core.market.SymbolFutures;
import xyz.cereshost.vesta.core.market.TimeFrameMarket;
import xyz.cereshost.vesta.core.market.TypeMarket;
import xyz.cereshost.vesta.core.command.Arguments;
import xyz.cereshost.vesta.core.command.BaseCommand;
import xyz.cereshost.vesta.core.command.Flags;
import xyz.cereshost.vesta.core.ia.VestaEngine;
import xyz.cereshost.vesta.core.utils.Utils;

import java.util.ArrayList;
import java.util.List;

public class FastSetupTrainingModel extends BaseCommand implements Flags {
    public FastSetupTrainingModel() {
        super("Entrenas un modelo nuevo");
        addAlias("fstm");
    }

    @Override
    public void execute(Arguments arguments) throws Exception {
        List<Symbol> symbols = new ArrayList<>();
        for (String nameSymbols : arguments.getFlagString("symbol", "SOLUSDC").split(",")) {
            symbols.add(Symbol.valueOf(nameSymbols));
        }
        TimeFrameMarket timeFrameMarket = TimeFrameMarket.parse(arguments.getFlagString("timeframe", "1m"));
        List<TypeMarket> typeMarkets = symbols.stream().map(s -> new TypeMarket(s, timeFrameMarket)).toList();
        VestaEngine.trainingModel(typeMarkets);
    }

    @Override
    public List<Flag> getFlags() {
        return List.of(
                new Flag("symbol", TypeValue.STRING, Utils.enumsToStrings(SymbolFutures.values())),
                new Flag("timeframe", TypeValue.STRING)
        );
    }
}
