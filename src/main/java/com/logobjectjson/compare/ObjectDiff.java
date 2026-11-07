package com.logobjectjson.compare;

import com.logobjectjson.model.ArrayRef;
import com.logobjectjson.model.DynamicObject;
import com.logobjectjson.model.JsonObjectMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares a PATTERN object (the one that works) with a SAMPLE object (the one
 * being analyzed), property by property:
 *
 *   MISSING : in the pattern, not in the sample          (red,   −)
 *   EXTRA   : in the sample, not in the pattern          (green, +)
 *   TYPE    : in both, with a different kind              (blue,  ≠)
 *             (string ≠ number ≠ boolean ≠ null ≠ list ≠ object; or another class name)
 *   VALUE   : in both, same kind, different value         (gray,  ~)
 *   SAME    : equal
 *
 * Property names are case-sensitive ("name" ≠ "Name"); lists are compared by position.
 * Nothing is invented: the result only describes what both texts contain.
 */
public final class ObjectDiff {

    public enum Status { SAME, MISSING, EXTRA, TYPE, VALUE }

    /** Kind of a value, as far as a log can tell. */
    public enum Kind { OBJECT, LIST, STRING, NUMBER, BOOLEAN, NULL }

    /**
     * One compared property/element.
     *
     * @param segment  key (String) or index (Integer); null for the root
     * @param pattern  value in the pattern (null if MISSING… or if it is a null value: see {@link #inPattern})
     * @param sample   value in the sample
     */
    public record Node(Object segment, Status status, boolean inPattern, boolean inSample,
                       Object pattern, Object sample, List<Node> children) {

        /** Number of differences in this node and below (SAME nodes count 0). */
        public int differences() {
            int n = status == Status.SAME ? 0 : 1;
            for (var c : children) n += c.differences();
            return n;
        }

        /** Differences strictly below this node. */
        public int differencesInside() {
            int n = 0;
            for (var c : children) n += c.differences();
            return n;
        }

        /** The value shown for this node: the sample's, or the pattern's if missing in the sample. */
        public Object shown() {
            return inSample ? sample : pattern;
        }
    }

    private ObjectDiff() { }

    public static Node compare(Object pattern, Object sample) {
        return node(null, true, pattern, true, sample);
    }

    public static Kind kind(Object v) {
        if (v == null) return Kind.NULL;
        if (v instanceof DynamicObject || v instanceof Map) return Kind.OBJECT;
        if (v instanceof List) return Kind.LIST;
        if (v instanceof Boolean) return Kind.BOOLEAN;
        if (v instanceof Number) return Kind.NUMBER;
        return Kind.STRING;                      // String, ArrayRef (printed reference)
    }

    private static Node node(Object segment, boolean inP, Object p, boolean inS, Object s) {
        if (!inS) return new Node(segment, Status.MISSING, true, false, p, null, onlyOneSide(p, true));
        if (!inP) return new Node(segment, Status.EXTRA, false, true, null, s, onlyOneSide(s, false));

        Kind kp = kind(p), ks = kind(s);
        var children = new ArrayList<Node>();
        if (kp == Kind.OBJECT && ks == Kind.OBJECT) {
            Map<String, Object> mp = properties(p), ms = properties(s);
            for (String k : mergedKeys(mp, ms)) {
                children.add(node(k, mp.containsKey(k), mp.get(k), ms.containsKey(k), ms.get(k)));
            }
            boolean sameClass = Objects.equals(className(p), className(s));
            return new Node(segment, sameClass ? Status.SAME : Status.TYPE, true, true, p, s, children);
        }
        if (kp == Kind.LIST && ks == Kind.LIST) {
            List<?> lp = (List<?>) p, ls = (List<?>) s;
            for (int i = 0; i < Math.max(lp.size(), ls.size()); i++) {
                boolean ip = i < lp.size(), is = i < ls.size();
                children.add(node(i, ip, ip ? lp.get(i) : null, is, is ? ls.get(i) : null));
            }
            return new Node(segment, Status.SAME, true, true, p, s, children);
        }
        if (kp != ks) return new Node(segment, Status.TYPE, true, true, p, s, List.of());
        return new Node(segment, sameValue(p, s) ? Status.SAME : Status.VALUE, true, true, p, s, List.of());
    }

    /** Children of a subtree that exists on one side only (they share the parent's status). */
    private static List<Node> onlyOneSide(Object v, boolean pattern) {
        var out = new ArrayList<Node>();
        if (kind(v) == Kind.OBJECT) {
            properties(v).forEach((k, x) -> out.add(pattern ? node(k, true, x, false, null) : node(k, false, null, true, x)));
        } else if (v instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) out.add(pattern ? node(i, true, l.get(i), false, null) : node(i, false, null, true, l.get(i)));
        }
        return out;
    }

    /**
     * Union of keys in the sample's order, with the pattern-only keys inserted
     * where they are in the pattern (right after the previous common key).
     */
    static List<String> mergedKeys(Map<String, Object> pattern, Map<String, Object> sample) {
        var out = new LinkedHashSet<String>();
        var pKeys = new ArrayList<>(pattern.keySet());
        int pi = 0;
        for (String k : sample.keySet()) {
            if (pattern.containsKey(k)) {
                int at = pKeys.indexOf(k);
                for (; pi < at; pi++) if (!sample.containsKey(pKeys.get(pi))) out.add(pKeys.get(pi));
                pi = Math.max(pi, at + 1);
            }
            out.add(k);
        }
        for (; pi < pKeys.size(); pi++) if (!sample.containsKey(pKeys.get(pi))) out.add(pKeys.get(pi));
        for (String k : pKeys) if (!sample.containsKey(k)) out.add(k);   // any not placed yet
        return new ArrayList<>(out);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> properties(Object v) {
        if (v instanceof DynamicObject o) return o.properties();
        if (v instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<String, Object>();
            m.forEach((k, x) -> out.put(String.valueOf(k), x));
            return out;
        }
        return Map.of();
    }

    /** Class name of an object, "Map"/"JSON" for maps (so a class vs a map is a type change). */
    public static String className(Object v) {
        if (v instanceof DynamicObject o) return o.className();
        if (v instanceof JsonObjectMap) return "{JSON}";
        if (v instanceof Map) return "{Map}";
        return null;
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return new java.math.BigDecimal(x.toString()).compareTo(new java.math.BigDecimal(y.toString())) == 0;
        }
        if (a instanceof ArrayRef x) a = x.raw();
        if (b instanceof ArrayRef y) b = y.raw();
        return Objects.equals(a, b);
    }
}
