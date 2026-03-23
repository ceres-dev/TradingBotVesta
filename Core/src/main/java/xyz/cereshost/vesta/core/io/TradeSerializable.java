package xyz.cereshost.vesta.core.io;

import xyz.cereshost.vesta.core.ia.VestaEngine;
import xyz.cereshost.vesta.common.market.Trade;

import java.io.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.FutureTask;

import static xyz.cereshost.vesta.core.io.IOMarket.*;

public class TradeSerializable implements ParseSerializable<Trade> {

    @Override
    public void writeBin(DataOutput out, Trade trade) throws IOException {
        out.writeLong(trade.time());
        out.writeDouble(trade.price());
        out.writeDouble(trade.qty());
        out.writeBoolean(trade.isBuyerMaker());
    }

    @Override
    public Deque<Trade> readBin(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != getMagic()) {
            return null;
        }
        int version = in.readInt();
        if (version != BIN_VERSION) {
            return null;
        }
        Deque<Trade> list = new ArrayDeque<>(250_000);
        while (true) {
            try {
                long time = in.readLong();
                double price = in.readDouble();
                double qty = in.readDouble();
                boolean isBuyerMaker = in.readBoolean();
                list.add(new Trade(time, (float) price, (float) qty, isBuyerMaker));
            } catch (EOFException eof) {
                break;
            }
        }
        return list;
    }

    @Override
    public int getMagic() {
        return 0x54524431;
    }

    @Override
    public Trade parseLine(String line) {
        int p0 = line.indexOf(',');
        int p1 = line.indexOf(',', p0 + 1);
        int p2 = line.indexOf(',', p1 + 1);
        int p3 = line.indexOf(',', p2 + 1);
        int p4 = line.indexOf(',', p3 + 1);
        int p5 = line.indexOf(',', p4 + 1);
        if (p0 <= 0 || p1 <= 0 || p2 <= 0 || p3 <= 0 || p4 <= 0) {
            return null;
        }
        if (p5 == -1) p5 = line.length(); // Por si es la ultima columna

        return new Trade(
                Long.parseLong(line.substring(p3 + 1, p4)),        // time (col 4)
                Float.parseFloat(line.substring(p0 + 1, p1)),     // price (col 1)
                Float.parseFloat(line.substring(p1 + 1, p2)),     // qty (col 2)
                "true".equals(line.substring(p4 + 1, p5)) // isBuyerMaker (col 5)
        );
    }
}
