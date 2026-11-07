package com.dynamicparser.parser;

import com.dynamicparser.model.ArrayRef;
import com.dynamicparser.model.DynamicObject;
import com.dynamicparser.model.JsonObjectMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de descenso recursivo para textos de objetos impresos por distintos
 * lenguajes. Las reglas (literales, separadores, comillas, formas de objeto…)
 * vienen de un {@link Dialecto} definido en {@code dialectos.yml}: no hay
 * sintaxis de ningún lenguaje fija en el código.
 *
 * Formas soportadas (según lo que habilite el dialecto):
 *   Clase(k=v, ...)       objetoParentesis   (Lombok, dataclass de Python, tuple struct de Rust)
 *   Clase[k=v, ...]       objetoCorchetes    (record de Java)
 *   Clase { k = v, ... }  objetoLlaves       (record de C#, struct de Rust)
 *   Clase(1, 2)           posicionales       → propiedades "0", "1", …
 *   {k=v} / {k: v}        mapas (cualquiera de los separadores del dialecto)
 *   {"k": v}              objeto JSON        (jsonEmbebido)
 *   {1, 2}                conjunto → lista   (conjuntos)
 *   [a, b]                lista
 *   (a, b)                tupla → lista      (tuplas)
 *   [I@1a2b               arreglo sin contenido (Object.toString() por defecto de Java)
 *   literales             nulos / verdaderos / falsos del dialecto
 *   números               Integer, Long o Double
 *   "texto" / 'texto'     comillas del dialecto
 *   texto sin comillas    String
 *
 * Los nombres de propiedades se preservan exactamente como vienen.
 */
public class ObjectParser {

    /** Arreglo con Object.toString() por defecto: [I@1a2b, [[Ljava.lang.String;@1a2b */
    private static final Pattern ARRAY_REF =
            Pattern.compile("(\\[+)([BCDFIJSZ]|L[\\w.$]+;)@[0-9a-fA-F]+");

    private final String input;
    private final Dialecto d;
    private int pos;

    /** Valores "reconocidos" por el dialecto (strings entre comillas, literales): sirve para elegir dialecto. */
    private int reconocidos;

    /** Tramos clave→fin de valor de cada propiedad/elemento (offsets relativos a input). */
    private final List<Span> spans = new ArrayList<>();
    /** Ruta actual durante el descenso, enlazada (O(1) por nivel; sin copias). */
    private Span.Ruta ruta;

    public ObjectParser(String input) {
        this(input, Dialecto.porDefecto());
    }

    public ObjectParser(String input, Dialecto dialecto) {
        this.input = input;
        this.d = dialecto;
        this.pos = 0;
    }

    /** Punto de entrada: parsea el primer valor encontrado en el input. */
    public Object parse() {
        skipWhitespace();
        return parseValue();
    }

    /** Posición actual del cursor (cuánto texto se consumió). */
    public int getPosition() { return pos; }

    /** Tramos registrados en el último parse(), para ubicar texto → nodo del árbol. */
    public List<Span> getSpans() { return spans; }

    public int getReconocidos() { return reconocidos; }

    public Dialecto getDialecto() { return d; }

    /** Parsea el valor de una propiedad/elemento registrando su tramo [inicio, fin). */
    private Object parseValueEn(Object segmento, int inicio) {
        var segmentoActual = new Span.Ruta(segmento, ruta);
        ruta = segmentoActual;
        try {
            Object v = parseValue();
            spans.add(new Span(inicio, pos, segmentoActual));
            return v;
        } finally {
            ruta = segmentoActual.padre();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Despachador principal
    // ─────────────────────────────────────────────────────────────────────────

    private Object parseValue() {
        skipWhitespace();
        if (pos >= input.length()) return null;

        char c = input.charAt(pos);

        if (c == '{') return parseLlaves();
        if (c == '[') {
            ArrayRef ref = tryArrayRef();
            return ref != null ? ref : parseSecuencia('[', ']');
        }
        if (c == '(' && d.tuplas()) return parseSecuencia('(', ')');
        if (d.esComilla(c)) {
            reconocidos++;
            return parseQuotedString(c);
        }
        if (c == '-') return parseNumberOrPlain();
        if (Character.isDigit(c)) return parseNumber();
        if (Character.isLetter(c) || c == '_') return parseWordValue();

        return readPlainString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Valores que empiezan por letra: objeto con nombre, literal o texto
    // ─────────────────────────────────────────────────────────────────────────

    private Object parseWordValue() {
        int inicio = pos;
        String nombre = readNombreTipo();
        int finNombre = pos;
        skipWhitespace();

        if (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '(' && d.objetoParentesis()) return parseObjeto(nombre, '(', ')');
            if (c == '[' && d.objetoCorchetes() && looksLikeRecord()) return parseObjeto(nombre, '[', ']');
            if (c == '{' && d.objetoLlaves()) return parseObjeto(nombre, '{', '}');
        }

        // No era objeto: volver justo tras el nombre (sin perder el espacio que lo sigue)
        pos = finNombre;

        // Literales del dialecto, sólo si el valor termina ahí ("null pointer" es texto)
        if (terminaValor()) {
            if (d.nulos().contains(nombre))      { reconocidos++; return null; }
            if (d.verdaderos().contains(nombre)) { reconocidos++; return Boolean.TRUE; }
            if (d.falsos().contains(nombre))     { reconocidos++; return Boolean.FALSE; }
        }

        // Texto plano que empieza por letra (p.ej. fechas, códigos, Enum::Variante)
        pos = inicio;
        return readPlainStringContinuation().trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Objeto con nombre: Clase(...), Clase[...], Clase { ... }
    // ─────────────────────────────────────────────────────────────────────────

    private DynamicObject parseObjeto(String className, char open, char close) {
        consume(open);
        var props = new LinkedHashMap<String, Object>();
        int posicional = 0;

        while (pos < input.length() && input.charAt(pos) != close) {
            int antes = pos;   // guarda: si una vuelta no avanza, cerrar (evita bucles infinitos)
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) == close) break;
            if (esCierre(input.charAt(pos))) break;      // cierre ajeno: texto mal formado

            int inicio = pos;
            String key = leerClave();
            skipWhitespace();

            if (key != null && !key.isEmpty() && pos < input.length() && d.esSeparador(input.charAt(pos))) {
                pos++; // separador clave/valor
                props.put(key, parseValueEn(key, inicio));
            } else if (d.posicionales()) {
                pos = inicio;                            // no era "clave=": es un argumento posicional
                String k = String.valueOf(posicional++);
                props.put(k, parseValueEn(k, inicio));
            } else if (key == null || key.isEmpty()) {
                pos++;                                    // carácter inesperado
            }

            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ',') pos++;
            if (pos == antes) break;    // carácter inesperado (p. ej. un cierre que no corresponde)
        }

        if (pos < input.length() && input.charAt(pos) == close) pos++;
        return new DynamicObject(className, props);
    }

    /** Tras "Nombre", ¿el '[' abre un record? ("[]" o "[ident<sep>") */
    private boolean looksLikeRecord() {
        int p = pos + 1;
        while (p < input.length() && Character.isWhitespace(input.charAt(p))) p++;
        if (p < input.length() && input.charAt(p) == ']') return true;
        int start = p;
        while (p < input.length() && (Character.isLetterOrDigit(input.charAt(p)) || input.charAt(p) == '_' || input.charAt(p) == '$')) p++;
        if (p == start) return false;
        while (p < input.length() && Character.isWhitespace(input.charAt(p))) p++;
        return p < input.length() && d.esSeparador(input.charAt(p));
    }

    /** Intenta leer una referencia de arreglo "[I@1a2b"; null si no lo es (no avanza). */
    private ArrayRef tryArrayRef() {
        Matcher m = ARRAY_REF.matcher(input).region(pos, input.length());
        if (!m.lookingAt()) return null;
        pos = m.end();
        int dims = m.group(1).length();
        String code = m.group(2);
        String base = switch (code.charAt(0)) {
            case 'B' -> "byte";   case 'C' -> "char";  case 'D' -> "double";
            case 'F' -> "float";  case 'I' -> "int";   case 'J' -> "long";
            case 'S' -> "short";  case 'Z' -> "boolean";
            default  -> {
                String fqcn = code.substring(1, code.length() - 1);
                yield fqcn.substring(fqcn.lastIndexOf('.') + 1);
            }
        };
        return new ArrayRef(base + "[]".repeat(dims), m.group());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Llaves: mapa {k=v}/{k: v}, JSON {"k": v} o conjunto {a, b}
    // ─────────────────────────────────────────────────────────────────────────

    private Object parseLlaves() {
        int guardado = pos;
        pos++; // '{'
        skipWhitespace();
        boolean vacio = pos < input.length() && input.charAt(pos) == '}';
        boolean claveConComillaDoble = pos < input.length() && input.charAt(pos) == '"';
        char sep = 0;
        if (!vacio) {
            leerClave();
            skipWhitespace();
            if (pos < input.length()) {
                char c = input.charAt(pos);
                if (d.esSeparador(c) || (d.jsonEmbebido() && c == ':')) sep = c;
            }
        }
        pos = guardado;

        if (!vacio && sep == 0 && d.conjuntos()) return parseSecuencia('{', '}');
        boolean json = d.jsonEmbebido() && sep == ':' && (claveConComillaDoble || !d.esSeparador(':'));
        return parseMapa(json);
    }

    private Map<String, Object> parseMapa(boolean json) {
        consume('{');
        Map<String, Object> map = json ? new JsonObjectMap() : new LinkedHashMap<>();

        while (pos < input.length() && input.charAt(pos) != '}') {
            int antes = pos;   // guarda: si una vuelta no avanza, cerrar (evita bucles infinitos)
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) == '}') break;
            if (esCierre(input.charAt(pos))) break;

            int inicio = pos;
            String key = leerClave();
            if (key == null || key.isEmpty()) { pos++; continue; }

            skipWhitespace();
            if (pos < input.length() && (d.esSeparador(input.charAt(pos)) || (json && input.charAt(pos) == ':'))) {
                pos++;
                map.put(key, parseValueEn(key, inicio));
            }

            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ',') pos++;
            if (pos == antes) break;
        }

        if (pos < input.length() && input.charAt(pos) == '}') pos++;
        return map;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Secuencias: [a, b], (a, b) y {a, b}
    // ─────────────────────────────────────────────────────────────────────────

    private List<Object> parseSecuencia(char open, char close) {
        consume(open);
        var list = new ArrayList<Object>();

        while (pos < input.length() && input.charAt(pos) != close) {
            int antes = pos;   // guarda: si una vuelta no avanza, cerrar (evita bucles infinitos)
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) == close) break;
            if (esCierre(input.charAt(pos))) break;      // cierre ajeno: texto mal formado

            list.add(parseValueEn(list.size(), pos));

            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ',') pos++;
            if (pos == antes) break;
        }

        if (pos < input.length() && input.charAt(pos) == close) pos++;
        return list;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cadenas y números
    // ─────────────────────────────────────────────────────────────────────────

    private String parseQuotedString(char comilla) {
        consume(comilla);
        var sb = new StringBuilder();

        while (pos < input.length() && input.charAt(pos) != comilla) {
            char c = input.charAt(pos);
            if (c == '\\' && pos + 1 < input.length()) {
                pos++;
                char esc = input.charAt(pos);
                switch (esc) {
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case 'b'  -> sb.append('\b');
                    case 'f'  -> sb.append('\f');
                    case 'u'  -> { sb.append(parseUnicodeEscape()); continue; }
                    default   -> {
                        if (d.esComilla(esc)) sb.append(esc);          // \" o \'
                        else { sb.append('\\'); sb.append(esc); }
                    }
                }
            } else {
                sb.append(c);
            }
            pos++;
        }

        if (pos < input.length()) pos++; // comilla de cierre
        return sb.toString();
    }

    private char parseUnicodeEscape() {
        pos++; // ya se consumió la 'u'
        if (pos + 4 > input.length()) return '?';
        try {
            char ch = (char) Integer.parseInt(input.substring(pos, pos + 4), 16);
            pos += 4;
            return ch;
        } catch (NumberFormatException e) {
            return '?';
        }
    }

    private Object parseNumberOrPlain() {
        int savedPos = pos;
        pos++; // salta '-'
        if (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
            pos = savedPos;
            return parseNumber();
        }
        // No es número: texto plano que empieza con '-' (p.ej. codigo=-)
        pos = savedPos;
        return readPlainString();
    }

    private Object parseNumber() {
        int start = pos;
        if (pos < input.length() && input.charAt(pos) == '-') pos++;

        while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;

        boolean decimal = false;
        if (pos < input.length() && input.charAt(pos) == '.') {
            decimal = true;
            pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        }

        boolean exponent = false;
        if (pos < input.length() && (input.charAt(pos) == 'E' || input.charAt(pos) == 'e')) {
            exponent = true;
            pos++;
            if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        }

        // Sólo es número si el valor termina aquí (delimitador o fin). Si sigue
        // texto —p. ej. "123-informe.pdf", "22/10/2026" o "1.0.3"— es un String.
        if (!terminaValor()) {
            pos = start;
            return readPlainString();
        }

        String raw = input.substring(start, pos);
        try {
            if (decimal || exponent) return Double.parseDouble(raw);
            long v = Long.parseLong(raw);
            // Ojo: "cond ? (int) v : v" promovería ambas ramas a long; se boxea explícitamente
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) return Integer.valueOf((int) v);
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lectura de texto plano (sin comillas)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lee hasta encontrar un delimitador (coma o cierre de contenedor) a profundidad 0.
     * Rastrea la profundidad de paréntesis/llaves/corchetes para no cortar prematuramente.
     */
    private String readPlainString() {
        var sb = new StringBuilder();
        int depth = 0;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '(' || c == '{' || c == '[')  depth++;
            else if (c == ')' || c == '}' || c == ']') {
                if (depth == 0) break;
                depth--;
            } else if (c == ',' && depth == 0) break;
            sb.append(c);
            pos++;
        }
        return sb.toString().trim();
    }

    /** Lee un valor de texto que empieza por letra hasta el siguiente delimitador. */
    private String readPlainStringContinuation() {
        var sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == ',' || c == ')' || c == ']' || c == '}') break;
            sb.append(c);
            pos++;
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Claves, identificadores y nombres de tipo
    // ─────────────────────────────────────────────────────────────────────────

    /** Clave de propiedad: entre comillas del dialecto, o sin comillas (ver readKey). */
    private String leerClave() {
        if (pos < input.length() && d.esComilla(input.charAt(pos))) {
            return parseQuotedString(input.charAt(pos));
        }
        return readKey();
    }

    /**
     * Clave sin comillas: letras, dígitos, '_', '.', '-', '$'. Preserva exactamente el
     * case original (camelCase, snake_case, bci_field__c, etc.).
     */
    private String readKey() {
        var sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == '$') {
                sb.append(c);
                pos++;
            } else break;
        }
        return sb.toString();
    }

    /**
     * Nombre de tipo, posiblemente calificado: Clase, pkg.Clase, modulo::Tipo
     * (sólo se incluyen '.' o '::' si los sigue otro identificador).
     */
    private String readNombreTipo() {
        var sb = new StringBuilder(readIdentifier());
        while (pos < input.length()) {
            int sepLen = input.startsWith("::", pos) ? 2 : input.charAt(pos) == '.' ? 1 : 0;
            if (sepLen == 0 || pos + sepLen >= input.length()) break;
            char sig = input.charAt(pos + sepLen);
            if (!(Character.isLetter(sig) || sig == '_')) break;
            sb.append(input, pos, pos + sepLen);
            pos += sepLen;
            sb.append(readIdentifier());
        }
        return sb.toString();
    }

    /** Identificador (letras, dígitos, '_', '$'). */
    private String readIdentifier() {
        var sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                sb.append(c);
                pos++;
            } else break;
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidades
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean esCierre(char c) {
        return c == ')' || c == ']' || c == '}';
    }

    /** ¿Desde pos (ignorando espacios) viene un delimitador de valor o el fin del texto? */
    private boolean terminaValor() {
        int p = pos;
        while (p < input.length() && Character.isWhitespace(input.charAt(p))) p++;
        if (p >= input.length()) return true;
        char c = input.charAt(p);
        return c == ',' || c == ')' || c == ']' || c == '}';
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
    }

    private void consume(char expected) {
        if (pos < input.length() && input.charAt(pos) == expected) pos++;
    }
}
