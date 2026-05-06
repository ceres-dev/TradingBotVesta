package xyz.cereshost.vesta.core.utils;

import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfree.chart.*;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.Plot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.CandlestickRenderer;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.DefaultHighLowDataset;
import org.jfree.data.xy.OHLCDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.core.market.Candle;
import xyz.cereshost.vesta.core.market.Symbol;
import xyz.cereshost.vesta.core.trading.TradingManager;
import xyz.cereshost.vesta.core.trading.TradingTelemetry;
import xyz.cereshost.vesta.core.trading.TypeOrder;
import xyz.cereshost.vesta.core.utils.candle.CandleIndicators;
import xyz.cereshost.vesta.core.utils.candle.SequenceCandles;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

import static org.jfree.chart.axis.NumberAxis.createIntegerTickUnits;

public class ChartUtils {

    private static boolean chartsDisabledLogged = false;

    private static boolean chartsEnabled() {
        boolean enabled = !GraphicsEnvironment.isHeadless() && !Boolean.getBoolean("vesta.charts.disabled");
        if (!enabled && !chartsDisabledLogged) {
            Vesta.info("Charts deshabilitados (headless o vesta.charts.disabled=true).");
            chartsDisabledLogged = true;
        }
        return enabled;
    }


    public static XYSeriesCollection plot(
            String title,
            String xLabel,
            List<DataPlot> plots
    ) {
        XYSeriesCollection dataset = new XYSeriesCollection();
        for (DataPlot plot : plots) {
            List<Float> values = plot.getValues();
            String seriesName = plot.getYLabel();
            XYSeries series = new XYSeries(seriesName);
            for (int i = 0; i < values.size(); i++) {
                series.add(i + 1, (float) values.get(i));

            }
            dataset.addSeries(series);
        }


        JFreeChart chart = ChartFactory.createXYLineChart(
                title,
                xLabel,
                "Value",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );
        NumberAxis xAxis = getNumberAxisAndApplyStyle(plots, chart);
        xAxis.setNumberFormatOverride(new DecimalFormat("0"));
        xAxis.setAutoTickUnitSelection(true);
        xAxis.setStandardTickUnits(createIntegerTickUnits());

        if (!chartsEnabled()) {
            return dataset;
        }
        
        ChartFrame frame = new ChartFrame(title, chart);
        darkMode(chart);
        frame.getChartPanel().setMouseWheelEnabled(true);
        frame.pack();
        frame.setVisible(true);

        return dataset;
    }

    private static NumberAxis getNumberAxisAndApplyStyle(List<DataPlot> plots, JFreeChart chart) {
        XYPlot chartSeries = chart.getXYPlot();
        int idx = 0;
        for (DataPlot plot : plots) {
            XYItemRenderer render = chartSeries.getRenderer();
            if (plot.getColor() != null) render.setSeriesPaint(idx, plot.getColor());
            switch (plot.getStyleLine()){
                case DISCONTINUA -> render.setSeriesStroke(idx,
                        new BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{8.0f, 6.0f}, 0));
                default -> render.setSeriesStroke(idx,
                        new BasicStroke(2f));
            }
            idx++;
        }
        return (NumberAxis) chartSeries.getDomainAxis();
    }

    @Getter
    public static final class DataPlot {
        @NotNull
        private final String yLabel;
        @NotNull
        private final List<Float> values;
        private final Color color;
        @NotNull
        private final StyleLine styleLine;

        public DataPlot(
                @NotNull String yLabel,
                @NotNull List<Float> values
        ) {
            this.yLabel = yLabel;
            this.values = values;
            this.color = null;
            this.styleLine = StyleLine.NORMAL;
        }

        public DataPlot(
                @NotNull String yLabel,
                @NotNull List<Float> values,
                Color color,
                @NotNull StyleLine styleLine
        ) {
            this.yLabel = yLabel;
            this.values = values;
            this.color = color;
            this.styleLine = styleLine;
        }

        public enum StyleLine{
            NORMAL,
            DISCONTINUA
        }

    }

    public static void darkMode(JFreeChart chart){
        Plot plot = chart.getPlot();
        chart.getTitle().setPaint(Color.WHITE);
        switch (plot){
            case XYPlot xyplot -> {
                ValueAxis xAxis = xyplot.getDomainAxis();
                ValueAxis yAxis = xyplot.getRangeAxis();
                xAxis.setLabelPaint(Color.WHITE);
                xAxis.setTickLabelPaint(Color.WHITE);
                yAxis.setLabelPaint(Color.WHITE);
                yAxis.setTickLabelPaint(Color.WHITE);
                xyplot.setDomainGridlinePaint(Color.GRAY);
                xyplot.setRangeGridlinePaint(Color.GRAY);
            }
            default -> {}
        }
        plot.setBackgroundPaint(Color.BLACK);
        chart.getLegend().setBackgroundPaint(Color.BLACK);
        chart.getLegend().setItemPaint(Color.WHITE);
        chart.setBackgroundPaint(Color.BLACK);
    };

    public static void applyBinanceCandleStyle(@NotNull XYPlot plot) {
        XYItemRenderer renderer = plot.getRenderer();
        if (renderer instanceof CandlestickRenderer candleRenderer) {
            applyBinanceCandleStyle(candleRenderer);
        }
    }

    private static final Color BINANCE_UP = new Color(0x2E, 0xBC, 0x84, 0xFF);
    private static final Color BINANCE_DOWN = new Color(0xF4, 0x45, 0x5C, 0xFF);
    private static final Color BINANCE_OUTLINE = new Color(255, 255, 255, 0xFF);

    public static void applyBinanceCandleStyle(@NotNull CandlestickRenderer renderer) {
        renderer.setUpPaint(BINANCE_UP);
        renderer.setDownPaint(BINANCE_DOWN);
        renderer.setUseOutlinePaint(true);
        renderer.setDefaultOutlinePaint(BINANCE_OUTLINE);
    }



    public static void showCandleChart(String title, SequenceCandles candles, Symbol symbol) {
        if (candles == null || candles.isEmpty()) {
            Vesta.error("No hay velas para mostrar");
            return;
        }
        if (!chartsEnabled()) {
            return;
        }

        try {
            // Preparar datos para JFreeChart
            int itemCount = candles.size();
            Date[] dates = new Date[itemCount];
            double[] highs = new double[itemCount];
            double[] lows = new double[itemCount];
            double[] opens = new double[itemCount];
            double[] closes = new double[itemCount];
            double[] volumes = new double[itemCount];
            XYSeries indicatorSeries = new XYSeries("SuperTrend");

            for (int i = 0; i < itemCount; i++) {
                CandleIndicators candle = candles.getCandle(i);
                dates[i] = new Date(candle.getOpenTime());
                opens[i] = candle.getOpen();
                highs[i] = candle.getHigh();
                lows[i] = candle.getLow();
                closes[i] = candle.getClose();
                volumes[i] = candle.getVolumen().baseVolume();

                // Candle.superTrendSlow() almacena (lineaSuperTrend - close), por eso sumamos close para obtener el precio real de la linea.
                double superTrendPrice = candle.get("output5");// candle.close() + candle.superTrendSlow();

                if (Double.isFinite(superTrendPrice) && superTrendPrice > 0) {
                    indicatorSeries.add(dates[i].getTime(), superTrendPrice);
                }
            }

            // Crear dataset de velas
            OHLCDataset dataset = new DefaultHighLowDataset(
                    symbol.name(), dates, highs, lows, opens, closes, volumes
            );

            // Crear gráfico de velas
            JFreeChart chart = ChartFactory.createCandlestickChart(
                    title + " - " + symbol,
                    "Fecha",
                    "Precio",
                    dataset,
                    true
            );

            // Configurar eje de fechas
            XYPlot plot = (XYPlot) chart.getPlot();
            darkMode(chart);
            DateAxis axis = (DateAxis) plot.getDomainAxis();
            axis.setDateFormatOverride(new SimpleDateFormat("dd/MM HH:mm"));
            applyBinanceCandleStyle(plot);

            // Overlay: linea de SuperTrend
            if (!indicatorSeries.isEmpty()) {
                XYSeriesCollection superTrendDataset = new XYSeriesCollection();
                superTrendDataset.addSeries(indicatorSeries);

                XYLineAndShapeRenderer superTrendRenderer = new XYLineAndShapeRenderer(true, false);
                superTrendRenderer.setSeriesPaint(0, new Color(255, 215, 0));
                superTrendRenderer.setSeriesStroke(0, new BasicStroke(1.7f));

                plot.setDataset(1, superTrendDataset);
                plot.setRenderer(1, superTrendRenderer);
            }

            // Ajusta el zoon
            double minPrice = Arrays.stream(lows).min().orElse(0);
            double maxPrice = Arrays.stream(highs).max().orElse(0);
            if (!indicatorSeries.isEmpty()) {
                minPrice = Math.min(minPrice, indicatorSeries.getMinY());
                maxPrice = Math.max(maxPrice, indicatorSeries.getMaxY());
            }

            double padding = (maxPrice - minPrice) * 0.02; // pading

            NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
            rangeAxis.setRange(
                    minPrice - padding,
                    maxPrice + padding
            );

            // Mostrar en ventana
            ChartPanel chartPanel = new ChartPanel(chart);
            chartPanel.setPreferredSize(new Dimension(1200, 600));

            JFrame frame = new JFrame("Visualización de Velas - " + symbol);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.getContentPane().add(chartPanel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            Vesta.info("Mostrando gráfico de " + candles.size() + " velas para " + symbol);

        } catch (Exception e) {
            Vesta.error("Error mostrando gráfico: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public record PredictionPoint(long time, double tpPrice, double slPrice) {}
    public record ClosePredictionPoint(long time, double close) {}

    public static void showCandlePredictionSnapshot(
            String title,
            List<Candle> lookbackCandles,
            List<ClosePredictionPoint> predictedFuture,
            List<ClosePredictionPoint> actualFuture,
            long predictionTime
    ) {
        if (lookbackCandles == null || lookbackCandles.isEmpty()) {
            Vesta.error("No hay velas para mostrar");
            return;
        }
        if (!chartsEnabled()) {
            return;
        }

        List<Candle> sortedLookback = new ArrayList<>(lookbackCandles);
        sortedLookback.sort(Comparator.comparingLong(Candle::getOpenTime));

        List<ClosePredictionPoint> pred = new ArrayList<>();
        if (predictedFuture != null) {
            pred.addAll(predictedFuture);
            pred.sort(Comparator.comparingLong(ClosePredictionPoint::time));
        }

        List<ClosePredictionPoint> real = new ArrayList<>();
        if (actualFuture != null) {
            real.addAll(actualFuture);
            real.sort(Comparator.comparingLong(ClosePredictionPoint::time));
        }

        DefaultHighLowDataset candleDataset = buildHighLowDataset(sortedLookback, 0, sortedLookback.size() - 1, title);
        JFreeChart chart = ChartFactory.createCandlestickChart(
                title,
                "Fecha",
                "Precio",
                candleDataset,
                true
        );

        XYPlot plot = (XYPlot) chart.getPlot();
        DateAxis axis = (DateAxis) plot.getDomainAxis();
        axis.setDateFormatOverride(new SimpleDateFormat("dd/MM HH:mm"));
        applyBinanceCandleStyle(plot);

        XYSeries actualSeries = new XYSeries("Actual", true, false);
        if (!sortedLookback.isEmpty()) {
            double anchorClose = sortedLookback.getLast().getClose();
            actualSeries.add(predictionTime, anchorClose);
        }
        for (ClosePredictionPoint p : real) {
            actualSeries.add(p.time(), p.close());
        }

        XYSeries predictedSeries = new XYSeries("Predicted", true, false);
        if (!sortedLookback.isEmpty()) {
            double anchorClose = sortedLookback.getLast().getClose();
            predictedSeries.add(predictionTime, anchorClose);
        }
        for (ClosePredictionPoint p : pred) {
            predictedSeries.add(p.time(), p.close());
        }

        XYSeriesCollection lineDataset = new XYSeriesCollection();
        lineDataset.addSeries(actualSeries);
        lineDataset.addSeries(predictedSeries);

        XYLineAndShapeRenderer lineRenderer = new XYLineAndShapeRenderer(true, false);
        lineRenderer.setSeriesPaint(0, new Color(0, 120, 255, 220));
        lineRenderer.setSeriesPaint(1, new Color(255, 140, 0, 220));
        lineRenderer.setSeriesStroke(0, new BasicStroke(1.2f));
        lineRenderer.setSeriesStroke(1, new BasicStroke(1.2f));

        plot.setDataset(1, lineDataset);
        plot.setRenderer(1, lineRenderer);

        ValueMarker marker = new ValueMarker(predictionTime);
        marker.setPaint(new Color(255, 215, 0, 180));
        marker.setStroke(new BasicStroke(1.2f));
        plot.addDomainMarker(marker);

        long startTime = sortedLookback.getFirst().getOpenTime();
        long endTime = startTime;
        if (!real.isEmpty()) {
            endTime = Math.max(endTime, real.getLast().time());
        }
        if (!pred.isEmpty()) {
            endTime = Math.max(endTime, pred.getLast().time());
        }
        if (endTime <= startTime) {
            endTime = startTime + lookbackCandles.getFirst().getTimeUnit().getMilliseconds();
        }
        axis.setRange(new Date(startTime), new Date(endTime));

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Candle c : sortedLookback) {
            min = Math.min(min, c.getLow());
            max = Math.max(max, c.getHigh());
        }
        for (ClosePredictionPoint p : real) {
            min = Math.min(min, p.close());
            max = Math.max(max, p.close());
        }
        for (ClosePredictionPoint p : pred) {
            min = Math.min(min, p.close());
            max = Math.max(max, p.close());
        }
        if (Double.isFinite(min) && Double.isFinite(max)) {
            double range = max - min;
            double padding = range > 0 ? range * 0.05 : Math.max(Math.abs(max) * 0.01, 0.0001);
            NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
            rangeAxis.setRange(min - padding, max + padding);
        }

        darkMode(chart);

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(new Dimension(1200, 600));

        JFrame frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.getContentPane().add(chartPanel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public static void showCandleChartWithTradeSnapshots(
            String title,
            List<Candle> candles,
            Symbol symbol,
            @NotNull TradingTelemetry telemetry
    ) {
        if (candles == null || candles.isEmpty()) {
            Vesta.error("No hay velas para mostrar");
            return;
        }
        if (!chartsEnabled()) {
            return;
        }

        List<Candle> sortedCandles = new ArrayList<>(candles);
        sortedCandles.sort(Comparator.comparingLong(Candle::getOpenTime));

        List<TradingTelemetry.TradeSnapshot> trades = telemetry.getTrades();
        List<TradingTelemetry.TradeSnapshot> sortedTrades = new ArrayList<>(trades);
        sortedTrades.sort(Comparator.comparingLong(TradingTelemetry.TradeSnapshot::entryTime));
        List<TradingTelemetry.PendingObjectSnapshot> pendingObjects = new ArrayList<>(telemetry.getOrders());
        pendingObjects.addAll(telemetry.getOrderAlgos());
        pendingObjects.sort(Comparator.comparingLong(TradingTelemetry.PendingObjectSnapshot::openedAt));

        DefaultHighLowDataset candleDataset = buildHighLowDataset(sortedCandles, 0, sortedCandles.size() - 1, title);
        JFreeChart chart = ChartFactory.createCandlestickChart(
                title + " - " + symbol,
                "Fecha",
                "Precio",
                candleDataset,
                true
        );

        XYPlot plot = (XYPlot) chart.getPlot();
        DateAxis axis = (DateAxis) plot.getDomainAxis();
        axis.setDateFormatOverride(new SimpleDateFormat("dd/MM HH:mm"));
        applyBinanceCandleStyle(plot);

        long chartStartTime = sortedCandles.getFirst().getOpenTime();
        long chartEndTime = sortedCandles.getLast().getCloseTime();

        if (!sortedTrades.isEmpty()) {
            // Balance
            XYSeriesCollection balanceDataset = buildBalanceDataset(sortedTrades, chartStartTime, chartEndTime);
            NumberAxis balanceAxis = new NumberAxis("Balance %");
            balanceAxis.setAutoRangeIncludesZero(true);
            balanceAxis.setNumberFormatOverride(new DecimalFormat("0.##"));
            balanceAxis.setLabelPaint(new Color(135, 206, 250));
            balanceAxis.setTickLabelPaint(new Color(135, 206, 250));

            XYLineAndShapeRenderer balanceRenderer = new XYLineAndShapeRenderer(true, false);
            balanceRenderer.setSeriesPaint(0, new Color(100, 180, 255, 115));
            balanceRenderer.setSeriesStroke(0, new BasicStroke(2.2f));

            plot.setRangeAxis(1, balanceAxis);
            plot.setDataset(1, balanceDataset);
            plot.mapDatasetToRangeAxis(1, 1);
            plot.setRenderer(1, balanceRenderer);

            // Trades Lineas de win/loss
            XYSeriesCollection tradePathDataset = buildTradePathDataset(sortedTrades);
            XYLineAndShapeRenderer tradePathRenderer = new XYLineAndShapeRenderer(true, false);
            for (int i = 0; i < sortedTrades.size(); i++) {
                TradingTelemetry.TradeSnapshot trade = sortedTrades.get(i);
                tradePathRenderer.setSeriesPaint(i, trade.winner()
                        ? new Color(46, 188, 132, 190)
                        : new Color(244, 69, 92, 190));
                tradePathRenderer.setSeriesStroke(i, new BasicStroke(1.8f));
            }
            plot.setDataset(2, tradePathDataset);
            plot.setRenderer(2, tradePathRenderer);

            // Trades Iconos de entrada y salida
            XYSeriesCollection tradeMarkerDataset = buildTradeMarkerDataset(sortedTrades);
            XYLineAndShapeRenderer tradeMarkerRenderer = new XYLineAndShapeRenderer(false, true);
            tradeMarkerRenderer.setUseOutlinePaint(true);
            tradeMarkerRenderer.setDefaultOutlinePaint(Color.WHITE);
            tradeMarkerRenderer.setSeriesPaint(0, BINANCE_UP);
            tradeMarkerRenderer.setSeriesShape(0, createTriangleShape(7, true));
            tradeMarkerRenderer.setSeriesPaint(1, BINANCE_DOWN);
            tradeMarkerRenderer.setSeriesShape(1, createTriangleShape(7, false));
            tradeMarkerRenderer.setSeriesPaint(2, new Color(255, 215, 0));
            tradeMarkerRenderer.setSeriesShape(2, new Ellipse2D.Double(-4, -4, 8, 8));
            tradeMarkerRenderer.setSeriesPaint(3, new Color(255, 140, 0));
            tradeMarkerRenderer.setSeriesShape(3, new Rectangle2D.Double(-4, -4, 8, 8));
            plot.setDataset(3, tradeMarkerDataset);
            plot.setRenderer(3, tradeMarkerRenderer);
        }

        XYSeries pendingOrderRangeSeries = null;
        if (!pendingObjects.isEmpty()) {
            PendingOrderDataset pendingOrderDataset = buildPendingOrderDataset(pendingObjects, chartEndTime);
            pendingOrderRangeSeries = pendingOrderDataset.rangeSeries();

            XYLineAndShapeRenderer pendingOrderRenderer = new XYLineAndShapeRenderer(true, false);
            for (int i = 0; i < pendingOrderDataset.dataset().getSeriesCount(); i++) {
                PendingOrderSeriesStyle style = pendingOrderDataset.styles().get(i);
                pendingOrderRenderer.setSeriesPaint(i, getPendingOrderColor(style.typeOrder()));
                pendingOrderRenderer.setSeriesStroke(i, getPendingOrderStroke(style.kind()));
            }

            plot.setDataset(4, pendingOrderDataset.dataset());
            plot.setRenderer(4, pendingOrderRenderer);
        }

        axis.setRange(new Date(chartStartTime), new Date(chartEndTime));
        applyPriceRange(plot, sortedCandles, pendingOrderRangeSeries);
        darkMode(chart);

        Iterator<LegendItem> legend = plot.getLegendItems().iterator();
        LegendItemCollection collection = new LegendItemCollection();
        while (legend.hasNext()) {
            LegendItem item = legend.next();
            if (!item.getLabel().startsWith("Trade ") && !item.getLabel().startsWith("Orden ")){
                collection.add(item);
            }
        }
        plot.setFixedLegendItems(collection);

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setMouseWheelEnabled(true);
        chartPanel.setPreferredSize(new Dimension(1300, 700));

        JFrame frame = new JFrame(title + " - " + symbol);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.getContentPane().add(chartPanel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
         * Clase auxiliar para almacenar datos de TP/SL y ROI
         */
        private record TPSLROIData(float tpMagnitude, float slMagnitude, float roi) {
    }

    /**
     * Crea un heatmap de densidad para visualizar la relación entre TP, SL y ROI
     */
    private static JFreeChart createROIDensityHeatmap(List<TPSLROIData> dataList) {
        try {
            // Crear dataset para gráfico de dispersión 3D usando colores para representar el ROI
            XYSeriesCollection dataset = new XYSeriesCollection();

            // Crear series para diferentes rangos de ROI
            XYSeries highROI = new XYSeries("ROI Alto (>5%)");
            XYSeries mediumROI = new XYSeries("ROI Medio (0-5%)");
            XYSeries lowROI = new XYSeries("ROI Bajo (-5-0%)");
            XYSeries negativeROI = new XYSeries("ROI Negativo (<-5%)");

            for (TPSLROIData data : dataList) {
                if (data.roi() > 5) {
                    highROI.add(data.slMagnitude(), data.tpMagnitude());
                } else if (data.roi() > 0) {
                    mediumROI.add(data.slMagnitude(), data.tpMagnitude());
                } else if (data.roi() > -5) {
                    lowROI.add(data.slMagnitude(), data.tpMagnitude());
                } else {
                    negativeROI.add(data.slMagnitude(), data.tpMagnitude());
                }
            }

            if (highROI.getItemCount() > 0) dataset.addSeries(highROI);
            if (mediumROI.getItemCount() > 0) dataset.addSeries(mediumROI);
            if (lowROI.getItemCount() > 0) dataset.addSeries(lowROI);
            if (negativeROI.getItemCount() > 0) dataset.addSeries(negativeROI);

            // Crear gráfico de dispersión
            JFreeChart chart = ChartFactory.createScatterPlot(
                    "Heatmap: TP vs SL vs ROI",
                    "Stop Loss (puntos base)",
                    "Take Profit (puntos base)",
                    dataset
            );

            XYPlot plot = (XYPlot) chart.getPlot();

            // Configurar colores para cada serie
            plot.getRenderer().setSeriesPaint(0, Color.GREEN);     // Alto ROI
            plot.getRenderer().setSeriesPaint(1, Color.YELLOW);    // Medio ROI
            plot.getRenderer().setSeriesPaint(2, Color.ORANGE);    // Bajo ROI
            plot.getRenderer().setSeriesPaint(3, Color.RED);       // Negativo ROI

            // Añadir líneas de referencia
            plot.addDomainMarker(new ValueMarker(0, Color.GRAY, new BasicStroke(1.0f)));
            plot.addRangeMarker(new ValueMarker(0, Color.GRAY, new BasicStroke(1.0f)));

            return chart;

        } catch (Exception e) {
            Vesta.error("Error creando heatmap: " + e.getMessage());
            e.printStackTrace();
            // Crear un gráfico vacío en caso de error
            return ChartFactory.createScatterPlot(
                    "Heatmap: TP vs SL vs ROI (Error)",
                    "Stop Loss",
                    "Take Profit",
                    new XYSeriesCollection()
            );
        }
    }

    @Contract("_, _, _, _ -> new")
    private static @NotNull DefaultHighLowDataset buildHighLowDataset(
            List<Candle> candles,
            int startIndex,
            int endIndex,
            String seriesKey
    ) {
        int itemCount = endIndex - startIndex + 1;
        Date[] dates = new Date[itemCount];
        double[] highs = new double[itemCount];
        double[] lows = new double[itemCount];
        double[] opens = new double[itemCount];
        double[] closes = new double[itemCount];
        double[] volumes = new double[itemCount];

        for (int i = 0; i < itemCount; i++) {
            Candle candle = candles.get(startIndex + i);
            dates[i] = new Date(candle.getOpenTime());
            opens[i] = candle.getOpen();
            highs[i] = candle.getHigh();
            lows[i] = candle.getLow();
            closes[i] = candle.getClose();
            volumes[i] = candle.getVolumen().baseVolume();
        }
        return new DefaultHighLowDataset(seriesKey, dates, highs, lows, opens, closes, volumes);
    }

    private static XYSeriesCollection buildBalanceDataset(
            List<TradingTelemetry.TradeSnapshot> trades,
            long chartStartTime,
            long chartEndTime
    ) {
        XYSeries balanceSeries = new XYSeries("Balance %", true, false);
        TradingTelemetry.TradeSnapshot firstTrade = trades.getFirst();
        double initialBalance = firstTrade.balanceAfterClose() - firstTrade.netPnl();
        if (!Double.isFinite(initialBalance) || Math.abs(initialBalance) < 1.0E-9) {
            initialBalance = firstTrade.balanceAfterClose();
        }
        if (!Double.isFinite(initialBalance) || Math.abs(initialBalance) < 1.0E-9) {
            initialBalance = 1D;
        }

        double currentBalancePercent = 0D;
        balanceSeries.add(chartStartTime -1, currentBalancePercent);
        for (TradingTelemetry.TradeSnapshot trade : trades) {
            balanceSeries.addOrUpdate(trade.entryTime(), currentBalancePercent);
            currentBalancePercent = ((trade.balanceAfterClose() / initialBalance) - 1D) * 100D;
            balanceSeries.add(trade.exitTime(), currentBalancePercent);
        }
        balanceSeries.add(chartEndTime, currentBalancePercent);

        XYSeriesCollection collection = new XYSeriesCollection();
        collection.addSeries(balanceSeries);
        return collection;
    }

    private static XYSeriesCollection buildTradePathDataset(List<TradingTelemetry.TradeSnapshot> trades) {
        XYSeriesCollection dataset = new XYSeriesCollection();
        int index = 1;
        for (TradingTelemetry.TradeSnapshot trade : trades) {
            XYSeries tradeSeries = new XYSeries("Trade " + index++, true, false);
            tradeSeries.add(trade.entryTime(), trade.entryPrice());
            tradeSeries.add(trade.exitTime(), trade.exitPrice());
            dataset.addSeries(tradeSeries);
        }
        return dataset;
    }

    private static XYSeriesCollection buildTradeMarkerDataset(List<TradingTelemetry.TradeSnapshot> trades) {
        XYSeries longEntries = new XYSeries("Entrada LONG", true, false);
        XYSeries shortEntries = new XYSeries("Entrada SHORT", true, false);
        XYSeries winningExits = new XYSeries("Salida WIN", true, false);
        XYSeries losingExits = new XYSeries("Salida LOSS", true, false);

        for (TradingTelemetry.TradeSnapshot trade : trades) {
            if (trade.direction().isLong()) {
                longEntries.add(trade.entryTime(), trade.entryPrice());
            } else {
                shortEntries.add(trade.entryTime(), trade.entryPrice());
            }

            if (trade.winner()) {
                winningExits.add(trade.exitTime(), trade.exitPrice());
            } else {
                losingExits.add(trade.exitTime(), trade.exitPrice());
            }
        }

        XYSeriesCollection dataset = new XYSeriesCollection();
        dataset.addSeries(longEntries);
        dataset.addSeries(shortEntries);
        dataset.addSeries(winningExits);
        dataset.addSeries(losingExits);
        return dataset;
    }

    private static @NotNull PendingOrderDataset buildPendingOrderDataset(
            @NotNull List<TradingTelemetry.PendingObjectSnapshot> pendingObjects,
            long chartEndTime
    ) {
        XYSeriesCollection dataset = new XYSeriesCollection();
        XYSeries rangeSeries = new XYSeries("Rango ordenes", true, true);
        List<PendingOrderSeriesStyle> styles = new ArrayList<>();

        for (TradingTelemetry.PendingObjectSnapshot pendingObject : pendingObjects) {
            XYSeries series = buildPendingOrderSeries(pendingObject, chartEndTime);
            if (series.isEmpty()) {
                continue;
            }

            dataset.addSeries(series);
            styles.add(new PendingOrderSeriesStyle(pendingObject.kind(), pendingObject.typeOrder()));

            List<TradingManager.PriceSnapshot> history = pendingObject.historiesTriggerPrices();
            if (history.isEmpty()) {
                rangeSeries.add(pendingObject.openedAt(), pendingObject.triggerPrice());
            } else {
                for (TradingManager.PriceSnapshot priceSnapshot : history) {
                    rangeSeries.add(priceSnapshot.date(), priceSnapshot.price());
                }
            }
        }

        return new PendingOrderDataset(dataset, rangeSeries, styles);
    }

    private static @NotNull XYSeries buildPendingOrderSeries(
            @NotNull TradingTelemetry.PendingObjectSnapshot pendingObject,
            long chartEndTime
    ) {
        XYSeries series = new XYSeries("Orden " + pendingObject.kind() + " " + pendingObject.uuid(), true, true);

        List<TradingManager.PriceSnapshot> history = new ArrayList<>(pendingObject.historiesTriggerPrices());
        history.sort(Comparator.comparingLong(TradingManager.PriceSnapshot::date));
        if (history.isEmpty()) {
            history.add(new TradingManager.PriceSnapshot(pendingObject.triggerPrice(), pendingObject.openedAt()));
        }

        TradingManager.PriceSnapshot firstPoint = history.getFirst();
        long currentTime = Math.min(firstPoint.date(), pendingObject.openedAt());
        double currentPrice = firstPoint.price();
        series.add(currentTime, currentPrice);
        for (int i = 1; i < history.size(); i++) {
            TradingManager.PriceSnapshot pointCurrent = history.get(i);
            series.add(pointCurrent.date(), pointCurrent.price());
            TradingManager.PriceSnapshot pointPrev = history.get(i-1);
            series.add(pointCurrent.date()-1, pointPrev.price());
        }
//        long endTime = pendingObject.closedAt() != null ? pendingObject.closedAt() : chartEndTime;
//        if (endTime < currentTime) {
//            endTime = currentTime;
//        }
//        series.add(endTime, currentPrice);
        return series;
    }

    private static @NotNull Color getPendingOrderColor(@NotNull TypeOrder typeOrder) {
        return switch (typeOrder) {
            case STOP, STOP_MARKET -> new Color(244, 69, 92, 200);
            case TAKE_PROFIT, TAKE_PROFIT_MARKET, TRAILING_STOP_MARKET -> new Color(46, 188, 132, 200);
            default -> new Color(255, 196, 64, 200);
        };
    }

    private static @NotNull Stroke getPendingOrderStroke(@NotNull TradingTelemetry.PendingObjectKind kind) {
        if (kind == TradingTelemetry.PendingObjectKind.ORDER_ALGO) {
            return new BasicStroke(2.2f);
        }
        return new BasicStroke(2.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{8.0f, 6.0f}, 0);
    }

    private record PendingOrderSeriesStyle(
            @NotNull TradingTelemetry.PendingObjectKind kind,
            @NotNull TypeOrder typeOrder
    ) {
    }

    private record PendingOrderDataset(
            @NotNull XYSeriesCollection dataset,
            @NotNull XYSeries rangeSeries,
            @NotNull List<PendingOrderSeriesStyle> styles
    ) {
    }

    private static void applyPriceRange(
            @NotNull XYPlot plot,
            List<Candle> candles,
            @Nullable XYSeries extraSeries
    ) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (Candle candle : candles) {
            min = Math.min(min, candle.getLow());
            max = Math.max(max, candle.getHigh());
        }

        if (extraSeries != null && !extraSeries.isEmpty()) {
            min = Math.min(min, extraSeries.getMinY());
            max = Math.max(max, extraSeries.getMaxY());
        }

        if (Double.isFinite(min) && Double.isFinite(max)) {
            double range = max - min;
            double padding = range > 0 ? range * 0.05 : Math.max(Math.abs(max) * 0.01, 0.0001);
            NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
            rangeAxis.setRange(min - padding, max + padding);
        }
    }

    private static Shape createTriangleShape(int size, boolean up) {
        Path2D.Double path = new Path2D.Double();
        if (up) {
            path.moveTo(0, -size);
            path.lineTo(size, size);
            path.lineTo(-size, size);
        } else {
            path.moveTo(0, size);
            path.lineTo(size, -size);
            path.lineTo(-size, -size);
        }
        path.closePath();
        return path;
    }
}
