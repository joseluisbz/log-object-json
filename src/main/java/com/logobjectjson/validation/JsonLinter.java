package com.logobjectjson.validation;

import com.logobjectjson.Messages;
import com.logobjectjson.validation.Problem.Severity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tolerant JSON checker: unlike a regular parser it does not stop at the first
 * error. It recovers and keeps going, so it can report several problems at once
 * with clear messages (extra/missing commas, unquoted keys, unclosed strings,
 * mismatched or unclosed brackets, invalid literals and numbers, duplicate keys…).
 *
 * It is a helper for the user: the final "valid / invalid" verdict is still
 * Jackson's (see the callers).
 */
public final class JsonLinter {

    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

    private enum T { LBRACE, RBRACE, LBRACK, RBRACK, COLON, COMMA, STRING, NUMBER, LITERAL, WORD }

    private record Tok(T type, int start, int end, String text) {
        boolean startsValue() {
            return type == T.STRING || type == T.NUMBER || type == T.LITERAL || type == T.WORD
                    || type == T.LBRACE || type == T.LBRACK;
        }
        String shown() { return "'" + (text.length() > 30 ? text.substring(0, 30) + "…" : text) + "'"; }
    }

    /** What the container expects next. */
    private enum State { KEY, COLON, VALUE, COMMA }

    private static final class Frame {
        final boolean object;
        final int open;
        final int openLine;
        State state;
        int pendingComma = -1;          // offset of a comma not yet followed by an element
        String lastKey;
        final Set<String> keys = new HashSet<>();

        Frame(boolean object, int open, int openLine) {
            this.object = object;
            this.open = open;
            this.openLine = openLine;
            this.state = object ? State.KEY : State.VALUE;
        }

        char closer() { return object ? '}' : ']'; }
        String kind() { return Messages.get(object ? "lint.object" : "lint.array"); }
    }

    private final String text;
    private final List<Problem> problems = new ArrayList<>();
    private final Deque<Frame> stack = new ArrayDeque<>();

    private JsonLinter(String text) {
        this.text = text;
    }

    /** All problems found in the text, sorted by position. */
    public static List<Problem> lint(String text) {
        var l = new JsonLinter(text);
        l.run();
        l.problems.sort(Comparator.comparingInt(Problem::offset));
        return List.copyOf(l.problems);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Structure
    // ─────────────────────────────────────────────────────────────────────

    private void run() {
        List<Tok> toks = tokenize();
        boolean rootStarted = false, rootDone = false, afterEndReported = false;

        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            Frame f = stack.peek();

            if (f == null) {
                if (rootDone) {
                    if (!afterEndReported) error(t.start(), text.length() - t.start(), "lint.afterEnd");
                    afterEndReported = true;
                    continue;
                }
                if (t.startsValue()) {
                    rootStarted = true;
                    if (t.type() == T.LBRACE || t.type() == T.LBRACK) push(t);
                    else {
                        checkValue(t);
                        rootDone = true;
                    }
                } else {
                    error(t, "lint.unexpectedChar", t.shown());
                }
                continue;
            }

            switch (t.type()) {
                case RBRACE, RBRACK -> {
                    close(t);
                    if (stack.isEmpty()) rootDone = true;
                }
                case COMMA -> comma(f, t);
                case COLON -> {
                    if (f.object && f.state == State.COLON) f.state = State.VALUE;
                    else error(t, "lint.unexpectedChar", t.shown());
                }
                default -> element(f, t, i + 1 < toks.size() ? toks.get(i + 1) : null);
            }
        }

        while (!stack.isEmpty()) {
            Frame f = stack.pop();
            error(f.open, 1, "lint.unclosed", f.kind(), f.openLine, "'" + f.closer() + "'");
        }
        if (!rootStarted) error(0, Math.max(1, text.length()), "lint.empty");
    }

    private void comma(Frame f, Tok t) {
        if (f.state == State.COMMA) {
            f.state = f.object ? State.KEY : State.VALUE;
            f.pendingComma = t.start();
        } else if (f.object && f.state == State.VALUE) {
            error(t, "lint.missingValue", quote(f.lastKey));
            f.state = State.KEY;
            f.pendingComma = t.start();
        } else if (f.object && f.state == State.COLON) {
            error(t, "lint.missingColon", quote(f.lastKey));
            f.state = State.KEY;
            f.pendingComma = t.start();
        } else {
            error(t, "lint.extraComma");
        }
    }

    /** A token that starts a key or a value inside a container. */
    private void element(Frame f, Tok t, Tok next) {
        f.pendingComma = -1;
        if (!f.object) {
            if (f.state == State.COMMA) error(t, "lint.missingComma", t.shown());
            value(f, t);
            return;
        }
        switch (f.state) {
            case KEY -> key(f, t);
            case COLON -> {
                error(t, "lint.missingColon", quote(f.lastKey));
                value(f, t);
            }
            case VALUE -> value(f, t);
            case COMMA -> {
                error(t, "lint.missingComma", t.shown());
                boolean looksLikeKey = (t.type() == T.STRING || t.type() == T.WORD)
                        && next != null && next.type() == T.COLON;
                if (looksLikeKey) key(f, t);
                else value(f, t);
            }
        }
    }

    private void key(Frame f, Tok t) {
        String k;
        if (t.type() == T.STRING) {
            k = unquote(t.text());
        } else if (t.type() == T.WORD || t.type() == T.LITERAL || t.type() == T.NUMBER) {
            error(t, "lint.unquotedKey", t.shown());
            k = t.text();
        } else {
            error(t, "lint.expectedKey", t.shown());
            value(f, t);
            return;
        }
        if (!f.keys.add(k)) warning(t, "lint.duplicateKey", quote(k));
        f.lastKey = k;
        f.state = State.COLON;
    }

    private void value(Frame f, Tok t) {
        f.state = State.COMMA;
        if (t.type() == T.LBRACE || t.type() == T.LBRACK) push(t);
        else checkValue(t);
    }

    private void checkValue(Tok t) {
        if (t.type() == T.WORD) error(t, "lint.invalidValue", t.shown());
    }

    private void push(Tok t) {
        stack.push(new Frame(t.type() == T.LBRACE, t.start(), lineOf(t.start())));
    }

    private void close(Tok t) {
        boolean object = t.type() == T.RBRACE;
        Frame f = stack.peek();
        if (f.object == object) {
            if (f.pendingComma >= 0) error(f.pendingComma, 1, "lint.trailingComma", t.shown());
            else if (f.object && f.state == State.COLON) error(t, "lint.missingColon", quote(f.lastKey));
            else if (f.object && f.state == State.VALUE) error(t, "lint.missingValue", quote(f.lastKey));
            stack.pop();
            return;
        }
        // Mismatch: is there an outer container this bracket closes?
        boolean matchesOuter = stack.stream().skip(1).anyMatch(o -> o.object == object);
        if (!matchesOuter) {
            error(t, "lint.unexpectedClose", t.shown());
            return;
        }
        error(t, "lint.mismatchedClose", "'" + f.closer() + "'", f.kind(), f.openLine, t.shown());
        while (!stack.isEmpty() && stack.peek().object != object) stack.pop();
        if (!stack.isEmpty()) stack.pop();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tokens
    // ─────────────────────────────────────────────────────────────────────

    private List<Tok> tokenize() {
        var out = new ArrayList<Tok>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            int s = i;
            switch (c) {
                case '{' -> { out.add(new Tok(T.LBRACE, s, ++i, "{")); continue; }
                case '}' -> { out.add(new Tok(T.RBRACE, s, ++i, "}")); continue; }
                case '[' -> { out.add(new Tok(T.LBRACK, s, ++i, "[")); continue; }
                case ']' -> { out.add(new Tok(T.RBRACK, s, ++i, "]")); continue; }
                case ':' -> { out.add(new Tok(T.COLON, s, ++i, ":")); continue; }
                case ',' -> { out.add(new Tok(T.COMMA, s, ++i, ",")); continue; }
                default -> { }
            }
            if (c == '"' || c == '\'') {
                i = s + 1;
                boolean closed = false;
                while (i < n && text.charAt(i) != '\n') {
                    char d = text.charAt(i);
                    if (d == '\\') { i += 2; continue; }
                    i++;
                    if (d == c) { closed = true; break; }
                }
                i = Math.min(i, n);
                String tokText = text.substring(s, i);
                if (!closed) error(s, i - s, "lint.unterminatedString");
                else if (c == '\'') error(s, i - s, "lint.singleQuotes", tokText);
                out.add(new Tok(T.STRING, s, i, closed && c == '"' ? tokText : '"' + tokText.substring(1) + '"'));
            } else if (c == '-' || c == '+' || c == '.' || Character.isDigit(c)) {
                while (i < n && "0123456789+-.eE".indexOf(text.charAt(i)) >= 0) i++;
                String num = text.substring(s, i);
                if (!JSON_NUMBER.matcher(num).matches()) error(s, i - s, "lint.invalidNumber", "'" + num + "'");
                out.add(new Tok(T.NUMBER, s, i, num));
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_' || text.charAt(i) == '$')) i++;
                String w = text.substring(s, i);
                boolean literal = w.equals("true") || w.equals("false") || w.equals("null");
                out.add(new Tok(literal ? T.LITERAL : T.WORD, s, i, w));
            } else {
                error(s, 1, "lint.unexpectedChar", "'" + c + "'");
                i++;
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────

    private static String unquote(String s) {
        return s.length() >= 2 ? s.substring(1, s.length() - 1) : s;
    }

    private static String quote(String k) {
        return k == null ? "" : '"' + k + '"';
    }

    /** Offsets where each line starts (computed once: lineOf is called per container). */
    private int[] lineStarts;

    private int lineOf(int offset) {
        if (lineStarts == null) {
            var starts = new ArrayList<Integer>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
            lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }
        int idx = java.util.Arrays.binarySearch(lineStarts, offset);
        return (idx >= 0 ? idx : -idx - 2) + 1;
    }

    private void error(Tok t, String key, Object... args) {
        error(t.start(), t.end() - t.start(), key, args);
    }

    private void error(int offset, int length, String key, Object... args) {
        problems.add(new Problem(offset, length, Severity.ERROR, Messages.get(key, args)));
    }

    private void warning(Tok t, String key, Object... args) {
        problems.add(new Problem(t.start(), t.end() - t.start(), Severity.WARNING, Messages.get(key, args)));
    }
}
