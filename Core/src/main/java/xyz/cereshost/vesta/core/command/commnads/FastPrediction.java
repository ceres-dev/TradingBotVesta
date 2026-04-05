package xyz.cereshost.vesta.core.command.commnads;

import ai.djl.Device;
import ai.djl.Model;
import ai.djl.util.Pair;
import xyz.cereshost.vesta.common.market.Market;
import xyz.cereshost.vesta.common.market.Symbol;
import xyz.cereshost.vesta.core.command.Arguments;
import xyz.cereshost.vesta.core.command.BaseCommand;
import xyz.cereshost.vesta.core.command.Flags;
import xyz.cereshost.vesta.core.ia.PredictionEngine;
import xyz.cereshost.vesta.core.ia.utils.XNormalizer;
import xyz.cereshost.vesta.core.ia.utils.YNormalizer;
import xyz.cereshost.vesta.core.io.IOMarket;
import xyz.cereshost.vesta.core.io.IOdata;
import xyz.cereshost.vesta.core.utils.BuilderData;
import xyz.cereshost.vesta.core.utils.Utils;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import java.util.List;

public class FastPrediction extends BaseCommand<PredictionEngine.SequenceCandlesPrediction> implements Flags {
    public FastPrediction(String description) {
        super(description);
    }

    @Override
    public PredictionEngine.SequenceCandlesPrediction execute(Arguments arguments) throws Exception {
        String nameDevice = arguments.getFlagString("device", "GPU").toUpperCase();
        Model model;
        switch (nameDevice) {
            case "GPU" -> model = IOdata.loadModel(Device.gpu());
            case "CPU" -> model = IOdata.loadModel(Device.cpu());
            default -> throw new IllegalArgumentException("Flags Device invalida");
        }
        Pair<XNormalizer, YNormalizer> pair = IOdata.loadNormalizers();
        PredictionEngine predictionEngine = new PredictionEngine(pair.getKey(), pair.getValue(), model);

        Symbol symbol = Symbol.valueOf(arguments.getFlagString("symbol", "SOLUSDC"));
        int limit = arguments.getFlagInteger("limit", 300);

        Market market = IOMarket.loadMarketsBinance(symbol, limit, limit, limit);
        SequenceCandles sequence = BuilderData.getProfierCandlesBuilder().build(market);

        return predictionEngine.predictNextPriceDetail(sequence);
    }

    @Override
    public List<Flag> getFlags() {
        return List.of(
                new Flag("device", TypeValue.STRING, "GPU", "CPU"),
                new Flag("symbol", TypeValue.STRING, Utils.enumsToStrings(Symbol.values())),
                new Flag("future", TypeValue.INTEGER),
                new Flag("limit", TypeValue.INTEGER)
        );
    }
}
