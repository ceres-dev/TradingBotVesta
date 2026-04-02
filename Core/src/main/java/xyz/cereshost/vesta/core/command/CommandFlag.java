package xyz.cereshost.vesta.core.command;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.lang.constant.Constable;

/**
 * Almacena el valor de un flag previamente declarado por {@link net.atcore.command.Flags.Flag Flag}
 * @param <T> El tipo de dato que tiene que contener (Definidos por {@link net.atcore.command.Flags.TypeValue} TypeValue)
 *
 * @see Flags
 */

@Getter
public class CommandFlag<T extends Constable & Comparable<T> & Serializable>  {

    private final String name;
    @Nullable
    private final T value;
    private final Flags.TypeValue typeValue;
    @NotNull
    private final String[] args;

    public CommandFlag(String name, @Nullable T value) {
        this(name, value, new String[0]);
    }

    public CommandFlag(String name, @Nullable T value, String[] args) {
        this.name = name;
        this.value = value;
        this.args = args;
        switch (value) {
            case Boolean ignored -> typeValue = Flags.TypeValue.BOOLEAN;
            case Integer ignored -> typeValue = Flags.TypeValue.INTEGER;
            case Float ignored -> typeValue = Flags.TypeValue.FLOAT;
            case null, default -> typeValue = Flags.TypeValue.STRING;
        }
    }
}
