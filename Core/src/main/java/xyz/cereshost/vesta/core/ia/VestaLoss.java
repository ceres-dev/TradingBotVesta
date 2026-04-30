package xyz.cereshost.vesta.core.ia;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.training.loss.Loss;
import lombok.SneakyThrows;
import xyz.cereshost.vesta.core.ia.utils.EngineUtils;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public class VestaLoss extends Loss {

    public record LossReport(float total, float closes, float high, float low, float meanReal, float meanPred) {}

    private static final float RELATIVE_WEIGHT_ALPHA = 2.5f;
    private static final float DIRECTION_PENALTY_EXTRA = 1.0f;
    private static final float DIRECTION_PENALTY_K = 5.0f;

    // Debug: fuerza target constante para up/down/firstHit

    private volatile CompletableFuture<LossReport> dataRequest = null;

    public VestaLoss() {
        super("Vesta Loss");
    }

    @SneakyThrows
    @Override
    public NDArray evaluate(NDList target, NDList prediction) {
        NDArray yPred = prediction.singletonOrThrow();
        NDArray yTrue = target.singletonOrThrow();

        // 1. Cálculos vectorizados rápidos (GPU)
//        NDList trueParts = yTrue.split(new long[]{1, 2}, 1);
//        NDList predParts = yPred.split(new long[]{1, 2, 3, 4}, 1);

        NDArray lossCloses = computeError(yTrue.get(":, 0"), yPred.get(":, 0"));
//        NDArray lossHigh = computeDistance(yTrue.get(":, 1"),yPred.get(":, 1"));
//        NDArray lossLow = computeDistance(trueParts.get(2), predParts.get(2));
//        NDArray lossVolumen = computeDistance(trueParts.get(3), predParts.get(3));
//        NDArray lossMAE = computeDistance(trueParts.get(4), predParts.get(4));

        NDArray totalLoss = lossCloses;//.add(lossHigh);//.add(lossLow).add(lossVolumen).add(lossMAE);
        CompletableFuture<LossReport> request = dataRequest;
        if (request != null && !request.isDone()) {

            // Solo aquí pagamos el costo de sincronización GPU -> CPU
            request.complete(new LossReport(
                    totalLoss.getFloat(),
                    lossCloses.getFloat(),
                    0,
                    0,//lossLow.getFloat(),
                    yTrue.mean().getFloat(),
                    yPred.mean().getFloat()
            ));
            dataRequest = null;
        }
        return totalLoss;
    }

    public NDArray computeError(NDArray trueND, NDArray predND) {
        // Error absoluto base
        NDArray absError = trueND.sub(predND).abs();

        // Detectar signo: positivo = 1, negativo = 0
        NDArray truePositive = trueND.gt(0);
        NDArray predPositive = predND.gt(0);

        // Coincidencia de signo (true si ambos tienen el mismo signo)
        NDArray sameSign = truePositive.eq(predPositive);

        // Crear factor: 0.5 si mismo signo, 2.0 si signo diferente
        NDArray factor = sameSign.toType(trueND.getDataType(), false)
                .mul(0.1f) // true -> 0.1
                .add(sameSign.logicalNot().toType(trueND.getDataType(), false).mul(2.0f)); // false -> 2.0

        // Aplicar factor al error
        NDArray weightedError = absError.mul(factor);

        return weightedError.mean();
    }

    /**
     * Este método bloquea el hilo que lo llama hasta que el entrenamiento
     * termine el siguiente batch y devuelva los resultados.
     */
    public LossReport awaitNextBatchData() {
        dataRequest = new CompletableFuture<>();
        try {
            // Se queda bloqueado aquí hasta que evaluate() llame a .complete()
            return dataRequest.get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            return new LossReport(0,0,0, 0,0,0);
        }
    }

    private final Map<String, NDArray> totalLoss = new ConcurrentHashMap<>();
    @Override
    public void updateAccumulators(String[] keys, NDList labels, NDList predictions) {
        NDArray updateArr = evaluate(labels, predictions).sum();
        try {
            for (String key : keys) {
                totalInstances.compute(key, (k, v) -> v == null ? 1L : v + 1L);
                NDArray acc = totalLoss.computeIfAbsent(
                        key,
                        k -> updateArr.getManager().zeros(new ai.djl.ndarray.types.Shape(1))
                );
                acc.detach();
                acc.addi(updateArr);
            }
        } finally {
            updateArr.close();
        }
    }

    @Override
    public void addAccumulator(String key) {
        totalInstances.put(key, 0L);
        NDArray acc = totalLoss.get(key);
        if (acc != null) {
            acc.set(new  float[]{0f});
        }
    }

    @Override
    public void updateAccumulator(String key, NDList labels, NDList predictions) {
        updateAccumulators(new String[] {key}, labels, predictions);
    }

    @Override
    public void resetAccumulator(String key) {
        totalInstances.compute(key, (k, v) -> 0L);
        NDArray acc = totalLoss.get(key);
        if (acc != null) {
            acc.set(new NDIndex(0), EngineUtils.floatToNDArray(0f, acc.getManager()));
        }
    }

    @Override
    public float getAccumulator(String key) {
        Long total = totalInstances.get(key);
        if (total == null) {
            throw new IllegalArgumentException("No loss found at that path");
        }
        if (total == 0) {
            return Float.NaN;
        }
        NDArray acc = totalLoss.get(key);
        if (acc == null) {
            return Float.NaN;
        }
        return acc.getFloat() / total;
    }
}
