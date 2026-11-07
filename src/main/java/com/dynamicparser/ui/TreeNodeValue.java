package com.dynamicparser.ui;

/**
 * User-object almacenado en cada DefaultMutableTreeNode.
 * Guarda la etiqueta visible, el objeto parseado original (para re-enraizar
 * el árbol o serializar a JSON) y un tooltip opcional.
 * toString() devuelve la etiqueta para que JTree renderice sin cambios.
 */
public record TreeNodeValue(String label, Object value, String tooltip) {

    public TreeNodeValue(String label, Object value) {
        this(label, value, null);
    }

    @Override
    public String toString() { return label; }
}
