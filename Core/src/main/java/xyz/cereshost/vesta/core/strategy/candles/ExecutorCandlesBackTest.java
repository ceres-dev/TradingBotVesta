package xyz.cereshost.vesta.core.strategy.candles;

import lombok.Data;
import lombok.EqualsAndHashCode;
import xyz.cereshost.vesta.core.trading.TradingManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class ExecutorCandlesBackTest implements ExecutorCandles {

    private final ArrayList<Stack<?>> candles = new ArrayList<>();

    private long currentTime;

    @Override
    public ExecutorCandles pause(long milliseconds) {
        candles.add(new StackPause(milliseconds));
        return this;
    }

    @Override
    public ExecutorCandles setStep(String step) {
        candles.add(new StackSetStep(step));
        return this;
    }

    @Override
    public ExecutorCandles move(String step) {
        candles.add(new StackMove(step));
        return this;
    }

    @Override
    public ExecutorCandles died() {
        candles.add(new StackDied());
        return this;
    }

    @Override
    public ExecutorCandles execute(Consumer<TradingManager> consumer) {
        candles.add(new StackExecute(consumer));
        return this;
    }

    @Override
    public ExecutorCandles executeReturnStep(Function<TradingManager, Optional<String>> function) {
        candles.add(new StackExecuteReturnIndex(function));
        return this;
    }

    boolean isDied = false;

    @Override
    public void executeStack(TradingManager tradingManager) {
        HashMap<String, Integer> mapStep = new HashMap<>();
        for (int i = 0; i < candles.size(); i++) {
            Stack<?> stack = candles.get(i);
            if (stack instanceof StackSetStep stackSetStep) {
                mapStep.put(stackSetStep.getObject(), i);
            }
        }
        boolean isBreak = false;
        for (int i = 0; i < candles.size(); ) {
            switch (candles.get(i)){
                case StackPause stackPause ->  {
                    if (pause(tradingManager.getCurrentTime(), stackPause.getObject())) {
                        i++;
                    }else isBreak = true;
                }
                case StackSetStep ignored -> i++;
                case StackMove stackMove -> i = mapStep.get(stackMove.getObject());
                case StackExecute stackExecute ->  {
                    stackExecute.getObject().accept(tradingManager);
                    i++;
                }
                case StackDied ignored -> isDied = true;
                case StackExecuteReturnIndex stackExecuteReturnIndex -> {
                    Optional<String> step = stackExecuteReturnIndex.getObject().apply(tradingManager);
                    if (step.isPresent()) {
                        i = mapStep.get(step.get());
                    }else {
                        i++;
                    }

                }
                default -> throw new IllegalStateException("Unexpected value: " + candles.get(i));
            }
            if (isDied || isBreak) break;
        }
    }

    public boolean pause(long currentTime, long startInTime){
        return currentTime >= startInTime;
    }

    @Data
    public static abstract class Stack<T> {
        private final T object;
    }

    @EqualsAndHashCode(callSuper = true)
    public static class StackPause extends Stack<Long> {

        public StackPause(Long object) {
            super(object);
        }
    }

    @EqualsAndHashCode(callSuper = true)
    public static class StackSetStep extends Stack<String> {

        public StackSetStep(String object) {
            super(object);
        }
    }

    @EqualsAndHashCode(callSuper = true)
    public static class StackMove extends Stack<String> {

        public StackMove(String object) {
            super(object);
        }
    }

    @EqualsAndHashCode(callSuper = true)
    public static class StackDied extends Stack<Void> {

        public StackDied() {
            super(null);
        }
    }

    @EqualsAndHashCode(callSuper = true)
    public static class StackExecute extends Stack<Consumer<TradingManager>> {

        public StackExecute(Consumer<TradingManager> object) {
            super(object);
        }
    }

    @EqualsAndHashCode(callSuper = true)
    public static class StackExecuteReturnIndex extends Stack<Function<TradingManager, Optional<String>>> {

        public StackExecuteReturnIndex(Function<TradingManager, Optional<String>> object) {
            super(object);
        }
    }
}
