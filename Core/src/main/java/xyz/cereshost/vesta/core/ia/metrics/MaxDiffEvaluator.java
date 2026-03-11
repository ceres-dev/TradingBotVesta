package xyz.cereshost.vesta.core.ia.metrics;

public class MaxDiffEvaluator extends AbstractDiffEvaluator {

    public MaxDiffEvaluator() {
        this("max_diff");
    }

    public MaxDiffEvaluator(String name) {
        super(name, 0);
    }
}
