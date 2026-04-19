package xyz.cereshost.vesta.core.ia;

import ai.djl.Device;
import ai.djl.Model;
import ai.djl.engine.Engine;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.*;
import ai.djl.nn.core.Linear;
import ai.djl.nn.recurrent.LSTM;
import ai.djl.pytorch.engine.PtModel;
import ai.djl.pytorch.engine.PtNDManager;
import ai.djl.pytorch.jni.JniUtils;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.EasyTrain;
import ai.djl.training.Trainer;
import ai.djl.training.TrainingConfig;
import ai.djl.training.dataset.ArrayDataset;
import ai.djl.training.initializer.Initializer;
import ai.djl.training.listener.EpochTrainingListener;
import ai.djl.training.listener.EvaluatorTrainingListener;
import ai.djl.training.listener.LoggingTrainingListener;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.training.tracker.Tracker;
import ai.djl.util.Pair;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.common.market.TypeMarket;
import xyz.cereshost.vesta.core.Main;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.core.ia.blocks.TemporalTransformerBlock;
import xyz.cereshost.vesta.core.trading.backtest.BackTestEngine;
import xyz.cereshost.vesta.core.io.IOdata;
import xyz.cereshost.vesta.core.ia.metrics.MAEEvaluator;
import xyz.cereshost.vesta.core.ia.metrics.MetricsListener;
import xyz.cereshost.vesta.core.utils.BuilderData;
import xyz.cereshost.vesta.core.ia.utils.EngineUtils;
import xyz.cereshost.vesta.core.ia.utils.TrainingData;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;

public class VestaEngine {

    public static final int LOOK_BACK = 48;
    public static final int SHORT_LOOK_BACK = 4;
    public static final int AUXILIAR_EPOCH = 1;
    public static final int BACH_SIZE = 4;
    public static final int SPLIT_DATA = 1;
    public static final int EPOCH = SPLIT_DATA * 70;

    @Getter @Setter
    private static NDManager rootManager;

    public static final ExecutorService EXECUTOR_BUILD = Executors.newScheduledThreadPool(6);
    public static final ExecutorService EXECUTOR_AUXILIAR_BUILD = Executors.newScheduledThreadPool(10);
    public static final ExecutorService EXECUTOR_READ_CACHE_BUILD = Executors.newScheduledThreadPool(2);
    public static final ExecutorService EXECUTOR_WRITE_CACHE_BUILD = Executors.newScheduledThreadPool(6);
    public static final ExecutorService EXECUTOR_TRAINING = Executors.newScheduledThreadPool(8);

    private static int countEpoch = 0;

    /**
     * Entrena un modelo con múltiples símbolos combinados
     */
    @SuppressWarnings("UnusedAssignment")
    public static void trainingModel(@NotNull List<TypeMarket> typeMarket) throws IOException {
        Engine torch = Engine.getEngine("PyTorch");
        JniUtils.setGraphExecutorOptimize(false);

        if (torch == null) {
            Vesta.error("PyTorch no está disponible. Engines disponibles:");
            for (String engine : Engine.getAllEngines()) {
                Vesta.error("  - " + engine);
            }
            throw new RuntimeException("PyTorch engine no encontrado");
        }
        Device device = Engine.getInstance().getDevices()[0];
        Vesta.info("Usando dispositivo: " + device);
        Vesta.info("Entrenando con " + typeMarket.size() + " símbolos: " + typeMarket);

        try (PtModel model = (PtModel) Model.newInstance(Main.NAME_MODEL, device, "PyTorch")) {
            PtNDManager manager = (PtNDManager) model.getNDManager();

            final TrainingData data;
            if (IOdata.isBuiltData(typeMarket)){
                data = IOdata.getBuiltData(typeMarket);
            }else {
                data = BuilderData.buildTrainingData(typeMarket, Main.MAX_MONTH_TRAINING, 0, BuilderData.getProfierCandlesBuilder());
                IOdata.saveCacheProperties(data.getCacheProperties(typeMarket));

            }
            data.prepareNormalize();

            IOdata.saveYNormalizer(data.getYNormalizer());
            IOdata.saveXNormalizer(data.getXNormalizer());

            Vesta.info("Datos combinados:");
            Vesta.info("  Total de muestras: " + data.getSamplesSize());
            Vesta.info("  Lookback: " + data.getLookback());
            Vesta.info("  Características: " + data.getFeatures());

            System.gc();

            // Borra por que no se va a usar más
            Vesta.MARKETS.clear();

            model.setBlock(getSequentialBlock());

            int maxMonthTraining = Main.MAX_MONTH_TRAINING - 1;
            int maxUpdates = estimateMaxUpdates(data, BACH_SIZE, maxMonthTraining);

            Vesta.info("Split sizes: train=" + data.getTrainSize() + " val=" + data.getValSize() + " test=" + data.getTestSize());
            Vesta.info("Max Updates: %,d~", maxUpdates);

            // Configuración de entrenamiento
            TrainingConfig config = new DefaultTrainingConfig(new VestaLoss())
                    .optOptimizer(Optimizer.adamW()
                            .optLearningRateTracker(Tracker.cosine()
                                    .setBaseValue(.000_1f)
                                    .optFinalValue(.000_001f)
                                    .setMaxUpdates((int) (maxUpdates*.5f))
                                    .build())
                            .optWeightDecays(0)
                            .build())
                    .optDevices(Engine.getInstance().getDevices())
//                    .addEvaluator(new MAEEvaluator())
                    .optInitializer(Initializer.ZEROS, Parameter.Type.BETA)
                    .optExecutorService(EXECUTOR_TRAINING)
                    .addTrainingListeners(
                            new EpochTrainingListener(),
                            new EvaluatorTrainingListener(),
                            new LoggingTrainingListener()
                    )
                    .addTrainingListeners(new MetricsListener());
//                    .addTrainingListeners(new AutoStopListener());

            int batchSize = BACH_SIZE;
            Trainer trainer = model.newTrainer(config);
            trainer.initialize(new Shape(batchSize, LOOK_BACK, data.getFeatures()));
            // Entrenar
            long totalParams = 0;
            for (Parameter p : model.getBlock().getParameters().values()) {
                totalParams += p.getArray().getShape().size();
            }
            model.setProperty("lookback", String.valueOf(LOOK_BACK));
            model.setProperty("features", String.valueOf(BuilderData.FEATURES));
            Vesta.info("🧠 Total de parámetros: %,d", totalParams);
            Vesta.info("Iniciando entrenamiento con " + EPOCH* AUXILIAR_EPOCH *maxMonthTraining + " epochs...");
            rootManager = manager;
            NDManager managerTraining = manager.newSubManager();
            System.gc();
            data.preLoad(3, TrainingData.ModeData.RAMDOM, SPLIT_DATA);
            ChunkDataset sampleTraining = computeDataset(data.nextTrainingData(), batchSize, managerTraining);
            ChunkDataset sampleVal = computeDataset(data.nextValidationData(), 64, managerTraining);

//            ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
//            executorService.scheduleAtFixedRate(() -> {
//                try {
//                    IOdata.saveModel(model);
//                } catch (IOException e) {
//                    throw new RuntimeException(e);
//                }
//            }, 4, 4, TimeUnit.MINUTES);


            try{
                for (int epoch = 0; epoch < EPOCH; epoch++) {
                    for (int idx = 0; idx < maxMonthTraining; idx++) {
                        CompletableFuture<ChunkDataset> sampleTrainingNext = CompletableFuture.supplyAsync(() ->
                                computeDataset(data.nextTrainingData(), batchSize, managerTraining), EXECUTOR_AUXILIAR_BUILD);
                        CompletableFuture<ChunkDataset> sampleValNext = CompletableFuture.supplyAsync(() ->
                                computeDataset(data.nextValidationData(), batchSize, managerTraining), EXECUTOR_AUXILIAR_BUILD);

                        EasyTrain.fit(trainer, AUXILIAR_EPOCH, sampleTraining.dataset(), sampleVal.dataset());
                        NDArray xT = sampleTraining.x();
                        NDArray yT = sampleTraining.y();
                        NDArray xV = sampleVal.x();
                        NDArray yV = sampleVal.y();
                        countEpoch++;
                        EXECUTOR_TRAINING.submit(() -> {
                            xT.close();
                            yT.close();
                            xV.close();
                            yV.close();
                            EngineUtils.clearCacheFloats();
                        });
                        if(!sampleTrainingNext.isDone() || !sampleValNext.isDone()) Vesta.warning("Mes no listo procesando...");
                        sampleTraining = sampleTrainingNext.get();
                        sampleTrainingNext = null;
                        sampleVal = sampleValNext.get();
                        sampleValNext = null;
                        if (stop) break;
                        //sampleVal = sampleValNext.get();
                    }
                    if (stop) break;
                }
            }catch (Exception e){
                e.printStackTrace();
            }
            stop = true;
            managerTraining.close();
            data.closePosTraining();
            System.gc();
            // Guardar modelo (igual que antes)
            IOdata.saveModel(model);

//            Pair<float[][][], float[][]> pairTest = data.getTestNormalize();
//
//            NDArray X_test  = EngineUtils.concat3DArrayToNDArray(pairTest.getKey(), manager, 1024);
//            NDArray y_test  = manager.create(pairTest.getValue());
//            // Evaluar en conjunto de test si hay muestras
//            Vesta.info("\nEvaluando modelo con Backtest Walk-Forward (15% data)...");
//            data.closeAll();
//
//            EngineUtils.ResultsEvaluate evaluate = EngineUtils.evaluateModel(trainer, X_test, y_test, data.getYNormalizer(), 1024);
//            PredictionEngine predEngine = new PredictionEngine(
//                    data.getXNormalizer(),
//                    data.getYNormalizer(),
//                    model,
//                    data.getLookback(),
//                    data.getFeatures()
//            );
//            Market market = new Market(symbols.getFirst());
//            for (int day = 12; day >= 1; day--) {
//                market.concat(IOMarket.loadMarkets(Main.DATA_SOURCE_FOR_BACK_TEST, symbols.getFirst(), day));
//            }
//
//            BackTestEngine.BackTestResult simResult;
//            simResult = new BackTestEngine(market, predEngine).run();
//            manager.close();
//            return new TrainingTestsResults(evaluate, simResult);
        }
    }
    public record TrainingTestsResults(EngineUtils.ResultsEvaluate evaluate, BackTestEngine.BackTestResult backtest) {}

    @SuppressWarnings("DuplicatedCode")
    public static @NotNull SequentialBlock getSequentialBlock() {
        SequentialBlock mainBlock = new SequentialBlock();

        TTLHeader(mainBlock);
        mainBlock.add(Linear.builder().setUnits(1024*2).build());

        ParallelBlock branches = new ParallelBlock(list -> {
            NDArray out0 = list.get(0).singletonOrThrow();
            NDArray out1 = list.get(1).singletonOrThrow();
            NDArray out2 = list.get(2).singletonOrThrow();
            NDArray out3 = list.get(3).singletonOrThrow();
            NDArray out4 = list.get(4).singletonOrThrow();
            return new NDList(
                    NDArrays.concat(new NDList(out0, out1, out2, out3, out4), 1)
            );
        });

        branches.add(getMagnitud());   // output 0: Close
        branches.add(getZeroHead());   // output 1: high
        branches.add(getZeroHead());   // output 2: low
        branches.add(getZeroHead());   // output 3: volumen
        branches.add(getZeroHead());   // output 4: Otro (Indicador)

        mainBlock.add(branches);
        return mainBlock;
    }

    private static SequentialBlock getMagnitud() {
//        ParallelBlock branches = new ParallelBlock(list -> {
//            NDArray linearBlock = list.get(0).singletonOrThrow();
//            NDArray cosBLock = list.get(1).singletonOrThrow();
//            return new NDList(
//                    NDArrays.concat(new NDList(linearBlock, cosBLock), 1) // axis = 1
//            );
//        });
//
//        branches.add(Linear.builder().setUnits(64).build())
//                .add(Linear.builder().setUnits(64).build())
//                .add(Linear.builder().setUnits(64).build())
//                .add(Linear.builder().setUnits(32).build());
//
//        branches.add(Linear.builder().setUnits(64).build())
//                .add(CosLinear.builder().build())
//                .add(Linear.builder().setUnits(64).build())
//                .add(CosLinear.builder().build())
//                .add(Linear.builder().setUnits(32).build());

        return new SequentialBlock()
                .add(Linear.builder().setUnits(1024*2).build())
                .add(Linear.builder().setUnits(1024*2).build())
                .add(Linear.builder().setUnits(1024).build())
                .add(Linear.builder().setUnits(1024).build())
                .add(Linear.builder().setUnits(1024).build())
                .add(Linear.builder().setUnits(1024).build())
                .add(Linear.builder().setUnits(1).build());
    }

    private static SequentialBlock getZeroHead() {
        return new SequentialBlock()
                .add(new LambdaBlock(ndArrays -> {
                    NDArray x = ndArrays.singletonOrThrow();
                    return new NDList(x.getManager().zeros(new Shape(x.getShape().get(0), 1)));
                }));
    }

    private static void TTLHeader(SequentialBlock mainBlock) {
        ParallelBlock dualLookback = new ParallelBlock(list -> {
            NDArray longFeatures = list.get(0).singletonOrThrow();   // [B, 2H]
            NDArray shortFeatures = list.get(1).singletonOrThrow();  // [B, 2H]
            NDArray merged = NDArrays.concat(new NDList(longFeatures, shortFeatures), 1); // [B, 4H]
            return new NDList(merged);
        });

        dualLookback.add(buildTemporalSummaryBlock());
        dualLookback.add(buildLSTMSummaryBlock());

        mainBlock.add(dualLookback);
    }

    private static SequentialBlock buildTemporalSummaryBlock() {
        SequentialBlock block = new SequentialBlock();
        block.add(new LambdaBlock(ndArrays -> {
            NDArray seq = ndArrays.singletonOrThrow(); // [B, T, F]
            long totalSteps = seq.getShape().get(1);
            long start = Math.max(0, totalSteps - VestaEngine.SHORT_LOOK_BACK);
            NDArray recent = seq.get(":, " + start + ":, :");
            return new NDList(recent);
        }));
        block.add(TemporalTransformerBlock.builder()
                        .setModelDim(2*32)
                        .setNumHeads(2)
                        .setFeedForwardDim(1024*2)
                        .setDropoutRate(0)
                        .setAttentionProbsDropoutProb(.05f)
                        .setMaxSequenceLength(VestaEngine.SHORT_LOOK_BACK)
                        .build())
                .add(new LambdaBlock(ndArrays -> {
                    NDArray seq = ndArrays.singletonOrThrow();  // [B, T, H]
                    NDArray last = seq.get(":, -1, :");         // [B, H]
                    NDArray mean = seq.mean(new int[]{1});      // [B, H]

                    NDArray combined = NDArrays.concat(
                            new NDList(last, mean),
                            1 // concat en features
                    ); // [B, 2H]
                    return new NDList(combined);
                }));
        return block;
    }

    private static SequentialBlock buildLSTMSummaryBlock() {
        SequentialBlock block = new SequentialBlock();
        block.add(TemporalTransformerBlock.builder()
                        .setModelDim(8*(64))
                        .setNumHeads(8)
                        .setFeedForwardDim(1024)
                        .setDropoutRate(0)
                        .setAttentionProbsDropoutProb(.05f)
                        .setMaxSequenceLength(VestaEngine.LOOK_BACK)
                        .build())
                .add(new LambdaBlock(ndArrays -> {
                    NDArray seq = ndArrays.singletonOrThrow();  // [B, T, H]
                    NDArray last = seq.get(":, -1, :");         // [B, H]
                    NDArray mean = seq.mean(new int[]{1});      // [B, H]

                    NDArray combined = NDArrays.concat(
                            new NDList(last, mean),
                            1 // concat en features
                    ); // [B, 2H]
                    return new NDList(combined);
                }));
        return block;
    }

    private static ChunkDataset computeDataset(Pair<float[][][], float[][]> pairNormalize, int batchSize, NDManager manager) {
        NDArray X_train = EngineUtils.concat3DArrayToNDArray(pairNormalize.getKey(), manager, 1024);
        NDArray y_train = manager.create(pairNormalize.getValue());
//        Arrays.fill(pairNormalize.getValue(), null);
//        Arrays.fill(pairNormalize.getKey(), null);
//        System.err.println(Arrays.deepToString(pairNormalize.getValue()));
        int i = (countEpoch/100)*2;
        return new ChunkDataset(X_train, y_train, new ArrayDataset.Builder()
                .setData(X_train)
                .optLabels(y_train)
                .setSampling(batchSize + i, false)
                .build());
    }

    private static int estimateMaxUpdates(TrainingData data, int batchSize, int maxMonthTraining) {
        long trainSize = data.getTrainSize();
        int splitParts = Math.max(1, SPLIT_DATA);
        long samplesPerChunk = (trainSize + splitParts - 1) / splitParts;
        long stepsPerChunk = (samplesPerChunk + batchSize - 1) / batchSize;
        long totalUpdates = stepsPerChunk * (long) maxMonthTraining * (long) EPOCH * (long) AUXILIAR_EPOCH;
        if (totalUpdates < 1) return 1;
        return Math.toIntExact(trainSize / batchSize) * EPOCH/SPLIT_DATA; //totalUpdates > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalUpdates;
    }

    private static boolean stop = false;

    public static void stopTraining(){
        Vesta.info("⛔ Deteniendo el entrenamiento");
        stop = true;
    }

    private record ChunkDataset(NDArray x, NDArray y, ArrayDataset dataset) {}
}
