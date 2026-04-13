package xyz.cereshost.vesta.core.io;

import org.jetbrains.annotations.NotNull;
import xyz.cereshost.vesta.common.market.Metric;

import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class MetricsSerializable implements ParseSerializable<Metric> {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void writeMetaDataBin(DataOutput out, @NotNull Metric source) throws IOException {

    }

    @Override
    public void writeBin(DataOutput out, Metric source) throws IOException {
        out.writeLong(source.getOpenTime());
        out.writeDouble(source.getSumOpenInterest());
        out.writeDouble(source.getSumOpenInterestValue());
        out.writeDouble(source.getCountTopTradesLongShortRatio());
        out.writeDouble(source.getCountTradesLongShortRatio());
    }

    @Override
    public Metric readBin(DataInputStream in) throws IOException {
        long createTime = in.readLong();
        double sumOpenInterest = in.readDouble();
        double sumOpenInterestValue = in.readDouble();
        double countTopTradesLongShortRatio = in.readDouble();
        double countTradesLongShortRatio = in.readDouble();
        return new Metric(
                createTime,
                sumOpenInterest,
                sumOpenInterestValue,
                countTopTradesLongShortRatio,
                countTradesLongShortRatio
        );
    }

    @Override
    public void readMetaDataBin(DataInputStream in) throws IOException {

    }

    @Override
    public int getMagic() {
        return 0x4D455431;
    }

    @Override
    public Metric parseLine(String line) {
        String[] p = line.split(",");
        long createTime = LocalDateTime.parse(p[0], DATE_FORMATTER).toInstant(ZoneOffset.UTC).toEpochMilli();
        return new Metric(
                createTime,
                Double.parseDouble(p[2]),
                Double.parseDouble(p[3]),
                Double.parseDouble(p[4]),
                Double.parseDouble(p[6])
        );
    }
}
