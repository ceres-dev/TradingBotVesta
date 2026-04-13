package xyz.cereshost.vesta.core.utils;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.checkerframework.checker.units.qual.A;
import xyz.cereshost.vesta.core.Main;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Getter
@Setter
@RequiredArgsConstructor
public class ProgressBar {

    private final int finalValue;
    private int currentValue;

    public synchronized void addValue(int value) {
        this.currentValue += value;
    }

    public synchronized void increaseValue() {
        this.currentValue++;
    }

    public synchronized void print(){
        float progress = (float) currentValue / (float) finalValue;
        System.out.print(String.format("\r[%s] %.3f%% %,d/%,d            ",
                "#".repeat((int) (progress * 100)) + " ".repeat((int) (Math.abs(progress - 1) * 100)),
                progress*100,
                currentValue,
                finalValue
        ));
    }

    public void printAsync(){
        printAsync(Main.EXECUTOR);
    }

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile boolean allowPrint = true;

    public synchronized void printAsync(ScheduledExecutorService executor){
        if (!allowPrint) return;
        allowPrint = false;
        executor.execute(this::print);
        executor.schedule(() -> {
            allowPrint = true;
        }, 250, TimeUnit.MILLISECONDS);
    }


}
