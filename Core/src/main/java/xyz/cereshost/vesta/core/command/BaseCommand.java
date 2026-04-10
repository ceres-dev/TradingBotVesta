package xyz.cereshost.vesta.core.command;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@RequiredArgsConstructor
public abstract class BaseCommand {

    private final String name = this.getClass().getSimpleName().toLowerCase();
    private final String description;

    @Getter(AccessLevel.NONE)
    private final List<String> aliases = new ArrayList<>();

    public abstract void execute(Arguments arguments) throws Exception;

    public void addAlias(String alias) {
        this.aliases.add(alias);
    }

    public List<String> getAliases() {
        return new ArrayList<>(this.aliases);
    }
}
