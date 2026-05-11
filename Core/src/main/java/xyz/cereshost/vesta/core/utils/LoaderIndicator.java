package xyz.cereshost.vesta.core.utils;

import lombok.Getter;
import lombok.Setter;

public class LoaderIndicator {

    private final int often;
    private int count;
    private int countPrint;
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
            switch(countPrint%25) {
                case 0  -> System.out.print("\r⠁ " + label);
                case 1  -> System.out.print("\r⠂ " + label);
                case 2  -> System.out.print("\r⠄ " + label);
                case 3  -> System.out.print("\r⡀ " + label);
                case 4  -> System.out.print("\r⢀ " + label);
                case 5  -> System.out.print("\r⠠ " + label);
                case 6  -> System.out.print("\r⠐ " + label);
                case 7  -> System.out.print("\r⠈ " + label);
                case 9  -> System.out.print("\r⠁ " + label);
                case 10 -> System.out.print("\r⠃ " + label);
                case 11 -> System.out.print("\r⠇ " + label);
                case 12 -> System.out.print("\r⡇ " + label);
                case 13 -> System.out.print("\r⣇ " + label);
                case 14 -> System.out.print("\r⣧ " + label);
                case 15 -> System.out.print("\r⣷ " + label);
                case 16 -> System.out.print("\r⣿ " + label);
                case 17 -> System.out.print("\r⣾ " + label);
                case 18 -> System.out.print("\r⣼ " + label);
                case 19 -> System.out.print("\r⣸ " + label);
                case 20 -> System.out.print("\r⢸ " + label);
                case 21 -> System.out.print("\r⠸ " + label);
                case 22 -> System.out.print("\r⠸ " + label);
                case 23 -> System.out.print("\r⠘ " + label);
                case 24 -> System.out.print("\r⠈ " + label);
            }
            countPrint++;
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
