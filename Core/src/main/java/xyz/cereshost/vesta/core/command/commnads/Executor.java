package xyz.cereshost.vesta.core.command.commnads;

import xyz.cereshost.vesta.core.Main;
import xyz.cereshost.vesta.core.command.Arguments;
import xyz.cereshost.vesta.core.command.BaseCommand;
import xyz.cereshost.vesta.core.command.Flags;
import xyz.cereshost.vesta.core.command.HanderCommand;

import java.util.List;

public class Executor extends BaseCommand implements Flags {
    public Executor() {
        super("puedes ejecutar multiples comandos");
        addAlias("ex");
    }

    @Override
    public void execute(Arguments arguments) throws Exception {
        String[] executes = arguments.getJoinArgs(0).split(";");
        HanderCommand command = Main.getHanderCommand();
        if (arguments.getFlagBolean("parallel")){
            for (String execute : executes) {
                Main.EXECUTOR.submit(() -> command.dispatch(execute));
            }
        }else {
            for (String execute : executes) {
                command.dispatch(execute);
            }
        }
    }

    @Override
    public List<Flag> getFlags() {
        return List.of(
                new Flag("parallel", TypeValue.BOOLEAN)
        );
    }
}
