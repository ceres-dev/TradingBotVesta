package xyz.cereshost.vesta.core.ia.utils;

/**
 * Normalizador para Y:
 * - Y shape esperado: [samples][N]
 * - fit(y): calcula media/desviacion por columna
 * - transform(y): aplica z-score por columna
 * - inverseTransform(yNorm): revierte z-score
 */
public class YNormalizer implements Normalizer<float[][]> {

    private float[] means;
    private float[] stds;
    private static final float EPSILON = 1e-8f;
    private int numOutputs;

    private double[] meanAcc;
    private double[] m2Acc;
    private long[] countAcc;

    @Override
    public void fit(float[][] y) {
        if (y == null || y.length == 0) {
            throw new IllegalArgumentException("Los datos y no pueden ser nulos o vacios");
        }
        resetAcc(y[0].length);
        partialFit(y);
        finishFit();
    }

    public void partialFit(float[][] y) {
        if (y == null || y.length == 0) {
            return;
        }
        if (meanAcc == null || meanAcc.length != y[0].length) {
            resetAcc(y[0].length);
        }
        for (float[] row : y) {
            for (int col = 0; col < numOutputs; col++) {
                if (col >= row.length) {
                    continue;
                }
                float v = row[col];
                if (!Float.isFinite(v)) {
                    continue;
                }
                long c = ++countAcc[col];
                double delta = v - meanAcc[col];
                meanAcc[col] += delta / c;
                double delta2 = v - meanAcc[col];
                m2Acc[col] += delta * delta2;
            }
        }
    }

    public void finishFit() {
        means = new float[numOutputs];
        stds = new float[numOutputs];
        for (int col = 0; col < numOutputs; col++) {
            long c = countAcc[col];
            if (c == 0) {
                means[col] = 0f;
                stds[col] = 1f;
                continue;
            }
            double mean = meanAcc[col];
            double variance = (m2Acc[col] / c);
            double std = Math.sqrt(Math.max(variance, EPSILON));
            means[col] = (float) mean;
            stds[col] = (float) std;
        }
    }

    private void resetAcc(int outputs) {
        numOutputs = outputs;
        meanAcc = new double[outputs];
        m2Acc = new double[outputs];
        countAcc = new long[outputs];
    }

    @Override
    public float[][] transform(float[][] y) {
        if (means == null || stds == null) throw new IllegalStateException("Normalizador no ajustado");
        float[][] normalized = new float[y.length][numOutputs];
        for (int i = 0; i < y.length; i++) {
            for (int col = 0; col < numOutputs; col++) {
                float raw = y[i][col];
                if (!Float.isFinite(raw)) raw = 0f;
                float z = (float) ((raw - means[col]) / stds[col]);
                normalized[i][col] = Float.isFinite(z) ? z : 0f;
            }
        }
        return normalized;
    }

    @Override
    public float[][] inverseTransform(float[][] yNorm) {
        float[][] original = new float[yNorm.length][yNorm[0].length];
        for (int i = 0; i < yNorm.length; i++) {
            for (int col = 0; col < /*yNorm[i].length*/1; col++) {
                double v = (yNorm[i][col] * stds[col]) + means[col];
                original[i][col] = Double.isFinite(v) ? (float) v : 0f;
            }
        }
        return original;
    }
}
