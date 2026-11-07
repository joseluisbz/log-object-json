package com.dynamicparser.ui.fold;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Calcula la ruta JSON (p. ej. {@code pedido.lineas[0].producto.precio})
 * de la clave o valor que hay en un offset del texto. Tolera JSON editado:
 * sólo necesita que el texto hasta el offset sea razonablemente válido.
 *
 * Segmentos: {@link String} = clave de objeto, {@link Integer} = índice de arreglo.
 */
public final class JsonPath {

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private JsonPath() { }

    private static final class Frame {
        final boolean array;
        String key;          // objeto: clave actual (null = esperando clave)
        int index;           // arreglo: índice actual
        boolean hasValue;    // arreglo: ya empezó algún elemento
        Frame(boolean array) { this.array = array; }
    }

    /** Ruta en el offset, o null si el offset no está sobre una clave/valor. */
    public static List<Object> pathAt(String text, int offset) {
        if (offset < 0 || offset >= text.length()) return null;
        char at = text.charAt(offset);
        if (Character.isWhitespace(at) || at == ',' || at == ':') return null;

        Deque<Frame> stack = new ArrayDeque<>();
        int i = 0;
        while (i < offset) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = finDeString(text, i);
                if (end >= offset) {               // el offset está dentro de este string
                    break;
                }
                Frame f = stack.peek();
                if (f != null && !f.array && f.key == null) {
                    f.key = contenido(text, i, end);
                } else if (f != null && f.array) {
                    f.hasValue = true;
                }
                i = end + 1;
                continue;
            }
            Frame f = stack.peek();
            switch (c) {
                case '{', '[' -> {
                    if (f != null && f.array) f.hasValue = true;
                    stack.push(new Frame(c == '['));
                }
                case '}', ']' -> { if (!stack.isEmpty()) stack.pop(); }
                case ',' -> {
                    if (f != null) {
                        if (f.array) f.index++;
                        else f.key = null;
                    }
                }
                default -> { }
            }
            i++;
        }

        // Caso: offset dentro de una clave (string antes de ':')
        Frame top = stack.peek();
        if (top != null && !top.array && top.key == null && i < text.length() && text.charAt(i) == '"') {
            int end = finDeString(text, i);
            if (end > i) top.key = contenido(text, i, end);
        }

        var path = new ArrayList<Object>();
        var frames = new ArrayList<>(stack);
        java.util.Collections.reverse(frames);    // de la raíz hacia adentro
        for (Frame f : frames) {
            if (f.array) path.add(f.index);
            else if (f.key != null) path.add(f.key);
            else return path.isEmpty() ? null : path;   // sin clave aún
        }
        // Sobre la llave de cierre del contenedor: la ruta es la del contenedor
        if ((at == '}' || at == ']') && !path.isEmpty()) path.remove(path.size() - 1);
        return path.isEmpty() ? null : path;
    }

    /**
     * Inverso de {@link #pathAt}: offset donde empieza la clave (objeto) o el
     * elemento (arreglo) de la ruta dada; 0 para la ruta vacía; -1 si no existe.
     */
    public static int offsetOf(String text, List<Object> target) {
        if (target.isEmpty()) return 0;
        Deque<Frame> stack = new ArrayDeque<>();
        boolean arrayNeedsValue = false;   // en arreglo: esperando inicio de elemento
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            Frame f = stack.peek();

            // Inicio de un elemento de arreglo
            if (f != null && f.array && arrayNeedsValue && c != ']' && c != ',') {
                arrayNeedsValue = false;
                if (coincide(stack, target)) return i;
            }

            if (c == '"') {
                int end = finDeString(text, i);
                if (f != null && !f.array && f.key == null) {
                    f.key = contenido(text, i, end);
                    if (coincide(stack, target)) return i;
                }
                i = end;
                continue;
            }
            switch (c) {
                case '{', '[' -> {
                    stack.push(new Frame(c == '['));
                    arrayNeedsValue = c == '[';
                }
                case '}', ']' -> {
                    if (!stack.isEmpty()) stack.pop();
                    arrayNeedsValue = false;
                }
                case ',' -> {
                    if (f != null) {
                        if (f.array) { f.index++; arrayNeedsValue = true; }
                        else f.key = null;
                    }
                }
                default -> { }
            }
        }
        return -1;
    }

    /** ¿La ruta actual de la pila (raíz → tope) es igual a target? */
    private static boolean coincide(Deque<Frame> stack, List<Object> target) {
        if (stack.size() != target.size()) return false;
        var it = stack.descendingIterator();
        for (Object seg : target) {
            Frame f = it.next();
            Object actual = f.array ? (Object) f.index : f.key;
            if (!seg.equals(actual)) return false;
        }
        return true;
    }

    /** "a.b[0].c"; claves no-identificador como ["clave rara"]. */
    public static String format(List<Object> path) {
        return format(path, null);
    }

    /**
     * Igual que {@link #format(List)}, pero antepone una marca a ciertas claves
     * (p. ej. "&lt;,&gt;" a las claves cuyo valor es un Map).
     *
     * @param marca prefijo para el segmento en la posición i (null o "" = ninguno)
     */
    public static String format(List<Object> path, java.util.function.IntFunction<String> marca) {
        var sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            Object seg = path.get(i);
            if (seg instanceof Integer idx) {
                sb.append('[').append(idx).append(']');
            } else {
                String k = (String) seg;
                String pre = marca == null ? null : marca.apply(i);
                if (pre == null) pre = "";
                if (IDENT.matcher(k).matches()) {
                    if (!sb.isEmpty()) sb.append('.');
                    sb.append(pre).append(k);
                } else {
                    sb.append('[').append(pre).append("\"").append(k.replace("\"", "\\\"")).append("\"]");
                }
            }
        }
        return sb.toString();
    }

    /** Offset de la comilla de cierre del string que empieza en start (o fin de línea). */
    private static int finDeString(String text, int start) {
        for (int j = start + 1; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '\\') { j++; continue; }
            if (c == '"' || c == '\n') return j;
        }
        return text.length() - 1;
    }

    private static String contenido(String text, int start, int end) {
        var sb = new StringBuilder();
        for (int j = start + 1; j < end; j++) {
            char c = text.charAt(j);
            if (c == '\\' && j + 1 < end) {
                char n = text.charAt(++j);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (j + 4 < end) {
                            sb.append((char) Integer.parseInt(text.substring(j + 1, j + 5), 16));
                            j += 4;
                        }
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
