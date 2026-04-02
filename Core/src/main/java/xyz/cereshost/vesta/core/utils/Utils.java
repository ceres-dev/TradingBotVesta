package xyz.cereshost.vesta.core.utils;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

@UtilityClass
public class Utils {

    /**
     * Variación del {@link #enumsToStrings(Enum[], boolean)}
     */

    @NotNull
    @Contract(pure = true)
    public String @NotNull [] enumsToStrings(Enum<?>[] raw){
        return enumsToStrings(raw, true);
    }

    /**
     * Crea una lista para el tab usando un enum
     * @param raw la clase de enum
     * @param b ¿Se modifica las mayúsculas?
     * @return lista de enum en string
     */

    @NotNull
    @Contract(pure = true)
    public String @NotNull [] enumsToStrings(Enum<?> @NotNull [] raw, boolean b){
        String[] strings = new String[raw.length];
        int i = 0 ;
        for (Enum<?> e : raw){
            strings[i] = b ? e.name().toLowerCase() : e.name();
            i++;
        }
        return strings;
    }
}
