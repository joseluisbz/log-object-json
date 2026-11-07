package com.logobjectjson.parser;

import com.logobjectjson.model.ArrayRef;
import com.logobjectjson.model.DynamicObject;
import com.logobjectjson.model.JsonObjectMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recursive-descent parser for objects printed by different languages. The
 * rules (literals, separators, quotes, object forms…) come from a
 * {@link Dialect} defined in {@code dialects.yml}: no language syntax is
 * hard-coded.
 *
 * Supported forms (depending on what the dialect enables):
 *   Type(k=v, ...)        parenObject     (Lombok, Python dataclass, Rust tuple struct)
 *   Type[k=v, ...]        bracketObject   (Java record)
 *   Type { k = v, ... }   braceObject     (C# record, Rust struct)
 *   Type(1, 2)            positional      → properties "0", "1", …
 *   {k=v} / {k: v}        maps (any of the dialect's separators)
 *   {"k": v}              JSON object     (embeddedJson)
 *   {1, 2}                set → list      (sets)
 *   [a, b]                list
 *   (a, b)                tuple → list    (tuples)
 *   [I@1a2b               array without contents (Java's default Object.toString())
 *   literals              the dialect's nulls / trues / falses
 *   numbers               Integer, Long or Double
 *   "text" / 'text'       the dialect's quotes
 *   unquoted text         String
 *
 * Property names are preserved exactly as they come.
 */
public class ObjectParser {

    /** Array with default Object.toString(): [I@1a2b, [[Ljava.lang.String;@1a2b */
    private static final Pattern ARRAY_REF =
            Pattern.compile("(\\[+)([BCDFIJSZ]|L[\\w.$]+;)@[0-9a-fA-F]+");

    private final String input;
    private final Dialect d;
    private int pos;

    /** Values "recognized" by the dialect (quoted strings, literals): used to pick a dialect. */
    private int recognized;

    /** Key→value-end spans of each property/element (offsets relative to input). */
    private final List<Span> spans = new ArrayList<>();
    /** Current path while descending, linked (O(1) per level; no copies). */
    private Span.Path path;

    public ObjectParser(String input) {
        this(input, Dialect.defaultDialect());
    }

    public ObjectParser(String input, Dialect dialect) {
        this.input = input;
        this.d = dialect;
        this.pos = 0;
    }

    /** Entry point: parses the first value found in the input. */
    public Object parse() {
        skipWhitespace();
        return parseValue();
    }

    /** Current cursor position (how much text was consumed). */
    public int getPosition() { return pos; }

    /** Spans recorded by the last parse(), to locate text → tree node. */
    public List<Span> getSpans() { return spans; }

    public int getRecognized() { return recognized; }

    public Dialect getDialect() { return d; }

    /** Parses the value of a property/element, recording its span [start, end). */
    private Object parseValueAt(Object segment, int start) {
        var current = new Span.Path(segment, path);
        path = current;
        try {
            Object v = parseValue();
            spans.add(new Span(start, pos, current));
            return v;
        } finally {
            path = current.parent();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main dispatcher
    // ─────────────────────────────────────────────────────────────────────────

    private Object parseValue() {
        skipWhitespace();
        if (pos >= input.length()) return null;

        char c = input.charAt(pos);

        if (c == '{') return parseBraces();
        if (c == '[') {
            ArrayRef ref = tryArrayRef();
            return ref != null ? ref : parseSequence('[', ']');
        }
        if (c == '(' && d.tuples()) return parseSequence('(', ')');
        if (d.isQuote(c)) {
            recognized++;
            return parseQuotedString(c);
        }
        if (c == '-') return parseNumberOrPlain();
        if (Character.isDigit(c)) return parseNumber();
        if (Character.isLetter(c) || c == '_') return parseWordValue();

        return readPlainString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Values starting with a letter: named object, literal or text
    // ─────────────────────────────────────────────────────────────────────────

    private Object parseWordValue() {
        int start = pos;
        String name = readTypeName();
        int nameEnd = pos;
        skipWhitespace();

        if (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '(' && d.parenObject()) return parseObject(name, '(', ')');
            if (c == '[' && d.bracketObject() && looksLikeRecord()) return parseObject(name, '[', ']');
            if (c == '{' && d.braceObject()) return parseObject(name, '{', '}');
        }

        // Not an object: go back right after the name (without losing the following space)
        pos = nameEnd;

        // Dialect literals, only if the value ends there ("null pointer" is text)
        if (valueEnds()) {
            if (d.nulls().contains(name))  { recognized++; return null; }
            if (d.trues().contains(name))  { recognized++; return Boolean.TRUE; }
            if (d.falses().contains(name)) { recognized++; return Boolean.FALSE; }
        }

        // Plain text starting with a letter (e.g. dates, codes, Enum::Variant)
        pos = start;
        return readPlainStringContinuation().trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Named object: Type(...), Type[...], Type { ... }
    // ─────────────────────────────────────────────────────────────────────────

    private DynamicObject parseObject(String className, char open, char close) {
        consume(open);
        var props = new LinkedHashMap<String, Object>();
        int positional = 0;

        while (pos < input.length() && input.charAt(pos) != close) {
            int before = pos;   // guard: if a pass does not advance, stop (avoids infinite loops)
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) == close) break;
            if (isClosing(input.charAt(pos))) break;      // unrelated closing bracket: malformed text

            int start = pos;
            String key = readKeyOrQuoted();
            skipWhitespace();

            if (key != null && !key.isEmpty() && pos < input.length() && d.isSeparator(input.charAt(pos))) {
                pos++; // key/value separator
                props.put(key, parseValueAt(key, start));
            } else if (d.positional()) {
                pos = start;                              // not "key=": it is a positional argument
                String k = String.valueOf(positional++);
                props.put(k, parseValueAt(k, start));
            } else if (key == null || key.isEmpty()) {
                pos++;                                    // unexpected character
            }

            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ',') pos++;
            if (pos == before) break;    // unexpected character (e.g. a closing bracket that does not match)
        }

        if (pos < input.length() && input.charAt(pos) == close) pos++;
        return new DynamicObject(className, props);
    }

    /** After "Name", does the '[' open a record? ("[]" or "[ident<sep>") */
    private boolean looksLikeRecord() {
        int p = pos + 1;
        while (p < input.length() && Character.isWhitespace(input.charAt(p))) p++;
        if (p < input.length() && input.charAt(p) == ']') return true;
        int start = p;
        while (p < input.length() && (Character.isLetterOrDigit(input.charAt(p)) || input.charAt(p) == '_' || input.charAt(p) == '$')) p++;
        if (p == start) return false;
        while (p < input.length() && Character.isWhitespace(input.charAt(p))) p++;
        return p < input.length() && d.isSeparator(input.charAt(p));
    }

    /** Tries to read an array reference "[I@1a2b"; null if it is not one (does not advance). */
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
    // Braces: map {k=v}/{k: v}, JSON {"k": v} or set {a, b}
    // ─────────────────────────────────────────────────────────────────────────

    private Object parseBraces() {
        int saved = pos;
        pos++; // '{'
        skipWhitespace();
        boolean empty = pos < input.length() && input.charAt(pos) == '}';
        boolean doubleQuotedKey = pos < input.length() && input.charAt(pos) == '"';
        char sep = 0;
        if (!empty) {
            readKeyOrQuoted();
            skipWhitespace();
            if (pos < input.length()) {
                char c = input.charAt(pos);
                if (d.isSeparator(c) || (d.embeddedJson() && c == ':')) sep = c;
            }
        }
        pos = saved;

        if (!empty && sep == 0 && d.sets()) return parseSequence('{', '}');
        boolean json = d.embeddedJson() && sep == ':' && (doubleQuotedKey || !d.isSeparator(':'));
        return parseMap(json);
    }

    private Map<String, Object> parseMap(boolean json) {
        consume('{');
        Map<String, Object> map = json ? new JsonObjectMap() : new LinkedHashMap<>();

        while (pos < input.length() && input.charAt(pos) != '}') {
            int before = pos;   // guard: if a pass does not advance, stop (avoids infinite loops)
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) == '}') break;
            if (isClosing(input.charAt(pos))) break;

            int start = pos;
            String key = readKeyOrQuoted();
            if (key == null || key.isEmpty()) { pos++; continue; }

            skipWhitespace();
            if (pos < input.length() && (d.isSeparator(input.charAt(pos)) || (json && input.charAt(pos) == ':'))) {
                pos++;
                map.put(key, parseValueAt(key, start));
            }

            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ',') pos++;
            if (pos == before) break;
        }

        if (pos < input.length() && input.charAt(pos) == '}') pos++;
        return map;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sequences: [a, b], (a, b) and {a, b}
    // ─────────────────────────────────────────────────────────────────────────

    private List<Object> parseSequence(char open, char close) {
        consume(open);
        var list = new ArrayList<Object>();

        while (pos < input.length() && input.charAt(pos) != close) {
            int before = pos;   // guard: if a pass does not advance, stop (avoids infinite loops)
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) == close) break;
            if (isClosing(input.charAt(pos))) break;      // unrelated closing bracket: malformed text

            list.add(parseValueAt(list.size(), pos));

            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ',') pos++;
            if (pos == before) break;
        }

        if (pos < input.length() && input.charAt(pos) == close) pos++;
        return list;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Strings and numbers
    // ─────────────────────────────────────────────────────────────────────────

    private String parseQuotedString(char quote) {
        consume(quote);
        var sb = new StringBuilder();

        while (pos < input.length() && input.charAt(pos) != quote) {
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
                        if (d.isQuote(esc)) sb.append(esc);          // \" or \'
                        else { sb.append('\\'); sb.append(esc); }
                    }
                }
            } else {
                sb.append(c);
            }
            pos++;
        }

        if (pos < input.length()) pos++; // closing quote
        return sb.toString();
    }

    private char parseUnicodeEscape() {
        pos++; // the 'u' was already consumed
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
        pos++; // skip '-'
        if (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
            pos = savedPos;
            return parseNumber();
        }
        // Not a number: plain text starting with '-' (e.g. code=-)
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

        // Only a number if the value ends here (delimiter or end). If text
        // follows —e.g. "123-report.pdf", "22/10/2026" or "1.0.3"— it is a String.
        if (!valueEnds()) {
            pos = start;
            return readPlainString();
        }

        String raw = input.substring(start, pos);
        try {
            if (decimal || exponent) return Double.parseDouble(raw);
            long v = Long.parseLong(raw);
            // Careful: "cond ? (int) v : v" would promote both branches to long; box explicitly
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) return Integer.valueOf((int) v);
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reading plain (unquoted) text
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads until a delimiter (comma or container close) at depth 0.
     * Tracks parenthesis/brace/bracket depth so it does not cut too early.
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

    /** Reads a text value starting with a letter up to the next delimiter. */
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
    // Keys, identifiers and type names
    // ─────────────────────────────────────────────────────────────────────────

    /** Property key: in the dialect's quotes, or unquoted (see readKey). */
    private String readKeyOrQuoted() {
        if (pos < input.length() && d.isQuote(input.charAt(pos))) {
            return parseQuotedString(input.charAt(pos));
        }
        return readKey();
    }

    /**
     * Unquoted key: letters, digits, '_', '.', '-', '$'. Preserves the original
     * case exactly (camelCase, snake_case, custom_field__c, etc.).
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
     * Type name, possibly qualified: Type, pkg.Type, module::Type
     * ('.' or '::' are only included if another identifier follows).
     */
    private String readTypeName() {
        var sb = new StringBuilder(readIdentifier());
        while (pos < input.length()) {
            int sepLen = input.startsWith("::", pos) ? 2 : input.charAt(pos) == '.' ? 1 : 0;
            if (sepLen == 0 || pos + sepLen >= input.length()) break;
            char next = input.charAt(pos + sepLen);
            if (!(Character.isLetter(next) || next == '_')) break;
            sb.append(input, pos, pos + sepLen);
            pos += sepLen;
            sb.append(readIdentifier());
        }
        return sb.toString();
    }

    /** Identifier (letters, digits, '_', '$'). */
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
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean isClosing(char c) {
        return c == ')' || c == ']' || c == '}';
    }

    /** From pos (ignoring spaces), does a value delimiter or the end of text follow? */
    private boolean valueEnds() {
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
