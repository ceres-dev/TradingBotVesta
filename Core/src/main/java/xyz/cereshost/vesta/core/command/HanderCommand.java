package xyz.cereshost.vesta.core.command;

import java.util.HashMap;

public class HanderCommand {

    private HashMap<String, BaseCommand<?>> commands;

    public void registerCommand(BaseCommand<?> command) {
        commands.put(command.getName(), command);
    }

}
