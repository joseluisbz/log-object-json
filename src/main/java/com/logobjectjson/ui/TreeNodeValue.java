package com.logobjectjson.ui;

/**
 * User object stored in each DefaultMutableTreeNode.
 * Holds the visible label, the original parsed object (to serialize it to
 * JSON) and an optional tooltip.
 * toString() returns the label so JTree renders it unchanged.
 */
public record TreeNodeValue(String label, Object value, String tooltip) {

    public TreeNodeValue(String label, Object value) {
        this(label, value, null);
    }

    @Override
    public String toString() { return label; }
}
