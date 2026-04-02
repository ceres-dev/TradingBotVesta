package xyz.cereshost.vesta.core.exception;

/**
 * Se usa cuando se intenta obtener un flag, pero este no se escribió en los argumentos
 */
public class UndefinedFlagException extends Exception {
    public UndefinedFlagException(String message) {
        super(message);
    }
}
