package xyz.cereshost.vesta.core.utils;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public interface Copyable<T> {

    @Contract(pure = true, value = " -> new")
    @NotNull T copy();
}
