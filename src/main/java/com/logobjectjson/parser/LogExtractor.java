package com.logobjectjson.parser;

import com.logobjectjson.Config;
import com.logobjectjson.model.DynamicObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts and parses object representations embedded in log lines.
 *
 * For each line it tries several start points ("Type(", "Type {", "Type[",
 * "{", "[", "(") with every active dialect in {@code dialects.yml} (Java, C#,
 * Python, Rust…) and keeps the best one (see {@link #isBetter}).
 *
 * Not coupled to any particular log or language.
 */
public class LogExtractor {

    /** "Type(" — start of a Java toString() object (used by findObjectStart). */
    private static final Pattern OBJECT_START =
            Pattern.compile("[A-Z][a-zA-Z0-9_]*\\(");

    /** "Type(", "Type {", "Type[" — possible start of a named object (any dialect). */
    private static final Pattern TYPE_WITH_OPENER =
            Pattern.compile("[A-Z][\\w$]*\\s*[({\\[]");

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses the whole log text (one or more lines).
     * Returns one entry per line containing a recognizable object.
     */
    public static List<ParsedEntry> extractAll(String logText) {
        // The parser is recursive: it runs on a thread with a large (configurable)
        // stack to support deep nesting without StackOverflowError.
        long stack = (long) Config.integer("extractor.stackMb") * 1024 * 1024;
        var result = new AtomicReference<List<ParsedEntry>>();
        var error = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                result.set(extractLines(logText));
            } catch (Throwable t) {
                error.set(t);
            }
        }, "log-parser", stack);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            thread.interrupt();
            throw new IllegalStateException("Parsing interrupted", ex);
        }
        if (error.get() instanceof RuntimeException re) throw re;
        if (error.get() instanceof Error er) throw er;
        return result.get();
    }

    private static List<ParsedEntry> extractLines(String logText) {
        var entries = new ArrayList<ParsedEntry>();
        int lineStart = 0;

        while (lineStart <= logText.length()) {
            int lineEnd = logText.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = logText.length();
            String rawLine = logText.substring(lineStart, lineEnd);
            if (rawLine.endsWith("\r")) rawLine = rawLine.substring(0, rawLine.length() - 1);

            int indent = 0;
            while (indent < rawLine.length() && Character.isWhitespace(rawLine.charAt(indent))) indent++;
            String line = rawLine.trim();

            if (!line.isEmpty()) {
                var best = bestCandidate(line);
                if (best != null) {
                    int base = lineStart + indent + best.start();   // absolute offset of the fragment
                    if (best.error() != null) {
                        entries.add(new ParsedEntry(line, ParsedEntry.ERROR_PREFIX + best.error(),
                                base, base, List.of(), null));
                    } else {
                        var spans = best.parser().getSpans().stream().map(sp -> sp.shifted(base)).toList();
                        int end = base + best.parser().getPosition();
                        entries.add(new ParsedEntry(line, best.parsed(), base, end, spans,
                                best.parser().getDialect().name()));
                    }
                }
            }
            if (lineEnd >= logText.length()) break;
            lineStart = lineEnd + 1;
        }

        return entries;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Finding the parseable fragment within a line
    // ─────────────────────────────────────────────────────────────────────────

    /** Result of parsing from a candidate start point with one dialect. */
    private record Candidate(int start, int priority, ObjectParser parser, Object parsed, String error) {
        int consumed()   { return parser == null ? -1 : parser.getPosition(); }
        int recognized() { return parser == null ? -1 : parser.getRecognized(); }
        int elements()   { return parser == null ? -1 : parser.getSpans().size(); }
    }

    /**
     * Selection criteria (in order):
     *   1) the one reaching the END of the line (a log's object is usually last;
     *      leading brackets are prefixes: [INFO] [thread]…);
     *   2) the one parsing more text;
     *   3) the one yielding more properties/elements (a dialect that does not
     *      understand the separator "consumes" the text but ignores it: e.g. C# on Rust's "k: v");
     *   4) the one RECOGNIZING more values with its dialect (quoted strings,
     *      literals such as None/True): tells e.g. Python from Java;
     *   5) the dialect listed earlier in dialects.yml; 6) the earliest start.
     */
    private static boolean isBetter(Candidate c, Candidate current, String line) {
        boolean cEnd = reachesEnd(c, line), aEnd = reachesEnd(current, line);
        if (cEnd != aEnd) return cEnd;
        if (c.consumed() != current.consumed()) return c.consumed() > current.consumed();
        if (c.elements() != current.elements()) return c.elements() > current.elements();
        if (c.recognized() != current.recognized()) return c.recognized() > current.recognized();
        if (c.priority() != current.priority()) return c.priority() < current.priority();
        return c.start() < current.start();
    }

    private static boolean reachesEnd(Candidate c, String line) {
        if (c.consumed() < 0) return false;
        return line.substring(Math.min(line.length(), c.start() + c.consumed())).isBlank();
    }

    /**
     * Candidate start points, in order: "Type(" / "Type {" / "Type[" (not part of
     * a qualified name such as c.b.Type or $Inner) and every '{', '[' or '('.
     */
    static List<Integer> starts(String line, int max) {
        var set = new TreeSet<Integer>();
        char c0 = line.charAt(0);
        if (c0 == '{' || c0 == '[' || c0 == '(' || Character.isLetter(c0)) set.add(0);
        Matcher m = TYPE_WITH_OPENER.matcher(line);
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

    private static Candidate bestCandidate(String line) {
        int maxCandidates = Config.integer("extractor.maxCandidates");
        List<Dialect> dialects = Dialect.actives();
        var bestPerDialect = new Candidate[dialects.size()];

        Candidate best = null;
        for (int start : starts(line, maxCandidates)) {
            for (int di = 0; di < dialects.size(); di++) {
                var previous = bestPerDialect[di];
                // Pruning: a start INSIDE the best span already found by this dialect
                // would consume less (or the dialect already reached the end): it cannot win.
                if (previous != null && previous.consumed() >= 0
                        && (reachesEnd(previous, line) || start < previous.start() + previous.consumed())) continue;

                Candidate c;
                try {
                    var parser = new ObjectParser(line.substring(start), dialects.get(di));
                    Object parsed = parser.parse();
                    if (!(parsed instanceof DynamicObject || parsed instanceof Map || parsed instanceof List)) continue;
                    c = new Candidate(start, di, parser, parsed, null);
                } catch (StackOverflowError e) {
                    c = new Candidate(start, di, null, null,
                            "nesting too deep (increase extractor.stackMb in application.yml)");
                } catch (Exception e) {
                    c = new Candidate(start, di, null, null, e.getMessage());
                }
                if (previous == null || isBetter(c, previous, line)) bestPerDialect[di] = c;
                if (best == null || isBetter(c, best, line)) best = c;
            }
        }
        return best;
    }

    static String findObjectFragment(String line) {
        int idx = findObjectStart(line);
        return idx < 0 ? null : line.substring(idx);
    }

    /** Index of the first "Type(" that is not part of a qualified name (c.b.Type, $Inner), or -1. */
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

    /** Index where the first recognizable object in the line starts, or -1. */
    static int findObjectStart(String line) {
        int cls = findClassStart(line);
        if (cls >= 0) return cls;

        // Fallback: first '{' or '['
        int brace   = line.indexOf('{');
        int bracket = line.indexOf('[');

        if (brace < 0 && bracket < 0) return -1;
        if (brace < 0)   return bracket;
        if (bracket < 0) return brace;
        return Math.min(brace, bracket);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result model
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pair (original log line, parsed object).
     * {@code parsed} may be a {@link DynamicObject}, a {@link Map}, a {@link List},
     * or a primitive / String (an error message starting with {@link #ERROR_PREFIX}).
     */
    public record ParsedEntry(String sourceLine, Object parsed, int baseOffset, int endOffset,
                              List<Span> spans, String dialect) {

        /** Prefix of {@code parsed} when the line could not be parsed. */
        public static final String ERROR_PREFIX = "⚠ Parse error: ";

        public ParsedEntry(String sourceLine, Object parsed) {
            this(sourceLine, parsed, -1, -1, List.of(), null);
        }

        /** Whether this entry is a parse error rather than an object. */
        public boolean isError() {
            return parsed instanceof String s && s.startsWith(ERROR_PREFIX);
        }

        /** Does the absolute offset fall inside this entry's object? */
        public boolean contains(int offset) {
            return baseOffset >= 0 && offset >= baseOffset && offset < endOffset;
        }

        /** Path of the innermost property/element containing the offset ([] = root). */
        public List<Object> pathAt(int offset) {
            Span best = null;
            for (Span sp : spans) {
                if (sp.contains(offset) && (best == null || sp.length() < best.length())) best = sp;
            }
            return best == null ? List.of() : best.path();
        }
    }
}
