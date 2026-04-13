package xyz.cereshost.vesta.core.ia;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.nn.Activation;
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
        NDList trueParts = yTrue.split(new long[]{1}, 1);
        NDList predParts = yPred.split(new long[]{1, 2, 3, 4}, 1);

        NDArray lossCloses = computeDistance(trueParts.get(0), predParts.get(0));
//        NDArray lossHigh = computeDistance(trueParts.get(1), predParts.get(1));
//        NDArray lossLow = computeDistance(trueParts.get(2), predParts.get(2));
//        NDArray lossVolumen = computeDistance(trueParts.get(3), predParts.get(3));
//        NDArray lossMAE = computeDistance(trueParts.get(4), predParts.get(4));

        NDArray totalLoss = lossCloses;//.add(lossHigh).add(lossLow).add(lossVolumen).add(lossMAE);
        CompletableFuture<LossReport> request = dataRequest;
        if (request != null && !request.isDone()) {
            // Solo aquí pagamos el costo de sincronización GPU -> CPU
            request.complete(new LossReport(
                    totalLoss.getFloat(),
                    lossCloses.getFloat(),
                    0,//lossHigh.getFloat(),
                    0,//lossLow.getFloat(),
                    trueParts.getFirst().mean().getFloat(),
                    predParts.getFirst().mean().getFloat()
            ));
            dataRequest = null;
        }
        return totalLoss;
    }

    public NDArray computeDistance(NDArray trueND, NDArray predND){
        return trueND.sub(predND).abs().mean();
    }
    public NDArray computeRelative(NDArray trueND, NDArray predND){
        NDManager manager = trueND.getManager();
        NDArray diff = trueND.sub(predND).abs();
        NDArray mse = diff.pow(EngineUtils.floatToNDArray(1f, manager));

        NDArray absTrue = trueND.abs();
        NDArray weight = absTrue.mul(EngineUtils.floatToNDArray(RELATIVE_WEIGHT_ALPHA, manager))
                .add(EngineUtils.floatToNDArray(1f, manager));
        NDArray weighted = mse.mul(weight);

//        NDArray absPred = predND.abs();
//        NDArray one = EngineUtils.floatToNDArray(1f, manager);
//        NDArray clipped = absPred.minimum(one);
//        NDArray center = one.sub(clipped);
//        NDArray centerPenalty = center
//                .pow(EngineUtils.floatToNDArray(RELATIVE_CENTER_POWER, manager))
//                .mul(EngineUtils.floatToNDArray(RELATIVE_CENTER_WEIGHT, manager));

//        return weighted.add(centerPenalty).mean();
        return weighted.mean();
    }

    public NDArray computeDiffAdvance(NDArray trueND, NDArray predND){
        NDManager manager = trueND.getManager();
        NDArray diff = trueND.sub(predND).abs();

        NDArray radix = diff.sqrt();
        NDArray pow = diff.square().mul(EngineUtils.floatToNDArray(0.2f, manager));
        return radix.add(pow).mean();
    }

    /**
     * Penaliza el orden de llegada a los límites usando una probabilidad en [0,1].
     * trueFirstHit: 0 si mínimo primero, 1 si máximo primero.
     * Se pondera por la diferencia absoluta entre límites (señal más fuerte cuando hay más separación).
     */
    public NDArray computeFirstHitLoss(NDArray trueUp, NDArray trueDown, NDArray trueFirstHit, NDArray predFirstHit) {
        NDManager manager = trueUp.getManager();
        NDArray diff = trueUp.sub(trueDown).abs();
        NDArray predProb = Activation.sigmoid(predFirstHit);
        NDArray error = predProb.sub(trueFirstHit).abs();
        return error.mul(diff).mean();
    }

    /**
     * Penaliza cuando la predicción invierte la dirección (up > down vs up < down).
     * Retorna un factor multiplicativo: 1.0 si coincide, 2.0 si es opuesta.
     */
    public NDArray computeDirectionPenalty(NDArray trueUp, NDArray trueDown, NDArray predUp, NDArray predDown) {
        NDManager manager = trueUp.getManager();
        NDArray trueDiff = trueUp.sub(trueDown);
        NDArray predDiff = predUp.sub(predDown);
        NDArray product = trueDiff.mul(predDiff);
        // Penalizacion suave: 1.0 cuando coincide, 1.0 + extra cuando es opuesta
        NDArray mismatch = Activation.sigmoid(product.mul(EngineUtils.floatToNDArray(-DIRECTION_PENALTY_K, manager)));
        return mismatch.mul(EngineUtils.floatToNDArray(DIRECTION_PENALTY_EXTRA, manager))
                .add(EngineUtils.floatToNDArray(1f, manager));
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
