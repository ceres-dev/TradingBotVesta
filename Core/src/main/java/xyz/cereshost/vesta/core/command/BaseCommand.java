package xyz.cereshost.vesta.core.command;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public abstract class BaseCommand<R> {

    private final String name = this.getClass().getSimpleName().toLowerCase();
    private final String description;

    public abstract R execute(Arguments arguments);
}
