package xyz.cereshost.vesta.core.ia;

import ai.djl.Device;
import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.util.Pair;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.core.Main;
import xyz.cereshost.vesta.core.ia.utils.EngineUtils;
import xyz.cereshost.vesta.core.ia.utils.PredictionUtils;
import xyz.cereshost.vesta.core.ia.utils.XNormalizer;
import xyz.cereshost.vesta.core.ia.utils.YNormalizer;
import xyz.cereshost.vesta.core.io.IOdata;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.utils.BuilderData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


@Getter
public class PredictionEngine {

    public static final double THRESHOLD_PRICE = 0.002; // No se usa
    public static final double THRESHOLD_RELATIVE = 0.5;

    private final Model model;
    private final XNormalizer xNormalizer;
    private final YNormalizer yNormalizer;
    private final int lookBack;
    private final int features;
    private final Device device;

    public PredictionEngine(XNormalizer xNormalizer, YNormalizer yNormalizer, Model model, int lookBack, int features) {
        this.device = Device.gpu();
        this.model = model;
        this.xNormalizer = xNormalizer;
        this.yNormalizer = yNormalizer;
        this.lookBack = lookBack;
        this.features = features;
    }

    /**
     * Hace la inferencia en el modelo.
     * Devuelve los valores desnormalizados para los outputs 0 (upMove) y 1 (downMove).
     * Formato: [upMove, downMove, 0, 0, 0]
     */
    public float[] predictRaw(float[][][] inputSequence) {
        NDManager manager = model.getNDManager();
        int batchSize = inputSequence.length;
        int sequenceLength = inputSequence[0].length;
        int actualFeatures = inputSequence[0][0].length;
        //debugInputData(inputSequence);
        // Validación de dimensiones
        if (actualFeatures != features) {
            Vesta.warning("⚠️ Advertencia de dimensiones: El modelo espera " + features +
                    " features, pero recibió " + actualFeatures);
        }

        // 1. Normalizar entrada (RobustScaling)
        float[][][] normalizedInput = xNormalizer.transform(inputSequence);

        // 2. Aplanar para DJL
        float[] flatInput = EngineUtils.flatten3DArray(normalizedInput);
        NDArray inputArray = manager.create(flatInput, new Shape(batchSize, sequenceLength, actualFeatures));

        // 3. Forward Pass
        var block = model.getBlock();
        var parameterStore = new ai.djl.training.ParameterStore(manager, false);
        NDList output = block.forward(parameterStore, new NDList(inputArray), false);

        NDArray prediction = output.singletonOrThrow();
        float[] normalizedOutput = prediction.toFloatArray();

        // Verificar la forma de la salida
        long[] shape = prediction.getShape().getShape();
        if (shape[shape.length - 1] != 5) {
            throw new RuntimeException("El modelo debe tener 5 salidas. Forma actual: " + prediction.getShape());
        }

        float[][] output2D = new float[1][5];
        output2D[0][0] = normalizedOutput[0];
        output2D[0][1] = normalizedOutput[1];
        output2D[0][2] = normalizedOutput[2];
        output2D[0][3] = normalizedOutput[3];
        output2D[0][4] = normalizedOutput[4];
        return yNormalizer.inverseTransform(output2D)[0];
    }

    public PredictionResult predictNextPriceDetail(List<Candle> candles) {
        return predictNextPriceDetail(candles, 20);
    }

    public PredictionResult predictNextPriceDetail(List<Candle> candles, int futureCandles) {
        if (futureCandles <= 0) {
            return new PredictionResult(List.of());
        }
        candles.sort(Comparator.comparingLong(Candle::openTime));

        if (candles.size() < lookBack + 1) {
            throw new RuntimeException("Historial insuficiente. Se necesitan " + (lookBack + 1) + " velas.");
        }

        List<Candle> subList = candles.subList((candles.size() - (lookBack + 1)), candles.size());

        // Construir entrada inicial
        float[][][] X = new float[1][Math.toIntExact(lookBack)][Math.toIntExact(features - 2)];
        for (int j = 0; j < lookBack; j++) {
            X[0][j] = BuilderData.extractFeatures(subList.get(j + 1), subList.get(j));
        }

        if (X[0][0].length < 4) {
            throw new IllegalStateException("Se requieren al menos 4 features para inyectar la prediccion.");
        }

        List<PredictionCandleResult> results = new ArrayList<>(futureCandles);

        for (int step = 0; step < futureCandles; step++) {
            // Inferencia
            float[] rawPredictions = predictRaw(X); // Output del modelo
            PredictionCandleResult predicted = new PredictionCandleResult(
                    rawPredictions[0],
                    rawPredictions[1],
                    rawPredictions[2],
                    rawPredictions[3]
            );
            results.add(predicted);

            // Shift de ventana y agregar la prediccion como nueva entrada
            for (int t = 0; t < lookBack - 1; t++) {
                X[0][t] = X[0][t + 1];
            }

            float[] nextFeatures = new float[X[0][0].length];
            nextFeatures[0] = (float) predicted.close();
            nextFeatures[1] = (float) predicted.high();
            nextFeatures[2] = (float) predicted.low();
            nextFeatures[3] = predicted.volumen();
            X[0][lookBack - 1] = nextFeatures;
        }

        return new PredictionResult(results);
    }
    @Data
    @AllArgsConstructor
    public static class PredictionResult {
        List<PredictionCandleResult> candles;

        public double maxClose() {
            if (candles == null || candles.isEmpty()) return Double.NaN;
            double max = Double.NEGATIVE_INFINITY;
            for (PredictionCandleResult c : candles) {
                max = Math.max(max, c.close());
            }
            return max;
        }

        public double minClose() {
            if (candles == null || candles.isEmpty()) return Double.NaN;
            double min = Double.POSITIVE_INFINITY;
            for (PredictionCandleResult c : candles) {
                min = Math.min(min, c.close());
            }
            return min;
        }

        public double maxHigh() {
            if (candles == null || candles.isEmpty()) return Double.NaN;
            double max = Double.NEGATIVE_INFINITY;
            for (PredictionCandleResult c : candles) {
                max = Math.max(max, c.high());
            }
            return max;
        }

        public double minLow() {
            if (candles == null || candles.isEmpty()) return Double.NaN;
            double min = Double.POSITIVE_INFINITY;
            for (PredictionCandleResult c : candles) {
                min = Math.min(min, c.low());
            }
            return min;
        }

        public double ratioClose() {
            double min = minClose();
            double max = maxClose();
            if (!Double.isFinite(min) || !Double.isFinite(max)) return Double.NaN;
            double a = Math.abs(min);
            double b = Math.abs(max);
            if (a == 0.0 || b == 0.0) return Double.NaN;
            return Math.max(a, b) / Math.min(a, b);
        }

        public DireccionOperation getDireccion() {
            double min = minClose();
            double max = maxClose();
            if (max > Math.abs(min)){
                return DireccionOperation.LONG;
            }else {
                return DireccionOperation.SHORT;
            }
        }

        public double tpPercent(){
            double min = minClose();
            double max = maxClose();
            if (getDireccion().equals(DireccionOperation.LONG)) {
                return max *100;
            }else {
                return Math.abs(min) * 100;
            }
        }

        public double slPercent(){
            double min = minClose();
            double max = maxClose();
            if (getDireccion().equals(DireccionOperation.SHORT)) {
                return max * 100;
            }else {
                return Math.abs(min) * 100;
            }
        }
    }

    public record PredictionCandleResult(double close, double high, double low, float volumen) {}
}
