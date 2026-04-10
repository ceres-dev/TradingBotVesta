package xyz.cereshost.vesta.core.io;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.common.market.TimeFrameMarket;
import xyz.cereshost.vesta.common.market.Volumen;
import xyz.cereshost.vesta.common.market.Candle;

import java.io.*;
import java.util.Objects;

public class KlinesSerializable implements ParseSerializable<Candle> {

    private static final int META_MAGIC_TIME_UNIT = 0x54554D31; // "TUM1"
    private TimeFrameMarket timeFrameMarket = null;

    @Override
    public void writeMetaDataBin(DataOutput out, @NotNull Candle source) throws IOException {
        TimeFrameMarket sourceTimeUnit = source.getTimeUnit();
        out.writeInt(META_MAGIC_TIME_UNIT);
        out.writeInt(sourceTimeUnit.ordinal());
    }

    @Override
    public void writeBin(DataOutput out, Candle candle) throws IOException {
        Volumen vol = candle.getVolumen();
        out.writeLong(candle.getOpenTime());
        out.writeDouble(candle.getOpen());
        out.writeDouble(candle.getHigh());
        out.writeDouble(candle.getLow());
        out.writeDouble(candle.getClose());
        out.writeDouble(vol.quoteVolume());
        out.writeDouble(vol.baseVolume());
        out.writeDouble(vol.takerBuyQuoteVolume());
        out.writeDouble(vol.sellQuoteVolume());
        out.writeDouble(vol.deltaUSDT());
        out.writeDouble(vol.buyRatio());
    }

    @Override
    public Candle readBin(DataInputStream in) throws IOException {
        long openTime = in.readLong();
        double open = in.readDouble();
        double high = in.readDouble();
        double low = in.readDouble();
        double close = in.readDouble();
        double quoteVolume = in.readDouble();
        double baseVolume = in.readDouble();
        double takerBuyQuoteVolume = in.readDouble();
        double sellQuoteVolume = in.readDouble();
        double deltaUSDT = in.readDouble();
        double buyRatio = in.readDouble();

        return new Candle(
                Objects.requireNonNull(timeFrameMarket),
                openTime,
                open,
                high,
                low,
                close,
                new Volumen(quoteVolume, baseVolume, takerBuyQuoteVolume, sellQuoteVolume, deltaUSDT, buyRatio)
        );
    }

    @Override
    public void readMetaDataBin(DataInputStream in) throws IOException {
        if (!in.markSupported()) {
            return;
        }

        in.mark(Integer.BYTES * 2);
        int magic = in.readInt();
        if (magic != META_MAGIC_TIME_UNIT) {
            in.reset();
            return;
        }

        int ordinal = in.readInt();
        TimeFrameMarket[] values = TimeFrameMarket.values();
        if (ordinal >= 0 && ordinal < values.length) {
            timeFrameMarket = values[ordinal];
        }
    }

    @Override
    public int getMagic() {
        return 0x4B4C4E31;
    }

    @Override
    public Candle parseLine(String line) {
        String[] p = line.split(",");
        double quoteVolume = Double.parseDouble(p[7]);
        double takerBuyQuoteVolume = Double.parseDouble(p[10]);

        long openTime = Long.parseLong(p[0]);
        long closeTime = Long.parseLong(p[6]);
        return new Candle(
                TimeFrameMarket.parse(openTime, closeTime),
                openTime, // Open time
                Double.parseDouble(p[1]), // Open
                Double.parseDouble(p[2]), // High
                Double.parseDouble(p[3]), // Low
                Double.parseDouble(p[4]), // Close
                new Volumen(quoteVolume, Double.parseDouble(p[5]), takerBuyQuoteVolume,
                        quoteVolume - takerBuyQuoteVolume,
                        takerBuyQuoteVolume - (quoteVolume - takerBuyQuoteVolume),
                        (quoteVolume == 0) ? 0 : takerBuyQuoteVolume / quoteVolume)
        );
    }
}
