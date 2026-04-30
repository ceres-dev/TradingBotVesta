package xyz.cereshost.vesta.core.ia.metrics;

import ai.djl.training.Trainer;
import ai.djl.training.listener.TrainingListenerAdapter;
import org.jfree.data.xy.XYSeriesCollection;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.core.utils.ChartUtils;
import xyz.cereshost.vesta.core.Main;
import xyz.cereshost.vesta.core.ia.VestaEngine;
import xyz.cereshost.vesta.core.ia.VestaLoss;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class MetricsListener extends TrainingListenerAdapter {

    private long lastTime = -1;
    private long startTime = -1;
    private XYSeriesCollection datasetNormal = null;
    private XYSeriesCollection datasetDiff = null;
    private final List<String> symbols = new ArrayList<>();

    private int count = 1;

    @Override
    public void onTrainingBegin(Trainer trainer) {
        startTime = System.currentTimeMillis();
    }

    @Override
    public void onEpoch(Trainer trainer) {
        count++;
        if (count%5 != 0) return;
        var result = trainer.getTrainingResult();
        // Obtener los resultados
        float lossTrain = result.getTrainLoss();
        float lossValidation = result.getValidateLoss();
//        float maeTrain = result.getTrainEvaluation("ema");
//        float maeValidation = result.getValidateEvaluation("ema");
//        float minValidation = result.getValidateEvaluation("min_diff");
//        float closeValidation = result.getValidateEvaluation("max_diff");
        // Calucar porgreso
        int totalEpoch = (VestaEngine.EPOCH * (Main.MAX_MONTH_TRAINING-1) * VestaEngine.AUXILIAR_EPOCH);
        double progress = (double) trainer.getTrainingResult().getEpoch() / totalEpoch;
        long time = System.currentTimeMillis();
        long delta = Math.abs(lastTime - time);
        // Mostrar mensaje
        Vesta.info(
                String.format("Progreso: %.2f%% | %.2f Bach/s | tiempo: %.2fs -T: %dm +T: %dm\n[%s] Bach: %,d",
                        (progress) * 100,
                        ((float) countBachOnEpoch) / (delta / 1000f),
                        (double) delta / 1000d,
                        (int) (((totalEpoch - trainer.getTrainingResult().getEpoch()) * delta) / 1000) / 60,
                        (int) (((System.currentTimeMillis() - startTime) / 1000) / 60),
                        "#".repeat((int) (progress * 100)) + " ".repeat((int) (Math.abs(progress - 1) * 100)),
                        countBach
                )
        );

        // Ejecutar tarea de forma asincrónico
        Main.EXECUTOR.execute(() -> {
            lastTime = time;
            VestaLoss customLoss = (VestaLoss) trainer.getLoss();
            VestaLoss.LossReport l = customLoss.awaitNextBatchData();
            if (datasetNormal == null || datasetDiff == null) {
                datasetNormal = ChartUtils.plot("Training Loss/MAE " + String.join(", ", symbols), "epochs",
                        List.of(
                                new ChartUtils.DataPlot("Loss V", List.of(Math.min(lossValidation, 50)), new Color(108, 217, 91), ChartUtils.DataPlot.StyleLine.NORMAL),
                                new ChartUtils.DataPlot("Loss T", List.of(Math.min(lossTrain, 50)), new Color(41, 82, 33), ChartUtils.DataPlot.StyleLine.DISCONTINUA)
//                                new ChartUtils.DataPlot("MAE T", List.of(Math.min(maeTrain, 50)), new Color(91, 63, 63), ChartUtils.DataPlot.StyleLine.DISCONTINUA),
//                                new ChartUtils.DataPlot("MAE V", List.of(Math.min(maeValidation, 50)), Color.PINK, ChartUtils.DataPlot.StyleLine.NORMAL)
                        )
                );
                datasetDiff = ChartUtils.plot("diff " + String.join(", ", symbols), "epochs",
                        List.of(new ChartUtils.DataPlot("diff", List.of(l.meanReal() - l.meanPred()), Color.RED, ChartUtils.DataPlot.StyleLine.DISCONTINUA),
                                new ChartUtils.DataPlot("diff Abs", List.of(Math.abs(l.meanReal() - l.meanPred())), new Color(64, 0, 0), ChartUtils.DataPlot.StyleLine.DISCONTINUA),
                        new ChartUtils.DataPlot("real", List.of(l.meanReal()), Color.GREEN, ChartUtils.DataPlot.StyleLine.NORMAL),
                        new ChartUtils.DataPlot("pred", List.of(l.meanPred()), new Color(108, 217, 91), ChartUtils.DataPlot.StyleLine.DISCONTINUA)
                ));
            }
            datasetNormal.getSeries("Loss T").add(count, lossTrain);
//            datasetNormal.getSeries("MAE T").add(count, maeTrain);
            datasetNormal.getSeries("Loss V").add(count, lossValidation);
//            datasetNormal.getSeries("MAE V").add(count, maeValidation);
            datasetDiff.getSeries("diff").add(count, l.meanReal() - l.meanPred());
            datasetDiff.getSeries("diff Abs").add(count, Math.abs(l.meanReal() - l.meanPred()));
            datasetDiff.getSeries("real").add(count, l.meanReal());
            datasetDiff.getSeries("pred").add(count, l.meanPred());
        });
        countBachOnEpoch = 0;
    }

    private int countBach = 0;
    private int countBachOnEpoch = 0;

    @Override
    public void onTrainingBatch(Trainer trainer, BatchData batchData) {
        countBach++;
        countBachOnEpoch++;
    }

}
