package com.dynamicparser.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrae y parsea las representaciones de objetos embebidas en líneas de log.
 *
 * Para cada línea prueba varios puntos de inicio ("Clase(", "Clase {", "Clase[",
 * "{", "[", "(") con cada dialecto activo de {@code dialectos.yml} (Java, C#,
 * Python, Rust…) y se queda con el mejor (ver {@link #mejorQue}).
 *
 * No está acoplado a ningún log ni lenguaje concreto.
 */
public class LogExtractor {

    /** "Clase(" — inicio de objeto Java toString() (usado por findObjectStart). */
    private static final Pattern OBJECT_START =
            Pattern.compile("[A-Z][a-zA-Z0-9_]*\\(");

    /** "Clase(", "Clase {", "Clase[" — posible inicio de objeto con nombre (cualquier dialecto). */
    private static final Pattern TIPO_CON_APERTURA =
            Pattern.compile("[A-Z][\\w$]*\\s*[({\\[]");

    // ─────────────────────────────────────────────────────────────────────────
    // API pública
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parsea el texto de log completo (una o varias líneas).
     * Devuelve una entrada por cada línea que contenga un objeto reconocible.
     */
    public static List<ParsedEntry> extractAll(String logText) {
        // El parser es recursivo: se ejecuta en un hilo con pila grande (configurable)
        // para soportar anidamientos profundos sin StackOverflowError.
        long pila = (long) com.dynamicparser.Config.entero("extractor.pilaMb") * 1024 * 1024;
        var resultado = new java.util.concurrent.atomic.AtomicReference<List<ParsedEntry>>();
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var hilo = new Thread(null, () -> {
            try {
                resultado.set(extraerTodo(logText));
            } catch (Throwable t) {
                error.set(t);
            }
        }, "parser-log", pila);
        hilo.start();
        try {
            hilo.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            hilo.interrupt();
            throw new IllegalStateException("Parseo interrumpido", ex);
        }
        if (error.get() instanceof RuntimeException re) throw re;
        if (error.get() instanceof Error er) throw er;
        return resultado.get();
    }

    private static List<ParsedEntry> extraerTodo(String logText) {
        var entries = new ArrayList<ParsedEntry>();
        int inicioLinea = 0;

        while (inicioLinea <= logText.length()) {
            int finLinea = logText.indexOf('\n', inicioLinea);
            if (finLinea < 0) finLinea = logText.length();
            String rawLine = logText.substring(inicioLinea, finLinea);
            if (rawLine.endsWith("\r")) rawLine = rawLine.substring(0, rawLine.length() - 1);

            int sangria = 0;
            while (sangria < rawLine.length() && Character.isWhitespace(rawLine.charAt(sangria))) sangria++;
            String line = rawLine.trim();

            if (!line.isEmpty()) {
                var mejor = mejorCandidato(line);
                if (mejor != null) {
                    int base = inicioLinea + sangria + mejor.inicio();   // offset absoluto del fragmento
                    if (mejor.error() != null) {
                        entries.add(new ParsedEntry(line, "⚠ Error de parseo: " + mejor.error()));
                    } else {
                        var spans = mejor.parser().getSpans().stream().map(sp -> sp.desplazado(base)).toList();
                        int fin = base + mejor.parser().getPosition();
                        entries.add(new ParsedEntry(line, mejor.parsed(), base, fin, spans,
                                mejor.parser().getDialecto().nombre()));
                    }
                }
            }
            if (finLinea >= logText.length()) break;
            inicioLinea = finLinea + 1;
        }

        return entries;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Búsqueda del fragmento parseable dentro de una línea
    // ─────────────────────────────────────────────────────────────────────────

    /** Resultado de parsear desde un punto de inicio candidato con un dialecto. */
    private record Candidato(int inicio, int prioridad, ObjectParser parser, Object parsed, String error) {
        int consumido()   { return parser == null ? -1 : parser.getPosition(); }
        int reconocidos() { return parser == null ? -1 : parser.getReconocidos(); }
        int elementos()   { return parser == null ? -1 : parser.getSpans().size(); }
    }

    /**
     * Criterio de elección (en orden):
     *   1) el que llega al FINAL de la línea (el objeto de un log suele ir al final;
     *      los corchetes del principio son prefijos: [INFO] [hilo]…);
     *   2) el que parsea más texto;
     *   3) el que obtuvo más propiedades/elementos (un dialecto que no entiende el
     *      separador "consume" el texto pero lo ignora: p. ej. C# ante "k: v" de Rust);
     *   4) el que más valores RECONOCE con su dialecto (strings entre comillas,
     *      literales como None/True): distingue p. ej. Python de Java;
     *   5) el dialecto que aparece antes en dialectos.yml; 6) el inicio más temprano.
     */
    private static boolean mejorQue(Candidato c, Candidato actual, String line) {
        boolean cFin = llegaAlFinal(c, line), aFin = llegaAlFinal(actual, line);
        if (cFin != aFin) return cFin;
        if (c.consumido() != actual.consumido()) return c.consumido() > actual.consumido();
        if (c.elementos() != actual.elementos()) return c.elementos() > actual.elementos();
        if (c.reconocidos() != actual.reconocidos()) return c.reconocidos() > actual.reconocidos();
        if (c.prioridad() != actual.prioridad()) return c.prioridad() < actual.prioridad();
        return c.inicio() < actual.inicio();
    }

    private static boolean llegaAlFinal(Candidato c, String line) {
        if (c.consumido() < 0) return false;
        return line.substring(Math.min(line.length(), c.inicio() + c.consumido())).isBlank();
    }

    /**
     * Puntos de inicio candidatos, en orden: "Tipo(" / "Tipo {" / "Tipo[" (que no sean
     * parte de un nombre calificado como c.b.Clase o $Interna) y cada '{', '[' o '('.
     */
    static List<Integer> inicios(String line, int max) {
        var set = new java.util.TreeSet<Integer>();
        char c0 = line.charAt(0);
        if (c0 == '{' || c0 == '[' || c0 == '(' || Character.isLetter(c0)) set.add(0);
        Matcher m = TIPO_CON_APERTURA.matcher(line);
        while (m.find()) {
            int s = m.start();
            if (s > 0) {
                char prev = line.charAt(s - 1);
                if (prev == '.' || prev == '$' || prev == ':' || Character.isLetterOrDigit(prev) || prev == '_') continue;
            }
            set.add(s);
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '{' || c == '[' || c == '(') set.add(i);
        }
        return set.stream().limit(max).toList();
    }

    private static Candidato mejorCandidato(String line) {
        int maxCandidatos = com.dynamicparser.Config.entero("extractor.maxCandidatos");
        List<Dialecto> dialectos = Dialecto.activos();
        var mejorPorDialecto = new Candidato[dialectos.size()];

        Candidato mejor = null;
        for (int ini : inicios(line, maxCandidatos)) {
            for (int di = 0; di < dialectos.size(); di++) {
                var previo = mejorPorDialecto[di];
                // Poda: un inicio DENTRO del mejor tramo ya hallado por este dialecto
                // consumiría menos (o el dialecto ya llegó al final): no puede ganar.
                if (previo != null && previo.consumido() >= 0
                        && (llegaAlFinal(previo, line) || ini < previo.inicio() + previo.consumido())) continue;

                Candidato c;
                try {
                    var parser = new ObjectParser(line.substring(ini), dialectos.get(di));
                    Object parsed = parser.parse();
                    if (!(parsed instanceof com.dynamicparser.model.DynamicObject
                            || parsed instanceof java.util.Map || parsed instanceof java.util.List)) continue;
                    c = new Candidato(ini, di, parser, parsed, null);
                } catch (StackOverflowError e) {
                    c = new Candidato(ini, di, null, null,
                            "anidamiento demasiado profundo (aumente extractor.pilaMb en application.yml)");
                } catch (Exception e) {
                    c = new Candidato(ini, di, null, null, e.getMessage());
                }
                if (previo == null || mejorQue(c, previo, line)) mejorPorDialecto[di] = c;
                if (mejor == null || mejorQue(c, mejor, line)) mejor = c;
            }
        }
        return mejor;
    }

    static String findObjectFragment(String line) {
        int idx = findObjectStart(line);
        return idx < 0 ? null : line.substring(idx);
    }

    /** Índice del primer "Clase(" que no sea parte de un nombre calificado (c.b.Clase, $Interna), o -1. */
    static int findClassStart(String line) {
        Matcher m = OBJECT_START.matcher(line);
        while (m.find()) {
            int start = m.start();
            if (start > 0) {
                char prev = line.charAt(start - 1);
                if (prev == '.' || prev == '$') continue;
            }
            return start;
        }
        return -1;
    }

    /** Índice donde empieza el primer objeto reconocible en la línea, o -1. */
    static int findObjectStart(String line) {
        // 1) Buscar UppercaseClassName(
        Matcher m = OBJECT_START.matcher(line);
        while (m.find()) {
            int start = m.start();
            // Descartar referencias de paquete/clase: "c.b.s.ClassName" o "$Inner"
            if (start > 0) {
                char prev = line.charAt(start - 1);
                if (prev == '.' || prev == '$') continue;
            }
            return start;
        }

        // 2) Fallback: primer '{' o '['
        int brace   = line.indexOf('{');
        int bracket = line.indexOf('[');

        if (brace < 0 && bracket < 0) return -1;
        if (brace < 0)   return bracket;
        if (bracket < 0) return brace;
        return Math.min(brace, bracket);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Modelo de resultado
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Par (línea original del log, objeto parseado).
     * El campo {@code parsed} puede ser un {@link com.dynamicparser.model.DynamicObject},
     * un {@link java.util.Map}, una {@link java.util.List}, o un primitivo / String.
     */
    public record ParsedEntry(String sourceLine, Object parsed, int baseOffset, int finOffset,
                              List<Span> spans, String dialecto) {

        public ParsedEntry(String sourceLine, Object parsed) {
            this(sourceLine, parsed, -1, -1, List.of(), null);
        }

        /** ¿El offset absoluto cae dentro del objeto de esta entrada? */
        public boolean contiene(int offset) {
            return baseOffset >= 0 && offset >= baseOffset && offset < finOffset;
        }

        /** Ruta de la propiedad/elemento más interno que contiene el offset ([] = raíz). */
        public List<Object> rutaEn(int offset) {
            Span mejor = null;
            for (Span sp : spans) {
                if (sp.contiene(offset) && (mejor == null || sp.largo() < mejor.largo())) mejor = sp;
            }
            return mejor == null ? List.of() : mejor.path();
        }
    }
}
