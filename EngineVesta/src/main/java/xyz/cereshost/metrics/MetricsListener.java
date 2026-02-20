package xyz.cereshost.metrics;

import ai.djl.training.Trainer;
import ai.djl.training.listener.TrainingListenerAdapter;
import org.jfree.data.xy.XYSeriesCollection;
import xyz.cereshost.utils.ChartUtils;
import xyz.cereshost.Main;
import xyz.cereshost.common.Vesta;
import xyz.cereshost.engine.VestaEngine;
import xyz.cereshost.engine.VestaLoss;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class MetricsListener extends TrainingListenerAdapter {

    private long lastTime = -1;
    private long startTime = -1;
    private XYSeriesCollection datasetNormal = null;
    private XYSeriesCollection datasetLoss = null;
    private XYSeriesCollection datasetDireccion = null;
    private XYSeriesCollection datasetRateDireccion = null;
    private XYSeriesCollection datasetDireccionMemory = null;
    private final List<String> symbols = new ArrayList<>();

    private int count = 1;

    @Override
    public void onTrainingBegin(Trainer trainer) {
        startTime = System.currentTimeMillis();
    }

    @Override
    public void onEpoch(Trainer trainer) {
        var result = trainer.getTrainingResult();
        // Obtener los resultados
        float lossTrain = result.getTrainLoss();
        float lossValidation = result.getValidateLoss();
        float maeTrain = result.getTrainEvaluation("mae");
        float maeValidation = result.getValidateEvaluation("mae");
        float minValidation = result.getValidateEvaluation("min_diff");
        float maxValidation = result.getValidateEvaluation("max_diff");
        // Calucar porgreso
        double progress = (double) trainer.getTrainingResult().getEpoch() / (VestaEngine.EPOCH * (Main.MAX_MONTH_TRAINING) * VestaEngine.AUXILIAR_EPOCH);
        long time = System.currentTimeMillis();
        long delta = Math.abs(lastTime - time);
        // Mostrar mensaje
        Vesta.info(
                String.format("Progreso: %.2f%% | %.2f Bach/s | tiempo: %.2fs -T: %dm +T: %dm\n[%s] Bach: %,d",
                        (progress) * 100,
                        ((float) countBachOnEpoch) / (delta / 1000),
                        (double) delta / 1000,
                        (int) ((((VestaEngine.EPOCH * Main.MAX_MONTH_TRAINING * VestaEngine.AUXILIAR_EPOCH) - trainer.getTrainingResult().getEpoch()) * delta) / 1000) / 60,
                        (int) (((System.currentTimeMillis() - startTime) / 1000) / 60),
                        "#".repeat((int) (progress * 100)) + " ".repeat((int) (Math.abs(progress - 1) * 100)),
                        countBach
                )
        );

        // Ejecutar tarea de forma asincrónico
        VestaEngine.EXECUTOR.execute(() -> {
            lastTime = time;
            VestaLoss customLoss = (VestaLoss) trainer.getLoss();
            VestaLoss.LossReport l = customLoss.awaitNextBatchData();
            if (datasetLoss == null && datasetNormal == null && datasetRateDireccion == null) {
                datasetNormal = ChartUtils.plot("Training Loss/MAE " + String.join(", ", symbols), "epochs",
                        List.of(new ChartUtils.DataPlot("Loss T", List.of(lossTrain), new Color(108, 217, 91), ChartUtils.DataPlot.StyleLine.DISCONTINUA),
                                new ChartUtils.DataPlot("MAE T", List.of(maeTrain), Color.PINK, ChartUtils.DataPlot.StyleLine.DISCONTINUA),
                                new ChartUtils.DataPlot("Loss V", List.of(lossValidation), new Color(108, 217, 91), ChartUtils.DataPlot.StyleLine.NORMAL),
                                new ChartUtils.DataPlot("MAE V", List.of(maeValidation), Color.PINK, ChartUtils.DataPlot.StyleLine.NORMAL)

                        )
                );
                datasetLoss = ChartUtils.plot("Training Losses Max/Min " + String.join(", ", symbols), "epochs",
                        List.of(new ChartUtils.DataPlot("Loss max", List.of(l.max()), Color.GREEN, ChartUtils.DataPlot.StyleLine.DISCONTINUA),
                                new ChartUtils.DataPlot("Loss min", List.of(l.min()), Color.RED, ChartUtils.DataPlot.StyleLine.DISCONTINUA),
                                new ChartUtils.DataPlot("max", List.of(maxValidation), Color.GREEN, ChartUtils.DataPlot.StyleLine.NORMAL),
                                new ChartUtils.DataPlot("min", List.of(minValidation), Color.RED, ChartUtils.DataPlot.StyleLine.NORMAL)
                        )
                );
            }
            datasetNormal.getSeries("Loss T").add(count, lossTrain);
            datasetNormal.getSeries("MAE T").add(count, maeTrain);
            datasetNormal.getSeries("Loss V").add(count, lossValidation);
            datasetNormal.getSeries("MAE V").add(count, maeValidation);
            datasetLoss.getSeries("max").add(count, maxValidation);
            datasetLoss.getSeries("min").add(count, minValidation);
            datasetLoss.getSeries("Loss max").add(count, l.max());
            datasetLoss.getSeries("Loss min").add(count, l.min());
            count++;
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
