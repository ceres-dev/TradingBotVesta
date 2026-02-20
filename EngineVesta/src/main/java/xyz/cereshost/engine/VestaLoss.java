package xyz.cereshost.engine;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.nn.Activation;
import ai.djl.training.loss.Loss;
import lombok.SneakyThrows;
import xyz.cereshost.utils.EngineUtils;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public class VestaLoss extends Loss {

    public record LossReport(float total, float max, float min) {}

    private static final float RELATIVE_WEIGHT_ALPHA = 2.5f;
    private static final float ROI_SIGMOID_K = 5.0f;

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
        NDList trueParts = yTrue.split(new long[]{1, 2, 3, 4}, 1);
        NDList predParts = yPred.split(new long[]{1, 2, 3, 4}, 1);
        NDArray totalLoss = computeExpectedRoiLoss(trueParts.get(0), trueParts.get(1), predParts.get(0), predParts.get(1));
        NDArray lossUp = computeDistance(trueParts.get(0), predParts.get(0));
        NDArray lossDown = computeDistance(trueParts.get(1), predParts.get(1));
        CompletableFuture<LossReport> request = dataRequest;
        if (request != null && !request.isDone()) {
            // Solo aquí pagamos el costo de sincronización GPU -> CPU
            request.complete(new LossReport(
                    totalLoss.getFloat(),
                    lossUp.getFloat(),
                    lossDown.getFloat()
            ));
            dataRequest = null;
        }
        return totalLoss;
    }

    private NDArray TPSLAdvance(NDList trueParts, NDArray slTrue, NDArray slPred) {
        NDManager manager = slTrue.getManager();;
        NDArray isNeutral = trueParts.get(3);
        NDArray mask = isNeutral.mul(EngineUtils.floatToNDArray(-1f, manager)).add(EngineUtils.floatToNDArray(1f, manager));

        NDArray loss = slTrue.sub(slPred)
                .abs()
                .mul(mask)
                .mul(EngineUtils.floatToNDArray(0.7f, manager));

        NDArray valid = mask.sum().maximum(EngineUtils.floatToNDArray(1e-7f, manager));

        return loss.sum().div(valid);
    }

    public NDArray computeDistance(NDArray trueND, NDArray predND){
        NDArray diff = trueND.sub(predND).abs();
        return diff.mean();
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
     * Expected ROI loss (differentiable approximation).
     * Assumes trueUp/trueDown represent reachable max/min magnitudes (>= 0) in the same scale as preds.
     */
    public NDArray computeExpectedRoiLoss(NDArray trueUp, NDArray trueDown, NDArray predUp, NDArray predDown) {
        NDManager manager = trueUp.getManager();
        NDArray zero = EngineUtils.floatToNDArray(0f, manager);

        NDArray trueUpPos = trueUp.maximum(zero);
        NDArray trueDownPos = trueDown.maximum(zero);

        NDArray predUpPos = Activation.softPlus(predUp);
        NDArray predDownPos = Activation.softPlus(predDown);

        NDArray k = EngineUtils.floatToNDArray(ROI_SIGMOID_K, manager);
        NDArray pTp = Activation.sigmoid(trueUpPos.sub(predUpPos).mul(k));
        NDArray pSl = Activation.sigmoid(trueDownPos.sub(predDownPos).mul(k));

        NDArray roi = pTp.mul(predUpPos).sub(pSl.mul(predDownPos));
        return roi.mean().mul(EngineUtils.floatToNDArray(-1f, manager));
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
            return new LossReport(0,0,0);
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
