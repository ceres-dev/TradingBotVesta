package xyz.cereshost.vesta.core.io;

import xyz.cereshost.vesta.common.market.TimeUnitMarket;
import xyz.cereshost.vesta.common.market.Volumen;
import xyz.cereshost.vesta.common.market.Candle;

import java.io.*;
import java.util.ArrayDeque;
import java.util.Deque;

import static xyz.cereshost.vesta.core.io.IOMarket.*;

public class KlinesSerializable implements ParseSerializable<Candle> {

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
    public Deque<Candle> readBin(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != getMagic()) {
            return null;
        }
        int version = in.readInt();
        if (version != BIN_VERSION) {
            return null;
        }
        Deque<Candle> list = new ArrayDeque<>(44_000);
        while (true) {
            try {
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
                list.add(new Candle(
                        TimeUnitMarket.ONE_MINUTE, // TODO: Guardar la unidad de tiempo dentro del bin
                        openTime,
                        open,
                        high,
                        low,
                        close,
                        new Volumen(quoteVolume, baseVolume, takerBuyQuoteVolume, sellQuoteVolume, deltaUSDT, buyRatio)
                ));
            } catch (EOFException eof) {
                break;
            }
        }
        return list;
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
        long closeTime = Long.parseLong(p[5]);
        return new Candle(
                TimeUnitMarket.parse(openTime, closeTime),
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
