package com.logobjectjson.ui;

import com.logobjectjson.Messages;
import com.logobjectjson.model.ArrayRef;
import com.logobjectjson.model.DynamicObject;
import com.logobjectjson.model.JsonObjectMap;
import com.logobjectjson.parser.LogExtractor.ParsedEntry;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;
import java.util.Map;

/**
 * Builds the tree model from the parsed dynamic objects.
 *
 * One node per property, TYPE first and then NAME (as in Java), showing the
 * type only when the log states it:
 *
 *   [⬡] Type name             object with a known class (printed by its toString)
 *   <,>  name  (n)            {k=v}: Map.toString() — implementation unknown
 *   {}   name  (n)            {"k": v}: embedded JSON object
 *   []   name  (n)            sequence: Array, List or Set (indistinguishable)
 *   T[] name  ⚠               array printed as [Lx;@hash: type is certain, no contents
 *   name = value              leaf (the exact type cannot be known from the log)
 *
 * Each type's icon is drawn by {@link TreeIcons}. In lists the "name" is the index.
 */
public class ObjectTreeBuilder {

    private static final int MAX_LEAF_TEXT = 120;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** JTree root: each log entry is a direct child (the UI only builds it for one object). */
    public static DefaultMutableTreeNode buildRoot(List<ParsedEntry> entries) {
        var values = entries.stream().map(ParsedEntry::parsed).toList();
        var root = new DefaultMutableTreeNode(new TreeNodeValue(
                Messages.get("tree.root", entries.size()), values,
                Messages.get("tree.root.tip", entries.size())));
        for (int i = 0; i < entries.size(); i++) {
            String name = entries.size() == 1 ? null : Messages.get("tree.entry", i + 1);
            root.add(buildNode(name, entries.get(i).parsed()));
        }
        return root;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recursive construction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param name property name, index "[i]" or null (root)
     */
    public static DefaultMutableTreeNode buildNode(String name, Object value) {
        String nameSuffix = name == null ? "" : " " + name;

        if (value instanceof DynamicObject obj) {
            var node = node(obj.className() + nameSuffix, obj, Messages.get("tree.tip.class", obj.className()));
            for (var e : obj.properties().entrySet()) {
                node.add(buildNode(e.getKey(), e.getValue()));
            }
            return node;
        }

        if (value instanceof Map<?, ?> map) {
            boolean json = map instanceof JsonObjectMap;
            // The symbol (<,> Map / {} JSON) comes from the icon; the text is just name and count
            var node = node(containerLabel(name, map.size()), map,
                    Messages.get(json ? "tree.tip.json" : "tree.tip.map"));
            for (var e : map.entrySet()) {
                node.add(buildNode(String.valueOf(e.getKey()), e.getValue()));
            }
            return node;
        }

        if (value instanceof List<?> list) {
            var node = node(containerLabel(name, list.size()), list, Messages.get("tree.tip.sequence"));
            for (int i = 0; i < list.size(); i++) {
                node.add(buildNode("[" + i + "]", list.get(i)));
            }
            return node;
        }

        if (value instanceof ArrayRef ref) {
            return node(ref.javaType() + nameSuffix + "  " + Messages.get("tree.arrayRef.label"), ref,
                    Messages.get("tree.tip.arrayRef") + "  (" + ref.raw() + ")");
        }

        // Leaf
        String label = name == null ? formatLeaf(value) : name + "  =  " + formatLeaf(value);
        return node(label, value, Messages.get("tree.tip.leaf"));
    }

    private static String containerLabel(String name, int n) {
        return (name == null ? "" : name + "  ") + "(" + n + ")";
    }

    private static DefaultMutableTreeNode node(String label, Object value, String tooltip) {
        return new DefaultMutableTreeNode(new TreeNodeValue(label, value, tooltip));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    static String formatLeaf(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            if (s.length() > MAX_LEAF_TEXT) return "\"" + s.substring(0, MAX_LEAF_TEXT) + "…\"";
            return "\"" + s + "\"";
        }
        return String.valueOf(value);
    }
}
