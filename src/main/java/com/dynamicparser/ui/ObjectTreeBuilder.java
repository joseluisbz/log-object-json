package com.dynamicparser.ui;

import com.dynamicparser.model.ArrayRef;
import com.dynamicparser.model.DynamicObject;
import com.dynamicparser.model.JsonObjectMap;
import com.dynamicparser.parser.LogExtractor.ParsedEntry;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;
import java.util.Map;

/**
 * Construye el modelo de árbol a partir de los objetos dinámicos parseados.
 *
 * Un nodo por propiedad, con el TIPO primero y luego el NOMBRE (como en Java),
 * mostrando el tipo sólo cuando el log lo indica:
 *
 *   [⬡] Clase nombre          objeto con clase conocida (la imprime su toString)
 *   <,>  nombre  (n)         {k=v}: Map.toString() — implementación desconocida
 *   {}   nombre  (n)         {"k": v}: objeto JSON embebido
 *   []   nombre  (n)         secuencia: Array, List o Set (no se distingue)
 *
 * El ícono de cada tipo lo dibuja {@link IconosArbol} (ver el renderer en MainFrame).
 *   T[] nombre  ⚠            arreglo impreso como [Lx;@hash: tipo seguro, sin contenido
 *   nombre = valor           hoja (el tipo exacto no se puede saber desde el log)
 *
 * En listas, el "nombre" es el índice: ⬡ Clase [0], [ ] [1]  (n), [2] = valor.
 */
public class ObjectTreeBuilder {

    private static final String TIP_MAP =
            "Map  —  impreso como {clave=valor}; la implementación (HashMap, LinkedHashMap, "
            + "TreeMap…) no aparece en el log.";
    private static final String TIP_JSON =
            "Objeto JSON embebido en el log ({\"clave\": valor}).";
    private static final String TIP_SEQ =
            "Array, List o Set  —  el log imprime los tres igual: [a, b, c].";
    private static final String TIP_ARRAY_REF =
            "Arreglo impreso con Object.toString(): el tipo es seguro, pero el log no contiene sus elementos.";
    private static final String TIP_HOJA =
            "El tipo exacto (int/Integer/long/…) no se puede determinar desde el log.";

    // ─────────────────────────────────────────────────────────────────────────
    // API pública
    // ─────────────────────────────────────────────────────────────────────────

    /** Raíz del JTree: cada entrada del log es un hijo directo. */
    public static DefaultMutableTreeNode buildRoot(List<ParsedEntry> entries) {
        String rootLabel = entries.size() == 1
                ? "Resultado — 1 objeto"
                : "Resultado — " + entries.size() + " objeto(s)";
        // La raíz guarda la lista de objetos: con varias entradas, el JSON es un arreglo de ellas
        var valores = entries.stream().map(ParsedEntry::parsed).toList();
        var root = new DefaultMutableTreeNode(new TreeNodeValue(rootLabel, valores,
                entries.size() + " objeto(s) encontrados en el texto"));

        for (int i = 0; i < entries.size(); i++) {
            String nombre = entries.size() == 1 ? null : "· entrada " + (i + 1);
            root.add(buildNode(nombre, entries.get(i).parsed()));
        }
        return root;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Construcción recursiva
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param nombre nombre de la propiedad, índice "[i]" o null (raíz)
     */
    public static DefaultMutableTreeNode buildNode(String nombre, Object value) {
        String sufijoNombre = nombre == null ? "" : " " + nombre;

        if (value instanceof DynamicObject obj) {
            var node = node(obj.className() + sufijoNombre, obj, "Clase " + obj.className());
            for (var e : obj.properties().entrySet()) {
                node.add(buildNode(e.getKey(), e.getValue()));
            }
            return node;
        }

        if (value instanceof Map<?, ?> map) {
            boolean json = map instanceof JsonObjectMap;
            // El símbolo (<,> Map / {} JSON) lo pone el ícono; el texto es sólo nombre y cantidad
            var node = node(etiquetaContenedor(nombre, map.size()), map, json ? TIP_JSON : TIP_MAP);
            for (var e : map.entrySet()) {
                node.add(buildNode(String.valueOf(e.getKey()), e.getValue()));
            }
            return node;
        }

        if (value instanceof List<?> list) {
            var node = node(etiquetaContenedor(nombre, list.size()), list, TIP_SEQ);
            for (int i = 0; i < list.size(); i++) {
                node.add(buildNode("[" + i + "]", list.get(i)));
            }
            return node;
        }

        if (value instanceof ArrayRef ref) {
            return node(ref.javaType() + sufijoNombre + "  ⚠ sin contenido en el log", ref,
                    TIP_ARRAY_REF + "  (" + ref.raw() + ")");
        }

        // Hoja
        String label = nombre == null ? formatLeaf(value) : nombre + "  =  " + formatLeaf(value);
        return node(label, value, TIP_HOJA);
    }

    private static String etiquetaContenedor(String nombre, int n) {
        return (nombre == null ? "" : nombre + "  ") + "(" + n + ")";
    }

    private static DefaultMutableTreeNode node(String label, Object value, String tooltip) {
        return new DefaultMutableTreeNode(new TreeNodeValue(label, value, tooltip));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidades
    // ─────────────────────────────────────────────────────────────────────────

    static String formatLeaf(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            if (s.length() > 120) return "\"" + s.substring(0, 120) + "…\"";
            return "\"" + s + "\"";
        }
        return String.valueOf(value);
    }
}
