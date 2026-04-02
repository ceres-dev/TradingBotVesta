package xyz.cereshost.vesta.core.command;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.cereshost.vesta.core.exception.UndefinedFlagException;

import java.util.*;

/**
 * Guarda las indicaciones de como se debe ejecutado usando los argumentos y los flags.
 * <p>
 *     El formato que usa para crear los argumentos es cada argumento debe estár separado por el " " (espacio)
 *     donde se accedera por índice ejemplo: {@code proxy stop *} se divide y se guarda en una {@link List} con el parametro de
 *     {@link String} en esté caso sería índice 0: {@code proxy}, índice 1: {@code stop}, índice 2: {@code *}
 * </p>
 * <p>
 *     Si se usa flags la cambia la forma como se construye los argumentos. Los flags serán extraídos del String original
 *     provocando que no afecte el índice de los argumentos ejemplo: {@code proxy stop -example *} en este caso si hay un flag
 *     que se llama {@code example} al extraerlo queda asi {@code proxy stop *} y con un flag {@code example} los flags extraídos
 *     se guardan en un {@link Map} donde la key es un String que es nombre del el flag en este caso {@code example} y valor es
 *     {@link CommandFlag}
 * </p>
 *
 * @see CommandFlag
 * @see BaseCommand
 *
 * @author Ceres
 */
@SuppressWarnings("unused")
public class Arguments {

    @NotNull
    private final Map<String, CommandFlag<?>> flags;
    @NotNull
    private final List<String> args;

    private Arguments(@NotNull List<String> args) {
        this.args = new ArrayList<>(args);
        this.flags = new HashMap<>();
    }

    private Arguments(String... args) {
        this.args = new ArrayList<>(Arrays.asList(args));
        this.flags = new HashMap<>();
    }

    private Arguments(@NotNull List<String> args, @NotNull Map<String, CommandFlag<?>> flags) {
        this.args = new ArrayList<>(args);
        this.flags =  new HashMap<>(flags);
    }

    public String get(int index) {
        return args.get(index);
    }

    public float getFloat(int index) throws NumberFormatException {
        return Float.parseFloat(args.get(index));
    }

    public int getInteger(int index) throws NumberFormatException {
        return Integer.parseInt(args.get(index));
    }

    public List<String> getRange(int index, int max) {
        return args.subList(index, Math.min(max, args.size()));
    }

    public List<String> toList(int offset) {
        return args.subList(offset, args.size());
    }

    public int length() {
        return args.size();
    }

    public boolean hasFlags() {
        return !flags.isEmpty();
    }

    public Set<String> getFlagsNames() {
        return flags.keySet();
    }

    @Nullable
    @Contract(pure = true)
    public CommandFlag<?> getFlag(String name) {
        return flags.get(name);
    }

    //////////////////////
    //////////////////////

    @Contract(pure = true)
    public boolean getFlagBolean(String name) {
        return flags.get(name) != null && Objects.requireNonNullElse((Boolean) flags.get(name).getValue(), false);
    }

    @Contract(pure = true)
    public int getFlagInteger(String name) throws UndefinedFlagException {
        if(flags.get(name) == null){
            throw new NullPointerException("No existe una flag con el nombre " + name);
        }else {
            try {
                return Objects.requireNonNull((Integer) flags.get(name).getValue());
            }catch (NullPointerException e){
                throw new UndefinedFlagException("No se definió una flag (Integer) con el nombre " + name);
            }
        }
    }

    @Contract(pure = true)
    public int getFlagInteger(String name, int defaultValue) {
        return flags.get(name) == null ? defaultValue : Objects.requireNonNullElse((Integer)  flags.get(name).getValue(), defaultValue);
    }

    @Contract(pure = true)
    public float getFlagFloat(String name) throws UndefinedFlagException {
        if(flags.get(name) == null){
            throw new NullPointerException("No existe una flag con el nombre " + name);
        }else {
            try {
                return Objects.requireNonNull((Float) flags.get(name).getValue());
            }catch (NullPointerException e){
                throw new UndefinedFlagException("No se definió una flag (Float) con el nombre " + name);
            }
        }
    }

    @Contract(pure = true)
    public float getFlagFloat(String name, float defaultValue) {
        return flags.get(name) == null ? defaultValue : Objects.requireNonNullElse((Float) flags.get(name).getValue(), defaultValue);
    }

    @NotNull
    @Contract(pure = true)
    public String getFlagString(String name) throws UndefinedFlagException {
        if(flags.get(name) == null){
            throw new NullPointerException("No existe una flag con el nombre " + name);
        }else {
            try {
                return Objects.requireNonNull(String.valueOf(flags.get(name).getValue()));
            }catch (NullPointerException e){
                throw new UndefinedFlagException("No se definió una flag (String) con el nombre " + name);
            }
        }
    }

    @NotNull
    @Contract(pure = true)
    public String getFlagString(String name, String defaultValue) {
        return flags.get(name) == null ? defaultValue : Objects.requireNonNullElse(String.valueOf(flags.get(name).getValue()), defaultValue);
    }

    //////////////////////
    //////////////////////

    @NotNull
    @Contract(pure = true)
    public String getJoinArgs(int offset) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < args.size(); i++){
            sb.append(args.get(i)).append(" ");
        }
        return sb.substring(0, sb.length() - 1);
    }

    /**
     * Añades los flags del argumento a este argumento
     * @param args El argumento que se va a tomar
     * @return La misma instancia
     */

    public Arguments joinFlags(@NotNull Arguments args) {
        this.flags.putAll(args.flags);
        return this;
    }

    public Arguments addFistArgs(@NotNull String @NotNull ... addArgs) {
        for (@NotNull String arg : addArgs){
            args.addFirst(arg);
        }
        return this;
    }

    /**
     * Crea el argumento con los flags.
     * <p>
     *     Para crear un flag unos de los argumentos tiene que comenzar con {@code -} donde este actuara de
     *     prefijo indicador de flag donde al final este se sacara de la lista de args y pasara a al mapa de flag
     *     ejemplo:
     * </p>
     * Este es el argumento crudo
     * <blockquote><pre>
     *     * Hola -toJson mundo
     * </pre></blockquote>
     * Se crea este argumento final  {@code * Hola mundo } y por separado se obtiene {@code  -toJson} que es el flag
     * @param command El comando donde se tomara los flags de referencia
     * @param args Argumento crudo
     * @return Argumento
     */

    @Contract(value = "_, _ -> new")
    public static Arguments buildArgsWithFlags(@NotNull BaseCommand command, String... args) {
        if (command instanceof Flags flags){
            List<String> nameFlags = flags.getNameFlags();
            List<String> finalArgs = new ArrayList<>();
            HashMap<String, CommandFlag<?>> finalFlags = new HashMap<>();
            String lastFlagName = "";
            for (String arg : args){
                if (lastFlagName.startsWith("-") && !arg.startsWith("-")){
                    //if (lastFlagName.isBlank()) continue;
                    String stringFlag = lastFlagName.substring(1);
                    Flags.Flag f = flags.getFlag(stringFlag);
                    lastFlagName = arg;
                    if (f == null) continue;
                    try{
                        switch (f.getValue()){
                            case BOOLEAN -> finalArgs.add(arg);
                            case STRING -> finalFlags.put(stringFlag, new CommandFlag<>(stringFlag, arg, f.getArgs()));
                            case INTEGER -> finalFlags.put(stringFlag, new CommandFlag<>(stringFlag, Integer.parseInt(arg)));
                            case FLOAT -> finalFlags.put(stringFlag, new CommandFlag<>(stringFlag, Float.parseFloat(arg)));
                        }
                    }catch (NumberFormatException ignored){

                    }
                }else {
                    if (arg.startsWith("-")){
                        String stringFlag = arg.substring(1);
                        Flags.Flag f = flags.getFlag(stringFlag);
                        if (f == null)continue;
                        lastFlagName = arg;
                        switch (f.getValue()){
                            case BOOLEAN -> finalFlags.put(stringFlag, new CommandFlag<>(stringFlag, true));
                            case STRING -> finalFlags.put(stringFlag, new CommandFlag<>(stringFlag, null, f.getArgs()));
                            case INTEGER -> finalFlags.put(stringFlag, new CommandFlag<>(stringFlag, 0));
                            case FLOAT -> finalFlags.put(stringFlag, new CommandFlag<>(stringFlag, 0f));
                        }
                    }else {
                        finalArgs.add(arg);
                    }
                }
            }
            return new Arguments(finalArgs, finalFlags);
        }else {
            return new Arguments(args);
        }
    }

    @Contract(value = "_ -> new")
    public static @NotNull Arguments BuildArgs(@NotNull List<String> args){
        return new Arguments(args);
    }

    @Contract(value = "_ -> new")
    public static @NotNull Arguments BuildArgs(@Nullable String... args){
        List<String> finalArgs = new ArrayList<>();
        for (String arg : args){
            if (arg != null) finalArgs.add(arg);
            else finalArgs.add("");
        }
        return new Arguments(finalArgs);
    }

    @Contract(value = " -> new")
    public static @NotNull Arguments BuildArgsEmpty(){
        return new Arguments("");
    }
}
