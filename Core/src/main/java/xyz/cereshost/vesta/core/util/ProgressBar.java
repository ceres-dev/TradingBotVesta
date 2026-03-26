package xyz.cereshost.vesta.core.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@RequiredArgsConstructor
public class ProgressBar {

    private final int finalValue;
    private int currentValue;

    public void addValue(int value) {
        this.currentValue += value;
    }

    public void increaseValue() {
        this.currentValue++;
    }

    public void print(){
        float progress = (float) currentValue / (float) finalValue;
        System.out.print(String.format("\r[%s] %.3f%% %,d/%,d            ",
                "#".repeat((int) (progress * 100)) + " ".repeat((int) (Math.abs(progress - 1) * 100)),
                progress*100,
                currentValue,
                finalValue
        ));
    }


}
