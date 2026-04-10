package xyz.cereshost.vesta.core.command;

import xyz.cereshost.vesta.common.Vesta;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class HanderCommand {

    private final HashMap<String, BaseCommand> commands = new HashMap<>();
    private final HashMap<String, String> commandsAliases = new HashMap<>();

    public void registerCommand(BaseCommand... commands) {
        for (BaseCommand command : commands) {
            this.commands.put(command.getName(), command);
            for (String alias : command.getAliases()) {
                this.commandsAliases.put(alias, command.getName());
            }
            this.commandsAliases.put(command.getName(), command.getName());
        }
    }

    public void dispatch(String... args) {
        if (args.length > 0){
            BaseCommand command = commands.get(commandsAliases.get(args[0]));
            if (command == null) return;

            List<String> argsList = Arrays.asList(args).subList(1, args.length);

            Arguments arguments;
            if (command instanceof Flags flags) {
                arguments = Arguments.buildArgsWithFlags(flags, argsList.toArray(new String[0]));
            }else {
                arguments = Arguments.BuildArgs(argsList.toArray(new String[0]));
            }
            try {
                command.execute(arguments);
            }catch (Exception e) {
                Vesta.sendWaringException("Error executing command " + command.getName(), e);
            }
        }
    }

    public void dispatch(String args) {
        dispatch(args.split(" "));
    }
}
