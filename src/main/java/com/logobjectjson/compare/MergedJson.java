package com.logobjectjson.compare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logobjectjson.compare.ObjectDiff.Node;
import com.logobjectjson.compare.ObjectDiff.Status;
import com.logobjectjson.model.ArrayRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Comparison JSON, for display only (never saved): the sample's JSON with the
 * properties missing in the sample interleaved where they are in the pattern
 * ("ghost" lines). Records which lines belong to each difference so the view
 * can color them. Same layout as the normal JSON (4-space indent, Postman style).
 */
public final class MergedJson {

    /** Lines [firstLine, lastLine] (0-based) of a difference. */
    public record Mark(int firstLine, int lastLine, Status status, List<Object> path) { }

    public record Result(String text, List<Mark> marks) { }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDENT = "    ";

    private final StringBuilder sb = new StringBuilder();
    private final List<Mark> marks = new ArrayList<>();
    private int line;

    private MergedJson() { }

    public static Result of(Node root) {
        var m = new MergedJson();
        m.value(root, 0, new ArrayList<>());
        return new Result(m.sb.toString(), List.copyOf(m.marks));
    }

    private void value(Node n, int depth, List<Object> path) {
        int first = line;
        Object v = n.shown();
        var kind = ObjectDiff.kind(v);
        if (kind == ObjectDiff.Kind.OBJECT || kind == ObjectDiff.Kind.LIST) {
            boolean object = kind == ObjectDiff.Kind.OBJECT;
            sb.append(object ? '{' : '[');
            if (n.children().isEmpty()) {
                sb.append(object ? '}' : ']');
            } else {
                for (int i = 0; i < n.children().size(); i++) {
                    var c = n.children().get(i);
                    newline(depth + 1);
                    if (object) sb.append(quote(String.valueOf(c.segment()))).append(": ");
                    var childPath = new ArrayList<>(path);
                    childPath.add(c.segment());
                    value(c, depth + 1, childPath);
                    if (i < n.children().size() - 1) sb.append(',');
                }
                newline(depth);
                sb.append(object ? '}' : ']');
            }
        } else {
            sb.append(scalar(v));
        }
        if (n.status() != Status.SAME && !path.isEmpty()) {
            // Type/value changes: the key's line only; missing/extra: the whole range
            boolean whole = n.status() == Status.MISSING || n.status() == Status.EXTRA;
            marks.add(new Mark(first, whole ? line : first, n.status(), List.copyOf(path)));
        } else if (n.status() == Status.TYPE && path.isEmpty()) {
            marks.add(new Mark(0, 0, Status.TYPE, List.of()));
        }
    }

    private void newline(int depth) {
        sb.append('\n').append(INDENT.repeat(depth));
        line++;
    }

    private static String scalar(Object v) {
        if (v instanceof ArrayRef r) v = r.raw();
        try {
            return MAPPER.writeValueAsString(v);
        } catch (Exception ex) {
            return quote(String.valueOf(v));
        }
    }

    private static String quote(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception ex) {
            return '"' + s + '"';
        }
    }
}
