package xyz.cereshost.vesta.core.ia;

import ai.djl.Device;
import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.common.market.Candle;
import xyz.cereshost.vesta.core.ia.utils.EngineUtils;
import xyz.cereshost.vesta.core.ia.utils.XNormalizer;
import xyz.cereshost.vesta.core.ia.utils.YNormalizer;
import xyz.cereshost.vesta.core.trading.DireccionOperation;
import xyz.cereshost.vesta.core.util.BuilderData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


@Getter
public class PredictionEngine {

    public static final double THRESHOLD_PRICE = 0.002; // No se usa
    public static final double THRESHOLD_RELATIVE = 0.5;
    private static final int MODEL_OUTPUTS = 5;
    private static final int REQUIRED_AUTOREGRESSIVE_FEATURES = 5;

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
     * Devuelve los valores desnormalizados para un unico ejemplo (batch=1).
     * Formato: [close, high, low, volumen, ema]
     */
    public float[] predictRaw(float[][][] inputSequence) {
        if (inputSequence == null || inputSequence.length == 0) {
            throw new IllegalArgumentException("inputSequence no puede ser null o vacio");
        }
        if (inputSequence[0] == null || inputSequence[0].length == 0 || inputSequence[0][0] == null) {
            throw new IllegalArgumentException("inputSequence tiene dimensiones invalidas");
        }
        NDManager manager = model.getNDManager();
        int batchSize = inputSequence.length;
        int sequenceLength = inputSequence[0].length;
        int actualFeatures = inputSequence[0][0].length;
        if (batchSize != 1) {
            throw new IllegalArgumentException("predictRaw solo soporta batch=1. Batch recibido: " + batchSize);
        }
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
        if (shape[shape.length - 1] != MODEL_OUTPUTS) {
            throw new RuntimeException("El modelo debe tener " + MODEL_OUTPUTS + " salidas. Forma actual: " + prediction.getShape());
        }

        float[][] output2D = new float[1][MODEL_OUTPUTS];
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
        List<Candle> sortedCandles = new ArrayList<>(candles);
        sortedCandles.sort(Comparator.comparingLong(Candle::openTime));

        if (sortedCandles.size() < lookBack + 1) {
            throw new RuntimeException("Historial insuficiente. Se necesitan " + (lookBack + 1) + " velas.");
        }

        List<Candle> subList = sortedCandles.subList((sortedCandles.size() - (lookBack + 1)), sortedCandles.size());

        // Construir entrada inicial
        float[][][] X = new float[1][Math.toIntExact(lookBack)][Math.toIntExact(features - 2)];
        for (int j = 0; j < lookBack; j++) {
            X[0][j] = BuilderData.extractFeatures(subList.get(j + 1), subList.get(j));
        }

        if (X[0][0].length < REQUIRED_AUTOREGRESSIVE_FEATURES) {
            throw new IllegalStateException(
                    "Se requieren al menos " + REQUIRED_AUTOREGRESSIVE_FEATURES + " features para inyectar la prediccion."
            );
        }

        List<PredictionCandleResult> results = new ArrayList<>(futureCandles);

        for (int step = 0; step < futureCandles; step++) {
            // Inferencia
            float[] rawPredictions = predictRaw(X); // Output del modelo
            PredictionCandleResult predicted = new PredictionCandleResult(
                    rawPredictions[0],
                    rawPredictions[1],
                    rawPredictions[2],
                    rawPredictions[3],
                    rawPredictions[4]
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
            nextFeatures[4] = (float) predicted.ema();
            X[0][lookBack - 1] = nextFeatures;
        }

        return new PredictionResult(results);
    }
    @Data
    @AllArgsConstructor
    public static class PredictionResult {
        List<PredictionCandleResult> candles;

        public double maxClose() {
            GraphRange graphRange = closeGraphRange();
            return graphRange.max();
        }

        public double minClose() {
            GraphRange graphRange = closeGraphRange();
            return graphRange.min();
        }

        public double maxHigh() {
            return maxClose();
        }

        public double minLow() {
            return minClose();
        }

        // Extremos de la curva predicha de close acumulado (no mechas individuales).
        private GraphRange closeGraphRange() {
            if (candles == null || candles.isEmpty()) {
                return new GraphRange(Double.NaN, Double.NaN);
            }
            double cumulative = 1.0;
            double max = Double.NEGATIVE_INFINITY;
            double min = Double.POSITIVE_INFINITY;
            for (PredictionCandleResult candle : candles) {
                double step = candle.close();
                if (!Double.isFinite(step)) {
                    continue;
                }
                cumulative *= (1.0 + step);
                if (!Double.isFinite(cumulative)) {
                    continue;
                }
                double point = cumulative - 1.0;
                max = Math.max(max, point);
                min = Math.min(min, point);
            }
            if (!Double.isFinite(min) || !Double.isFinite(max)) {
                return new GraphRange(Double.NaN, Double.NaN);
            }
            return new GraphRange(min, max);
        }

        private record GraphRange(double min, double max) {}

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
            double max = maxClose();
            double min = minClose();
            if (getDireccion().equals(DireccionOperation.LONG)) {
                return max *100;
            }else {
                return Math.abs(min) * 100;
            }
        }

        public double slPercent(){
            double min = minLow();
            double max = maxHigh();
            if (getDireccion().equals(DireccionOperation.LONG)) {
                return Math.abs(min) * 100;
            }else {
                return max * 100;
            }
        }
    }

    public record PredictionCandleResult(double close, double high, double low, float volumen, double ema) {

    }
}
