package com.logobjectjson.ui.fold;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Computes the JSON path (e.g. {@code order.lines[0].product.price})
 * of the key or value at a text offset. Tolerates edited JSON:
 * it only needs the text up to the offset to be reasonably valid.
 *
 * Segments: {@link String} = object key, {@link Integer} = array index.
 */
public final class JsonPath {

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private JsonPath() { }

    private static final class Frame {
        final boolean array;
        String key;          // object: current key (null = waiting for a key)
        int index;           // array: current index
        boolean hasValue;    // array: some element has started
        Frame(boolean array) { this.array = array; }
    }

    /** Path at the offset, or null if the offset is not on a key/value. */
    public static List<Object> pathAt(String text, int offset) {
        if (offset < 0 || offset >= text.length()) return null;
        char at = text.charAt(offset);
        if (Character.isWhitespace(at) || at == ',' || at == ':') return null;

        Deque<Frame> stack = new ArrayDeque<>();
        int i = 0;
        while (i < offset) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = stringEnd(text, i);
                if (end >= offset) {               // the offset is inside this string
                    break;
                }
                Frame f = stack.peek();
                if (f != null && !f.array && f.key == null) {
                    f.key = content(text, i, end);
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

        // Case: offset inside a key (string before ':')
        Frame top = stack.peek();
        if (top != null && !top.array && top.key == null && i < text.length() && text.charAt(i) == '"') {
            int end = stringEnd(text, i);
            if (end > i) top.key = content(text, i, end);
        }

        var path = new ArrayList<Object>();
        var frames = new ArrayList<>(stack);
        java.util.Collections.reverse(frames);    // from the root inwards
        for (Frame f : frames) {
            if (f.array) path.add(f.index);
            else if (f.key != null) path.add(f.key);
            else return path.isEmpty() ? null : path;   // no key yet
        }
        // On the container's closing bracket: the path is the container's
        if ((at == '}' || at == ']') && !path.isEmpty()) path.remove(path.size() - 1);
        return path.isEmpty() ? null : path;
    }

    /**
     * Inverse of {@link #pathAt}: offset where the key (object) or element
     * (array) of the given path starts; 0 for the empty path; -1 if it does not exist.
     */
    public static int offsetOf(String text, List<Object> target) {
        if (target.isEmpty()) return 0;
        Deque<Frame> stack = new ArrayDeque<>();
        boolean arrayNeedsValue = false;   // in an array: waiting for an element to start
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            Frame f = stack.peek();

            // Start of an array element
            if (f != null && f.array && arrayNeedsValue && c != ']' && c != ',') {
                arrayNeedsValue = false;
                if (matches(stack, target)) return i;
            }

            if (c == '"') {
                int end = stringEnd(text, i);
                if (f != null && !f.array && f.key == null) {
                    f.key = content(text, i, end);
                    if (matches(stack, target)) return i;
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

    /** Is the stack's current path (root → top) equal to target? */
    private static boolean matches(Deque<Frame> stack, List<Object> target) {
        if (stack.size() != target.size()) return false;
        var it = stack.descendingIterator();
        for (Object seg : target) {
            Frame f = it.next();
            Object actual = f.array ? (Object) f.index : f.key;
            if (!seg.equals(actual)) return false;
        }
        return true;
    }

    /** "a.b[0].c"; non-identifier keys as ["odd key"]. */
    public static String format(List<Object> path) {
        return format(path, null);
    }

    /**
     * Same as {@link #format(List)}, but prefixes a mark to some keys
     * (e.g. "&lt;,&gt;" to keys whose value is a Map).
     *
     * @param mark prefix for the segment at position i (null or "" = none)
     */
    public static String format(List<Object> path, java.util.function.IntFunction<String> mark) {
        var sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            Object seg = path.get(i);
            if (seg instanceof Integer idx) {
                sb.append('[').append(idx).append(']');
            } else {
                String k = (String) seg;
                String pre = mark == null ? null : mark.apply(i);
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

    /** Offset of the closing quote of the string starting at start (or end of line). */
    private static int stringEnd(String text, int start) {
        for (int j = start + 1; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '\\') { j++; continue; }
            if (c == '"' || c == '\n') return j;
        }
        return text.length() - 1;
    }

    private static String content(String text, int start, int end) {
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
