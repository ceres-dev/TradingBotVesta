package xyz.cereshost.vesta.core.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.common.market.*;
import xyz.cereshost.vesta.common.packet.Utils;
import xyz.cereshost.vesta.core.Main;
import xyz.cereshost.vesta.core.ia.VestaEngine;
import xyz.cereshost.vesta.core.io.setup.LoadDataMethodBinance;
import xyz.cereshost.vesta.core.io.setup.LoadDataMethodLocal;
import xyz.cereshost.vesta.core.io.setup.LoadDataMethodLocalIndex;
import xyz.cereshost.vesta.core.io.setup.LoadDataMethodLocalRange;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

@UtilityClass
public class IOMarket {

    static final int BIN_VERSION = 1;
    private static final int BATCH_SIZE = 50_000;
    public static final String STORAGE_DIR = "data";
    public static final int BUFFER_READ_MB = 50;
    private static final int ZSTD_LEVEL = 1;
    private static final String EXT_ZIP = ".zip";
    private static final String EXT_BIN = ".bin";
    private static final String EXT_ZST = ".zst";
    private static final String EXT_BIN_ZST = ".bin.zst";
    // Base dinámica de referencia para índices diarios.
    // dayIndex=1 => ayer (dataset diario completo más reciente).
    private static final int DEFAULT_LOOKBACK_DAY_INDEX = 1;

    public static @NotNull Market loadMarketsRecentDays(TypeMarket typeMarket, int days, boolean loadTrades) throws InterruptedException, IOException {
        int normalizedDays = Math.max(1, days);
        Market merged = new Market(typeMarket);

        // Cargar desde el día más antiguo al más reciente para mantener orden temporal.
        for (int dayIndex = normalizedDays; dayIndex >= DEFAULT_LOOKBACK_DAY_INDEX; dayIndex--) {
            Market dayMarket;
            dayMarket = loadMarket(typeMarket, new LoadDataMethodLocalIndex(loadTrades, dayIndex));
            if (dayMarket == null) {
                continue;
            }
            if (dayMarket.getCandles().isEmpty() || dayMarket.getTrades().isEmpty()) {
                continue;
            }
            merged.concat(dayMarket);
        }

        if (!merged.getCandles().isEmpty() || !merged.getTrades().isEmpty()) {
            merged.sortd();
        }
        return merged;
    }

    public static @NotNull Market loadMarket(@NotNull TypeMarket type, @NotNull LoadDataMethodBinance loadDataSetup){
        return loadMarketsBinance(type, loadDataSetup.getLimitCandle(), loadDataSetup.getLimitTrade(), loadDataSetup.getLimitDepth());
    }

    public static Market loadMarket(@NotNull TypeMarket type, @NotNull LoadDataMethodLocal loadDataSetup){
        switch (loadDataSetup){
            case LoadDataMethodLocalRange setupLocal -> {
                List<CompletableFuture<Market>> task = new ArrayList<>();
                Market market = new Market(type);
                for (int i = setupLocal.getEndDay(); i >= setupLocal.getStartDay(); i--) {
                    int index = i;
                    task.add(CompletableFuture.supplyAsync(() ->
                            loadMarketLocal(type, index, setupLocal.isLoadTrades()),
                            VestaEngine.EXECUTOR_AUXILIAR_BUILD
                    ));
                }
                for (CompletableFuture<Market> future : task) {
                    try {
                        Market m = future.get();
                        if (m == null) continue;
                        market.concat(m);
                    }catch (InterruptedException | ExecutionException e){
                        Vesta.sendWaringException("error al obtener los datos en el loop", e);
                        e.printStackTrace();
                    }
                }
                market.sortd();
                return market;
            }
            case LoadDataMethodLocalIndex setupLocal -> {
                return loadMarketLocal(type, setupLocal.getIndexDay(), setupLocal.isLoadTrades());
            }
            default -> throw new IllegalStateException("Unexpected value: " + loadDataSetup);
        }
    }

    private static Market loadMarketsBinance(TypeMarket typeMarket,
                                            int limitKlines,
                                            int limitTrade,
                                            int limitDepth
    ){
        final Symbol symbol = typeMarket.symbol();
        final TimeFrameMarket timeFrameMarket = typeMarket.timeFrameMarket();

        Vesta.info("📡 Solicitud de dato a binance del mercado: " + symbol);
        String rawCandles = Utils.getRequest("https://fapi.binance.com/fapi/v1/klines" + "?symbol=" + symbol + "&interval=" + timeFrameMarket.getKeyName() + "&limit=" + limitKlines);
        ObjectMapper mapperCandles = new ObjectMapper(); // https://testnet.binancefuture.com
        JsonNode root1;
        try {
            root1 = mapperCandles.readTree(rawCandles);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        ArrayDeque<Candle> deque = new ArrayDeque<>();
        Vesta.info("📂 Datos recibidos de binance del mercado: " + symbol + " (" + rawCandles.getBytes(StandardCharsets.UTF_8).length / 1024 + "mb)");
        for (JsonNode kline : root1) {
            double baseVolume = kline.get(5).asDouble();
            double quoteVolume = kline.get(7).asDouble();  // USDT
            double takerBuyQuoteVolume = kline.get(10).asDouble(); // USDT agresivo

            double sellQuoteVolume = quoteVolume - takerBuyQuoteVolume;
            double deltaUSDT = takerBuyQuoteVolume - sellQuoteVolume;
            double buyRatio = takerBuyQuoteVolume / quoteVolume;
            deque.add(new Candle(
                    TimeFrameMarket.ONE_MINUTE,
                    kline.get(0).asLong(),
                    kline.get(1).asDouble(), // open
                    kline.get(2).asDouble(), // high
                    kline.get(3).asDouble(), // low
                    kline.get(4).asDouble(), // close
                    new Volumen(quoteVolume, baseVolume, takerBuyQuoteVolume, sellQuoteVolume, deltaUSDT, buyRatio)));
        }
        Market market = new Market(typeMarket);
        ObjectMapper mapperTrade = new ObjectMapper();
        if (limitTrade > 0) {
            JsonNode rootTrade;
            try {
                rootTrade = mapperTrade.readTree(Utils.getRequest("https://fapi.binance.com/fapi/v1/trades" + "?symbol=" + symbol + "&limit=" + limitTrade));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            Deque<Trade> trades = new ArrayDeque<>();
            for (JsonNode trade : rootTrade) {
                double quoteQty = trade.get("quoteQty").asDouble();
                double price = trade.get("price").asDouble();
                boolean isBuyerMaker = trade.get("isBuyerMaker").asBoolean();
                long time = trade.get("time").asLong();
                trades.add(new Trade(time, (float) price, (float) quoteQty, isBuyerMaker));
            }
            market.addTrade(trades);
        }
        if (limitDepth > 0) {
            String rawDepth = Utils.getRequest("https://fapi.binance.com/fapi/v1/depth" + "?symbol=" + symbol + "&limit=" + limitDepth);
            OrderBookRaw orderBookRaw = Main.GSON.fromJson(rawDepth, OrderBookRaw.class);
            Depth depth = new Depth(System.currentTimeMillis(),
                    orderBookRaw.bids().stream().map(list ->
                            new Depth.OrderLevel(Double.parseDouble(list.getFirst()), Double.parseDouble(list.get(1)))).toList(),
                    orderBookRaw.asks().stream().map(list ->
                            new Depth.OrderLevel(Double.parseDouble(list.getFirst()), Double.parseDouble(list.get(1)))).toList()
            );
            market.addDepth(depth);
        }

        market.addCandles(deque);
        return market;
    }

    private static Market loadMarketLocal(@NotNull TypeMarket typeMarket, int dayIndex, boolean loadTrades){
        final Symbol symbol = typeMarket.symbol();
        final TimeFrameMarket timeFrameMarket = typeMarket.timeFrameMarket();

        int normalizedDayIndex = Math.max(1, dayIndex);
        LocalDate targetDate = resolveDateFromDayIndex(normalizedDayIndex);
        int targetYear = targetDate.getYear();
        int targetMonth = targetDate.getMonthValue();
        int targetDay = targetDate.getDayOfMonth();

        try {
            long timeTotal = System.currentTimeMillis();
//            Vesta.info("%d/%02d/%02d (idx=%d) 💾 Leyendo zst local de klines", targetYear, targetMonth, targetDay, normalizedDayIndex);
            File klineFile = ensureFileCached(typeMarket, TypeData.KLINES, targetDate);
            Deque<Candle> candles = parseKlinesFromFile(klineFile);
//            Vesta.info("%d/%02d/%02d (idx=%d) 💾 Leyendo zst local de trades", targetYear, targetMonth, targetDay, normalizedDayIndex);
            Deque<Trade> trades;
            if (loadTrades){
                File tradeFile = ensureFileCached(typeMarket, TypeData.TRADES, targetDate);
                trades = parseTradesFromFile(tradeFile);
            }else {
                trades = new ArrayDeque<>();
            }
//            Vesta.info("%d/%02d/%02d (idx=%d) 💾 Leyendo zst local de metrics", targetYear, targetMonth, targetDay, normalizedDayIndex);
            Deque<Metric> metrics;
            try {
                File metricsFile = ensureFileCached(typeMarket, TypeData.METRICS, targetDate);
                metrics = parseMetricsFromFile(metricsFile);
            } catch (IOException metricsException) {
                Vesta.info("%d/%02d/%02d (idx=%d) No se pudieron cargar metrics: %s",
                        targetYear, targetMonth, targetDay, normalizedDayIndex, metricsException.getMessage());
                metrics = new ArrayDeque<>();
            }
            if (candles.isEmpty()) {
                Vesta.info("Datos incompletos o corruptos para %s en %d/%02d/%02d (idx=%d)", symbol, targetYear, targetMonth, targetDay, normalizedDayIndex);
                return null;
            }
            int sizeCandles = candles.size();
            int sizeTrades = trades.size();
            int sizeMetrics = metrics.size();
//            Vesta.info("%d/%02d/%02d (idx=%d) 🔒 Asegurando orden de los datos", targetYear, targetMonth, targetDay, normalizedDayIndex);
            LinkedHashSet<Candle> candlesSorted = Market.sortd(candles, 10_000, Candle::getOpenTime);
            LinkedHashSet<Trade> tradeSorted = Market.sortd(trades, 10_000, Trade::time);
            LinkedHashSet<Metric> metricSorted = Market.sortd(metrics, 10_000, Metric::getOpenTime);

//            if (loadTrades && !tradeSorted.isEmpty()) {
                // 3. Lógica de CORTE (Sincronización de tiempos)
//                long minTimeCandles = candlesSorted.getFirst().getOpenTime();
//                long maxTimeCandles = candlesSorted.getLast().getOpenTime();
//
//                long minTimeTrades = tradeSorted.getFirst().time();
//                long maxTimeTrades = tradeSorted.getLast().time();
//
//                long commonStart = Math.max(minTimeCandles, minTimeTrades);
//                long commonEnd = Math.min(maxTimeCandles, maxTimeTrades);

//                Vesta.info("%d/%02d/%02d (idx=%d) ✂️ Ajustando %s a ventana comun: %d - %d", targetYear, targetMonth, targetDay, normalizedDayIndex, symbol, commonStart, commonEnd);
                // borrar por inicio
//                while (!candlesSorted.isEmpty() && candlesSorted.getFirst().getOpenTime() < commonStart) {
//                    candlesSorted.removeFirst();
//                }
//                // borrar por final
//                while (!candlesSorted.isEmpty() && candlesSorted.getLast().getOpenTime() > commonEnd) {
//                    candlesSorted.removeLast();
//                }

                // mismo para trades
//                while (!tradeSorted.isEmpty() && tradeSorted.getFirst().time() < commonStart) {
//                    tradeSorted.removeFirst();
//                }
//                while (!tradeSorted.isEmpty() && tradeSorted.getLast().time() > commonEnd) {
//                    tradeSorted.removeLast();
//                }
//                while (!metricSorted.isEmpty() && metricSorted.getFirst().getOpenTime() < commonStart) {
//                    metricSorted.removeFirst();
//                }
//                while (!metricSorted.isEmpty() && metricSorted.getLast().getOpenTime() > commonEnd) {
//                    metricSorted.removeLast();
//                }
//            } else if (loadTrades) {
//                Vesta.info("%d/%02d/%02d (idx=%d) Sin trades para ajustar ventana comun", targetYear, targetMonth, targetDay, normalizedDayIndex);
//            }

            final Market market = new Market(symbol, timeFrameMarket);
            market.setCandles(candlesSorted);
            market.setTrade(tradeSorted);
            market.setMetrics(metricSorted);
//            Vesta.info("Metrics cargadas para %s: %d", symbol, sizeMetrics);

//            Vesta.info("%d/%02d/%02d (idx=%d) 🟩 Mercado cargado desde DISCO: %s (C: %d, T: %d) en %.2fs",
//                    targetYear, targetMonth, targetDay, normalizedDayIndex, symbol, sizeCandles, sizeTrades, (float) (System.currentTimeMillis() - timeTotal) / 1000);
            return market;
        }catch (IOException e){
            Vesta.sendWaringException("error al cargar los datos de forma local", e);
            return null;
        }

    }

    public static LocalDate resolveDateFromDayIndex(int dayIndex) {
        int normalized = Math.max(1, dayIndex);
        return getReferenceBaseDate().minusDays(normalized - 1L);
    }

    public static int resolveDayIndex(LocalDate date) {
        long days = ChronoUnit.DAYS.between(date, getReferenceBaseDate());
        return (int) days + 1;
    }

    public static YearMonth resolveMonthFromIndex(int monthIndex) {
        int normalized = Math.max(1, monthIndex);
        return YearMonth.from(getReferenceBaseDate()).minusMonths(normalized - 1L);
    }

    private static LocalDate getReferenceBaseDate() {
        // Evita pedir archivos del día en curso (aún incompletos).
        return LocalDate.now().minusDays(1);
    }


    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static File ensureFileCached(TypeMarket typeMarket, @NotNull TypeData type, @NotNull LocalDate date) throws IOException {
        final Symbol symbol = typeMarket.symbol();
        final TimeFrameMarket timeFrameMarket = typeMarket.timeFrameMarket();

        String monthStr = String.format("%02d", date.getMonthValue());
        String dayStr = String.format("%02d", date.getDayOfMonth());
        String dateStr = String.format("%d-%s-%s", date.getYear(), monthStr, dayStr);

        // Nombre del archivo segun convencion de Binance (daily)
        String baseName = switch (type){
            case TRADES -> String.format("%s-trades-%s", symbol, dateStr);
            case KLINES ->  String.format("%s-%s-%s", symbol, timeFrameMarket.getKeyName(), dateStr);
            case METRICS -> String.format("%s-metrics-%s", symbol, dateStr);
            case DEPTH -> throw new UnsupportedOperationException();
        };

        String fileNameZip = baseName + EXT_ZIP;
        String fileNameBin = baseName + EXT_BIN;
        String fileNameBinZst = baseName + EXT_BIN_ZST;

        // Estructura: ./data/ETHUSDT/klines/2025-12/ETHUSDT-1m-2025-12-01.bin.zst
        File dir = new File(STORAGE_DIR + File.separator + symbol + File.separator + type.name().toLowerCase(Locale.ROOT) + File.separator + date.getYear() + "-" + monthStr);
        if (!dir.exists()) {
            dir.mkdirs(); // Crea la estructura de carpetas si no existe
        }

        File targetFileZip = new File(dir, fileNameZip);
        File targetFileBin = new File(dir, fileNameBin);
        File targetFileBinZst = new File(dir, fileNameBinZst);

        ParseSerializable<?> serializable = switch (type){
            case KLINES -> new KlinesSerializable();
            case TRADES -> new TradeSerializable();
            case METRICS -> new MetricsSerializable();
            case DEPTH -> throw new UnsupportedOperationException();
        };

        if (targetFileBin.exists() && targetFileBin.length() > 0) {
            return targetFileBin;
        } else if (targetFileBinZst.exists() && targetFileBinZst.length() > 0) {
            return targetFileBinZst;
        } else if (targetFileZip.exists() && targetFileZip.length() > 0) {
            buildSourceBinFromZst(targetFileZip, targetFileBinZst, serializable);
            safeDelete(targetFileZip);
            return targetFileBinZst;
        }

        // Construir URL de descarga
        String urlTypePath = type.name().toLowerCase(Locale.ROOT); // URL path segment
        String urlInterval = type.equals(TypeData.KLINES) ? "/" + timeFrameMarket.getKeyName() : ""; // Trades no tienen intervalo en la URL

        String urlString = String.format("https://data.binance.vision/data/futures/um/daily/%s/%s%s/%s",
                urlTypePath, symbol, urlInterval, fileNameZip);

        Vesta.info("📥 Descargando nuevo archivo: " + fileNameZip + " (" + urlString + ")");

        try (InputStream in = new URI(urlString).toURL().openStream()) {
            Files.copy(in, targetFileZip.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | URISyntaxException e) {
            // Si falla la descarga, borramos el archivo vacio/corrupto para no romper ejecuciones futuras
            if (targetFileZip.exists()) targetFileZip.delete();
            throw new IOException("Fallo al descargar " + urlString, e);
        }
        Vesta.info("✅ Descargando completada: " + fileNameZip);
        buildSourceBinFromZst(targetFileZip, targetFileBinZst, serializable);
        safeDelete(targetFileZip);

        return targetFileBinZst;
    }

    /**
     * Cambia el nombre de referencia al binario comprimido.
     * @param dataFile el archivo base (zip/bin/bin.zst)
     * @return el nombre del binario (bin o bin.zst)
     */
    public static String binEntryName(File dataFile) {
        String name = dataFile.getName();
        if (name.endsWith(EXT_BIN_ZST)) {
            return name;
        }
        if (name.endsWith(EXT_ZIP)) {
            name = name.substring(0, name.length() - EXT_ZIP.length());
        }
        if (name.endsWith(EXT_ZST)) {
            name = name.substring(0, name.length() - EXT_ZST.length());
        }
        if (name.endsWith(EXT_BIN)) {
            return name;
        }
        return name + EXT_BIN_ZST;
    }

    /**
     * Transforma la referencia de un archivo base a binario.
     * @param dataFile el archivo base
     * @return el archivo con la extension .bin o .bin.zst
     */
    private static File binFileForZip(File dataFile) {
        File parent = dataFile.getParentFile();
        String name = binEntryName(dataFile);
        return parent == null ? new File(name) : new File(parent, name);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    private static Deque<Candle> parseKlinesFromFile(File file) {
        Deque<Candle> list = new ArrayDeque<>();
        KlinesSerializable parse = new KlinesSerializable();

        Deque<Candle> cached1 = parseFromBin(binFileForZip(file), parse);
        if (cached1 != null) {
            return cached1;
        }
        if (!file.exists()) return list;
        if (file.getName().endsWith(EXT_ZIP)) {
            Deque<Candle> cached2 = parseFromBinInZip(file, parse);
            if (cached2 != null) {
                return cached2;
            }
        }

        return parseFromCSVandWriteBin(file, parse, parse);
    }

    private static Deque<Trade> parseTradesFromFile(File file) {
        Deque<Trade> list = new ArrayDeque<>();
        TradeSerializable parseCSV = new TradeSerializable();

        Deque<Trade> cached1 = parseFromBin(binFileForZip(file), parseCSV);
        if (cached1 != null) {
            return cached1;
        }
        if (!file.exists()) return list;
        // En el caso de que .bin exista dentro del .zip (legacy)
        if (file.getName().endsWith(EXT_ZIP)) {
            Deque<Trade> cached2 = parseFromBinInZip(file, parseCSV);
            if (cached2 != null) {
                return cached2;
            }
        }

        return parseFromCSVandWriteBin(file, parseCSV, parseCSV);
    }

    private static Deque<Metric> parseMetricsFromFile(File file) {
        Deque<Metric> list = new ArrayDeque<>();
        MetricsSerializable parseCSV = new MetricsSerializable();

        Deque<Metric> cached1 = parseFromBin(binFileForZip(file), parseCSV);
        if (cached1 != null) {
            return cached1;
        }
        if (!file.exists()) return list;
        if (file.getName().endsWith(EXT_ZIP)) {
            Deque<Metric> cached2 = parseFromBinInZip(file, parseCSV);
            if (cached2 != null) {
                return cached2;
            }
        }

        return parseFromCSVandWriteBin(file, parseCSV, parseCSV);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    public <T> void drainTask(Deque<FutureTask<List<T>>> tasks, Deque<T> list) {
        try {
            FutureTask<List<T>> task = tasks.removeFirst();
            List<T> trades = task.get();
            if (trades != null && !trades.isEmpty()) {
                list.addAll(trades);
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> @NotNull Deque<T> parseFromCSVandWriteBin(File file, SerializableCSV<T> SerializableCSV, SerializableBin<T> serializableBin) {
        Deque<T> list = new ArrayDeque<>();
        if (!file.exists()) return list;
        if (!file.getName().endsWith(EXT_ZIP)) return list;


        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        int maxInFlight = threads * 2;
        int batchSize = BATCH_SIZE;
        Deque<FutureTask<List<T>>> tasks = new ArrayDeque<>(maxInFlight);

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(file), (1 << 20) * BUFFER_READ_MB))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".csv")) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(zis), (1 << 20) * BUFFER_READ_MB);
                    String line;
                    List<String> batch = new ArrayList<>(batchSize);
                    while ((line = br.readLine()) != null) {
                        if (line.isEmpty() || Character.isLetter(line.charAt(0))) continue; // Skip Header
                        batch.add(line);
                        if (batch.size() >= batchSize) {
                            SerializableCSV.submitBatch(tasks, batch);
                            batch = new ArrayList<>(batchSize);
                            if (tasks.size() >= maxInFlight) {
                                drainTask(tasks, list);
                            }
                        }
                    }
                    if (!batch.isEmpty()) {
                        SerializableCSV.submitBatch(tasks, batch);
                    }
                }
            }
        } catch (Exception e) {
            Vesta.info("Error leyendo csv locales: " + e.getMessage());
        }
        try {
            while (!tasks.isEmpty()) {
                drainTask(tasks, list);
            }
        } catch (Exception e) {
            Vesta.info("Error procesando datos locales: " + e.getMessage());
        }
        boolean wrote = false;
        if (!list.isEmpty()) {
            try {
                String entryName = binEntryName(file);
                rewriteZipWithBinEntry(file, entryName, out -> {
                    out.writeInt(serializableBin.getMagic());
                    out.writeInt(BIN_VERSION);
                    serializableBin.writeMetaDataBin(out, list.getFirst());
                    for (T source : list) {
                        serializableBin.writeBin(out, source);
                    }
                });
                wrote = true;
            } catch (Exception e) {
                Vesta.info("Error guardando binario: " + e.getMessage());
            }
        }
        if (wrote) {
            safeDelete(file);
        }
        return list;
    }

    private static <T> @Nullable Deque<T> parseFromBin(File binFile, SerializableBin<T> parseMethod) {
        File candidate = binFile;
        if (candidate.getName().endsWith(EXT_BIN_ZST)) {
            File raw = new File(candidate.getParentFile(),
                    candidate.getName().substring(0, candidate.getName().length() - EXT_ZST.length()));
            if (raw.exists() && raw.length() > 0) {
                candidate = raw;
            }
        }
        if (!candidate.exists() || candidate.length() == 0) {
            return null;
        }
        try (DataInputStream in = openBinInputStream(candidate)) {
            return readBin(parseMethod, in);
        } catch (Exception e) {
            Vesta.info("Error leyendo binario: " + e.getMessage());
            return null;
        }
    }

    private static <T> @Nullable Deque<T> parseFromBinInZip(File zipFile, SerializableBin<T> parse) {
        String entryName = binEntryName(zipFile);
        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null && entryName.endsWith(EXT_BIN_ZST)) {
                String legacyEntry = entryName.substring(0, entryName.length() - EXT_ZST.length());
                entry = zip.getEntry(legacyEntry);
            }
            if (entry == null) {
                return null;
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(zip.getInputStream(entry), (1 << 20) * BUFFER_READ_MB))) {
                return readBin(parse, in);
            }
        } catch (Exception e) {
            Vesta.info("Error leyendo Trades binario: " + e.getMessage());
            return null;
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    private static <T> @Nullable Deque<T> readBin(@NotNull SerializableBin<T> parseMethod, @NotNull DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != parseMethod.getMagic()) {
            return null;
        }
        int version = in.readInt();
        if (version != BIN_VERSION) {
            return null;
        }
        parseMethod.readMetaDataBin(in);
        Deque<T> list = new ArrayDeque<>(5_000);
        while (true) {
            try {
                list.add(parseMethod.readBin(in));
            } catch (EOFException eof) {
                break;
            }
        }
        return list;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////


    @Contract("_ -> new")
    private static @NotNull DataInputStream openBinInputStream(File binFile) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(binFile), (1 << 20) * BUFFER_READ_MB);
        if (binFile.getName().endsWith(EXT_ZST)) {
            in = new ZstdInputStream(in);
        }
        // Ensure mark/reset support for optional metadata reads.
        in = new BufferedInputStream(in, 8 * 1024);
        return new DataInputStream(in);
    }

    private static @NotNull DataOutputStream openBinOutputStream(File binFile, boolean useZstd) throws IOException {
        OutputStream out = new BufferedOutputStream(new FileOutputStream(binFile), (1 << 20) * BUFFER_READ_MB);
        if (useZstd) {
            ZstdOutputStream zOut = new ZstdOutputStream(out);
            zOut.setLevel(ZSTD_LEVEL);
            zOut.setWorkers(Math.min(8, Runtime.getRuntime().availableProcessors()));
            out = zOut;
        }
        return new DataOutputStream(out);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static void rewriteZipWithBinEntry(@NotNull File zipFile, String entryName, @NotNull BinWriter writer) throws IOException {
        File parent = zipFile.getParentFile();
        File binFile = parent == null ? new File(entryName) : new File(parent, entryName);
        Path temp = Files.createTempFile(parent == null ? Paths.get(".") : parent.toPath(), binFile.getName(), ".tmp");
        try {
            try (DataOutputStream out = openBinOutputStream(temp.toFile(), binFile.getName().endsWith(EXT_ZST))) {
                writer.write(out);
                out.flush();
            }
            Files.move(temp, binFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void copyStream(@NotNull InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[(1 << 20) * BUFFER_READ_MB];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static void safeDelete(File file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
            // no-op
        }
    }

    private static <T> void buildSourceBinFromZst(File zipFile, @NotNull File binFile, ParseSerializable<T> serializable) throws IOException {
        if (binFile.getParentFile() != null) {
            Files.createDirectories(binFile.getParentFile().toPath());
        }
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile), (1 << 20) * BUFFER_READ_MB));
             DataOutputStream out = openBinOutputStream(binFile, binFile.getName().endsWith(EXT_ZST))) {
            out.writeInt(serializable.getMagic());
            out.writeInt(BIN_VERSION);
            boolean hasMeta = false;
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".csv")) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(zis), (1 << 20) * BUFFER_READ_MB);
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.isEmpty() || Character.isLetter(line.charAt(0))) continue; // Skip Header
                        T source = serializable.parseLine(line);
                        if (source == null) {
                            continue;
                        }
                        if (!hasMeta) {
                            serializable.writeMetaDataBin(out, source);
                            hasMeta = true;
                        }
                        serializable.writeBin(out, source);
                    }
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static void extractFirstBin(Path folder) throws IOException {
        Vesta.info("Comenzando extraccion en " + folder);
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("No es una carpeta");
        }
        boolean extracted = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*" + EXT_BIN_ZST)) {
            for (Path binPath : stream) {
                extracted = true;
                Vesta.info("Extrayendo de " + binPath.toString());
                extractBinFromZst(binPath);
            }
        }
        if (!extracted) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*" + EXT_ZIP)) {
                for (Path zipPath : stream) {
                    Vesta.info("Extrayendo de " + zipPath.toString());
                    extractBinFromZip(zipPath);
                }
            }
        }
    }

    private static void extractBinFromZst(@NotNull Path binZstPath) throws IOException {
        String name = binZstPath.getFileName().toString();
        String outName = name.endsWith(EXT_ZST) ? name.substring(0, name.length() - EXT_ZST.length()) : name;
        Path outputPath = binZstPath.getParent().resolve(outName);
        try (InputStream in = new ZstdInputStream(new BufferedInputStream(Files.newInputStream(binZstPath), (1 << 20) * BUFFER_READ_MB));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(
                     outputPath,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING), (1 << 20) * BUFFER_READ_MB)) {
            copyStream(in, out);
        }
    }

    private static void extractBinFromZip(Path zipPath) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();

                if (!entry.isDirectory() && entry.getName().endsWith(".bin")) {

                    Path outputPath = zipPath.getParent()
                            .resolve(Paths.get(entry.getName()).getFileName());

                    try (InputStream in = zipFile.getInputStream(entry);
                         OutputStream out = Files.newOutputStream(
                                 outputPath,
                                 StandardOpenOption.CREATE,
                                 StandardOpenOption.TRUNCATE_EXISTING)) {

                        in.transferTo(out);
                    }

                    // Solo el primer .bin
                    break;
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    public interface SerializableBin<T> {
        void writeMetaDataBin(DataOutput out, @NotNull T source)throws IOException;
        void writeBin(DataOutput out, T source) throws IOException;
        T readBin(DataInputStream in) throws IOException;
        void readMetaDataBin(DataInputStream in) throws IOException;
        int getMagic();
    }

    public interface SerializableCSV<T> {
        T parseLine(String line);

        default void submitBatch(Deque<FutureTask<List<T>>> tasks, List<String> batch) {
            List<String> batchCopy = new ArrayList<>(batch);
            FutureTask<List<T>> task = new FutureTask<>(() -> {
                List<T> sources = new ArrayList<>(batchCopy.size());
                for (String line : batchCopy) {
                    T source = parseLine(line);
                    sources.add(source);
                }
                return sources;
            });
            tasks.addLast(task);
            VestaEngine.EXECUTOR_AUXILIAR_BUILD.execute(task);
        }
    }

    @FunctionalInterface
    public interface BinWriter {
        void write(DataOutputStream out) throws IOException;
    }

    private record OrderBookRaw(List<List<String>> bids, List<List<String>> asks) {}

    private enum TypeData{
        KLINES,
        TRADES,
        DEPTH,
        METRICS
    }
}
