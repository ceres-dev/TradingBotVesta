package xyz.cereshost.vesta.core.utils;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public interface Identifiable {

    @NotNull
    @Contract(pure = true)
    UUID getUuid();
}
