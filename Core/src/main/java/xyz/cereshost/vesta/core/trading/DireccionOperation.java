package xyz.cereshost.vesta.core.trading;

import lombok.Getter;
import org.jetbrains.annotations.Contract;

/**
 * Direcciones de operación
 */
@Getter
public enum DireccionOperation {
    /**
     * Operación Corta/Vender
     */
    SHORT("SELL"),
    /**
     * Operación Larga/Comprar
     */
    LONG("BUY"),
    /**
     * En caso qué sea lateral
     * <strong>No se puede operar con neutral solo es una forma de identificar operacion sin movimiento ósea no hacer nada</strong>
     */
    NEUTRAL("invalid");

    private final String side;
    DireccionOperation(String side) {
        this.side = side;
    }

    public DireccionOperation inverse(){
        return switch (this){
            case LONG -> SHORT;
            case SHORT -> LONG;
            default -> NEUTRAL;
        };
    }

    public static DireccionOperation parse(String s) {
        return switch (s.toUpperCase()) {
            case "SELL" -> SHORT;
            case "BUY" -> LONG;
            default -> throw new IllegalArgumentException(s);
        };
    }

    public static DireccionOperation parse(double d) {
        if (d == 0){
            return NEUTRAL;
        }
        if (d > 0){
            return LONG;
        }
        if (d < 0){
            return SHORT;
        }
        return NEUTRAL;
    }

    @Contract(pure = true)
    public boolean isShort(){
        return this == SHORT;
    }

    @Contract(pure = true)
    public boolean isLong(){
        return this == LONG;
    }
}
