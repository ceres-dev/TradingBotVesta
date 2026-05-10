package xyz.cereshost.vesta.core.utils;

import lombok.Getter;
import lombok.Setter;

public class LoaderIndicator {

    private final int often;
    private int count;
    @Getter @Setter
    private String label;

    public LoaderIndicator(int often) {
        this.often = Math.max(often, 1);
    }

    public void printAndNexStep(){
        print();
        nextStep();
    }

    public void print(){
        if (0==(count % often)){
            switch(count%4){
                case 0 -> System.out.print("\r| " + label);
                case 1 -> System.out.print("\r/ " + label);
                case 2 -> System.out.print("\r- " + label);
                case 3 -> System.out.print("\r\\ " + label);
            }
        }
    }

    public void nextStep(){
        count++;
    }


    public void done(){
        System.out.print("\rDone! " + label +"\n");
    }

    public void clearLine() {
        System.out.print("\r" + " ".repeat(100) + "\r");
    }
}
