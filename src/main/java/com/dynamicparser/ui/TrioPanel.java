package com.dynamicparser.ui;

import com.dynamicparser.parser.LogExtractor;
import com.dynamicparser.parser.LogExtractor.ParsedEntry;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.DefaultTreeCellRenderer;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import javax.swing.tree.TreePath;
import com.dynamicparser.model.DynamicObject;
import java.util.ArrayList;
import java.util.Map;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Un "trío" texto → árbol → JSON (una pestaña dentro de una sesión): la Principal
 * o una rama extraída. Sus botones actúan sólo sobre esta pestaña.
 *
 * Fuente de datos única: un editor de texto (RSyntaxTextArea ⊂ JTextArea).
 *   • Se puede pegar el log directamente o cargarlo con "Abrir archivo…"
 *     (el contenido del archivo se carga en el editor; se recuerda la última ruta).
 *   • Buscar/Reemplazar con historial (Ctrl+F / Ctrl+H).
 *   • Sincronización en vivo: al pegar, cargar o editar el texto se re-instancia
 *     solo (600 ms tras la última tecla) y se actualizan el árbol y las pestañas JSON.
 */
public class TrioPanel extends JPanel {

    /** Pide abrir una rama en una pestaña nueva: título, ruta (tooltip) y texto de la rama. */
    @FunctionalInterface
    public interface ExtraerRama {
        void extraer(String titulo, String ruta, String texto);
    }

    /** Avisa el título de esta pestaña (nombre del archivo o "Texto pegado"). */
    private final java.util.function.Consumer<String> alCambiarTitulo;
    private final ExtraerRama alExtraer;

    /** true si este trío es una rama extraída (conserva su título salvo que se cargue un archivo). */
    private boolean esRama;

    /** El único JSON de este trío (serialización de su árbol). */
    private JsonViewerPanel jsonPanel;

    /** Serializar automáticamente las entradas en la próxima instanciación (tras cargar/pegar). */
    private boolean autoSerializar;

    /** Panel con CardLayout: barra deshabilitada si aún no hay serializaciones, o las pestañas. */
    private final JPanel zonaJson = new JPanel(new CardLayout());
    private JsonViewerPanel placeholderJson;

    // ── Fuente de datos ────────────────────────────────────────────────────
    private final RSyntaxTextArea  textArea;
    private final JButton          btnAbrir;
    private final JLabel           lblArchivo;
    private final BuscarReemplazar buscarFuente;

    // ── Acción principal ───────────────────────────────────────────────────
    /** Indicador del estado de sincronización texto → árbol/JSON (✔ / ⏳ / ⚠). */
    private final JLabel       lblSync;

    /** Re-instanciación automática tras dejar de escribir. */
    private final Timer        autoInstanciar = new Timer(com.dynamicparser.Config.entero("sincronizacion.esperaMs"), e -> instanciarSiCambio());

    /** Clave de client-property con la identidad estable (entrada + ruta) del origen de cada pestaña. */
    private static final String ID_ORIGEN = "json.idOrigen";

    /** Identidad estable de un nodo entre re-instanciaciones: índice de entrada + ruta JSON. */
    private record IdNodo(int entrada, List<Object> ruta) { }
    private final JToggleButton tglAjustar;

    /** Por encima de este largo de línea no se permite el ajuste (congelaría la UI). */
    private static final int MAX_LINEA_AJUSTE = com.dynamicparser.Config.entero("editor.maxLineaAjuste");

    // ── Resultado ──────────────────────────────────────────────────────────
    private final JTree    arbolResultado;
    private final JLabel   lblEstado;

    /** Raíz original del último resultado (para "Restaurar raíz"). */
    private DefaultMutableTreeNode raizOriginal;

    /** Entradas y texto de la última instanciación (para ubicar texto → nodo). */
    private List<ParsedEntry> entradasActuales;
    private String textoInstanciado;

    /** Último archivo cargado y su contenido tal cual (para proponer el nombre del .json). */
    private Path archivoCargado;
    private String textoCargado;

    /** Punto del último click en el editor de la fuente (menú contextual). */
    private Point ultimoClickFuente;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public TrioPanel(java.util.function.Consumer<String> alCambiarTitulo, ExtraerRama alExtraer) {
        this.alCambiarTitulo = alCambiarTitulo != null ? alCambiarTitulo : t -> { };
        this.alExtraer = alExtraer;

        // Editor de la fuente (texto pegado o archivo cargado)
        textArea = new RSyntaxTextArea(12, 70) {
            @Override
            protected void configurePopupMenu(JPopupMenu popupMenu) {
                super.configurePopupMenu(popupMenu);
                for (int i = popupMenu.getComponentCount() - 1; i >= 0; i--) {
                    if (Boolean.TRUE.equals(((JComponent) popupMenu.getComponent(i)).getClientProperty("fuente.ubicar"))) {
                        popupMenu.remove(i);
                    }
                }
                int off = ultimoClickFuente != null ? viewToModel2D(ultimoClickFuente) : getCaretPosition();
                var ubicar = new JMenuItem("Ubicar en el árbol");
                ubicar.addActionListener(e -> ubicarDesdeFuente(off));
                var sep = new JPopupMenu.Separator();
                ubicar.putClientProperty("fuente.ubicar", true);
                sep.putClientProperty("fuente.ubicar", true);
                popupMenu.insert(sep, 0);
                popupMenu.insert(ubicar, 0);
            }

            /** Sugerencia (placeholder) en gris mientras el editor está vacío. */
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getDocument().getLength() > 0) return;
                String sugerencia = com.dynamicparser.Config.texto("editor.sugerencia");
                if (sugerencia == null || sugerencia.isBlank()) return;
                var g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(UIManager.getColor("TextField.inactiveForeground") != null
                            ? UIManager.getColor("TextField.inactiveForeground") : Color.GRAY);
                    g2.setFont(getFont().deriveFont(Font.ITALIC));
                    var fm = g2.getFontMetrics();
                    Insets in = getInsets();
                    int x = in.left + 4, y = in.top + fm.getAscent();
                    for (String linea : sugerencia.strip().split("\\R")) {
                        g2.drawString(linea, x, y);
                        y += fm.getHeight();
                    }
                } finally {
                    g2.dispose();
                }
            }
        };
        // Repintar al pasar de vacío a con texto (y viceversa) para mostrar/ocultar la sugerencia
        textArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { textArea.repaint(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { textArea.repaint(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        });
        textArea.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { ultimoClickFuente = e.getPoint(); }
        });
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        // Sin ajuste de línea: con líneas enormes (logs de 200k+ caracteres en una
        // sola línea) el ajuste de RSyntaxTextArea es cuadrático y congela la UI.
        textArea.setLineWrap(false);
        textArea.setHighlightCurrentLine(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        buscarFuente = new BuscarReemplazar(textArea);
        buscarFuente.agregarAccion("→ Árbol", "Ubicar en el árbol la propiedad de la coincidencia/cursor "
                + "(instancia el objeto si hace falta)", () -> ubicarDesdeFuente(textArea.getSelectionStart()));

        btnAbrir = new JButton(new IconoCarpeta(18));
        btnAbrir.setToolTipText("Abrir archivo…  carga el .log en el editor y lo instancia");
        estiloBotonPrincipal(btnAbrir);
        lblArchivo = new JLabel(" ");
        lblArchivo.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        lblArchivo.setForeground(Color.DARK_GRAY);

        // Indicador de sincronización (reemplaza al botón Instanciar)
        lblSync = new JLabel(" ");
        lblSync.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD));
        lblSync.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 8));
        autoInstanciar.setRepeats(false);

        tglAjustar = new JToggleButton(new IconoWrap());
        tglAjustar.setToolTipText("Wrap Lines — ajustar líneas largas (no disponible con líneas de más de "
                + MAX_LINEA_AJUSTE + " caracteres)");
        tglAjustar.setFocusable(false);

        // Árbol de resultado
        arbolResultado = new JTree(new DefaultMutableTreeNode("(sin resultado)"));
        arbolResultado.setRootVisible(true);
        arbolResultado.setShowsRootHandles(true);
        arbolResultado.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ToolTipManager.sharedInstance().registerComponent(arbolResultado);
        arbolResultado.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                    boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                Object uo = ((DefaultMutableTreeNode) value).getUserObject();
                setToolTipText(uo instanceof TreeNodeValue t ? t.tooltip() : null);
                if (uo instanceof TreeNodeValue t) setIcon(IconosArbol.para(t.value()));
                return this;
            }
        });

        // Etiqueta de estado
        lblEstado = new JLabel(" ");
        lblEstado.setFont(lblEstado.getFont().deriveFont(Font.ITALIC));
        lblEstado.setForeground(Color.DARK_GRAY);

        registrarListeners();
        construirLayout();

    }

    // ─────────────────────────────────────────────────────────────────────────
    // Listeners
    // ─────────────────────────────────────────────────────────────────────────

    private void registrarListeners() {

        // Editor: habilitar Instanciar cuando haya contenido
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  {
                // Pegado (bloque grande o editor que estaba vacío): serializar al sincronizar
                if (e.getLength() > 40 || e.getDocument().getLength() == e.getLength()) autoSerializar = true;
                actualizarBtnInstanciar();
            }
            @Override public void removeUpdate(DocumentEvent e)  { actualizarBtnInstanciar(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });

        btnAbrir.addActionListener(e -> elegirArchivo());

        // Instanciar
        tglAjustar.addActionListener(e -> cambiarAjuste());

        // Ctrl+Enter en el editor = Instanciar
        textArea.getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER,
                java.awt.event.InputEvent.CTRL_DOWN_MASK), "instanciar");
        textArea.getActionMap().put("instanciar", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                autoInstanciar.stop();
                instanciar(null);      // Ctrl+Enter: sincronizar ya, sin esperar
            }
        });

        // Click secundario sobre un nodo del árbol
        arbolResultado.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { mostrarMenu(e); }
            @Override public void mouseReleased(MouseEvent e) { mostrarMenu(e); }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Menú contextual del árbol
    // ─────────────────────────────────────────────────────────────────────────

    private void mostrarMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int fila = arbolResultado.getClosestRowForLocation(e.getX(), e.getY());
        var path = fila < 0 ? null : arbolResultado.getPathForRow(fila);
        if (path == null || !arbolResultado.getRowBounds(fila).contains(e.getPoint())) return;
        arbolResultado.setSelectionPath(path);

        var nodo = (DefaultMutableTreeNode) path.getLastPathComponent();
        boolean tieneValor = nodo.getUserObject() instanceof TreeNodeValue;
        boolean esRaizActual = nodo == arbolResultado.getModel().getRoot();

        var menu = new JPopupMenu();

        Object v = tieneValor ? ((TreeNodeValue) nodo.getUserObject()).value() : null;
        boolean esContenedor = (v instanceof com.dynamicparser.model.DynamicObject
                || v instanceof java.util.Map || v instanceof java.util.List) && nodo != raizOriginal;
        var itemExtraer = new JMenuItem("Analizar este nodo en otra pestaña");
        itemExtraer.setEnabled(esContenedor && alExtraer != null);
        itemExtraer.setToolTipText("Abre este nodo en su propia pestaña: su texto, su árbol y su JSON");
        itemExtraer.addActionListener(ev -> extraerRama(nodo));
        menu.add(itemExtraer);
        menu.addSeparator();

        // Ubicar en una serialización existente que contenga este nodo
        JsonViewerPanel panelDestino = panelQueContiene(nodo);
        var itemUbicar = new JMenuItem(panelDestino != null
                ? "Ubicar en el JSON"
                : "Ubicar en el JSON  (no hay serialización que lo contenga)");
        itemUbicar.setEnabled(panelDestino != null);
        itemUbicar.addActionListener(ev -> ubicarEnJson(panelDestino, nodo));
        menu.add(itemUbicar);

        var itemTexto = new JMenuItem("Ubicar en el texto");
        itemTexto.setEnabled(tieneValor && textoInstanciado != null);
        itemTexto.addActionListener(ev -> ubicarEnFuente(nodo, List.of()));
        menu.add(itemTexto);


        menu.show(arbolResultado, e.getX(), e.getY());
    }


    private void serializarNodo(DefaultMutableTreeNode nodo) {
        var tnv = (TreeNodeValue) nodo.getUserObject();
        try {
            String json = JsonSerializer.toPrettyJson(tnv.value());
            var panelJson = new JsonViewerPanel[1];
            panelJson[0] = new JsonViewerPanel(json,
                    ruta -> {
                        var origen = (DefaultMutableTreeNode) panelJson[0].getClientProperty(ORIGEN);
                        ubicarEnArbol(origen, ruta);
                        ubicarEnFuente(origen, ruta);   // sólo selecciona: no dispara otra búsqueda
                    });
            panelJson[0].setPropuestaArchivo(this::propuestaJson);
            panelJson[0].putClientProperty(ORIGEN, nodo);
            panelJson[0].putClientProperty(ID_ORIGEN, idDe(nodo));
            panelJson[0].setFormateadorTooltip(ruta -> {
                var origen = (DefaultMutableTreeNode) panelJson[0].getClientProperty(ORIGEN);
                Object raizValor = ((TreeNodeValue) origen.getUserObject()).value();
                return com.dynamicparser.ui.fold.JsonPath.format(ruta,
                        i -> marcaContenedor(valorEn(raizValor, ruta.subList(0, i + 1))));
            });
            mostrarJson(panelJson[0]);
            lblEstado.setText("JSON generado: " + tnv.label());
        } catch (Exception ex) {
            mostrarError("Error al serializar a JSON:\n" + ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Árbol → JSON
    // ─────────────────────────────────────────────────────────────────────────

    /** Clave de client-property con el nodo del árbol que originó cada pestaña. */
    private static final String ORIGEN = "json.origen";

    /**
     * Pestaña JSON cuyo nodo de origen contiene al nodo dado.
     * Prioriza la pestaña seleccionada; si no, la de origen más cercano (más profundo).
     */
    private JsonViewerPanel panelQueContiene(DefaultMutableTreeNode nodo) {
        if (jsonPanel == null) return null;
        var origen = (DefaultMutableTreeNode) jsonPanel.getClientProperty(ORIGEN);
        return origen != null && (origen == nodo || origen.isNodeDescendant(nodo)) ? jsonPanel : null;
    }

    private void ubicarEnJson(JsonViewerPanel panel, DefaultMutableTreeNode nodo) {
        var origen = (DefaultMutableTreeNode) panel.getClientProperty(ORIGEN);
        List<Object> ruta = rutaJson(origen, nodo);
        if (panel.ubicarRuta(ruta)) {
            lblEstado.setText("Ubicado en el JSON: " + (ruta.isEmpty() ? "(raíz)" : com.dynamicparser.ui.fold.JsonPath.format(ruta)));
        } else {
            lblEstado.setText("⚠ La ruta no existe en el JSON (¿fue editado?).");
        }
    }

    /**
     * Ruta JSON desde el nodo origen (el serializado) hasta el nodo destino.
     * Los nodos-etiqueta ("▸ key", "[i]", "📄 Entrada") comparten valor con su
     * hijo y no aportan segmento; cada salto contenedor → hijo aporta clave o índice.
     */
    private static List<Object> rutaJson(DefaultMutableTreeNode origen, DefaultMutableTreeNode destino) {
        var ruta = new ArrayList<Object>();
        var n = destino;
        while (n != origen && n.getParent() != null) {
            var p = (DefaultMutableTreeNode) n.getParent();
            if (p.getUserObject() instanceof TreeNodeValue pv
                    && n.getUserObject() instanceof TreeNodeValue nv
                    && pv.value() != nv.value()) {
                Object seg = segmento(pv.value(), p.getIndex(n));
                if (seg != null) ruta.add(0, seg);
            }
            n = p;
        }
        return ruta;
    }

    /** Valor alcanzado al recorrer la ruta desde v (null si no existe). */
    private static Object valorEn(Object v, List<Object> ruta) {
        for (Object seg : ruta) {
            if (v instanceof DynamicObject obj) v = obj.properties();
            if (v instanceof Map<?, ?> map && seg instanceof String k) {
                v = map.get(k);
            } else if (v instanceof List<?> list && seg instanceof Integer i && i < list.size()) {
                v = list.get(i);
            } else {
                return null;
            }
        }
        return v;
    }

    /** Marca para la ruta del tooltip: "<,>" Map de Java, "{}" objeto JSON, nada en otro caso. */
    private static String marcaContenedor(Object v) {
        if (v instanceof com.dynamicparser.model.JsonObjectMap) return "{}";
        if (v instanceof Map<?, ?>) return "<,>";
        return null;
    }

    /** Clave (objeto/mapa) o índice (lista) del hijo en la posición idx. */
    private static Object segmento(Object valor, int idx) {
        if (valor instanceof DynamicObject obj) valor = obj.properties();
        if (valor instanceof Map<?, ?> map) {
            int i = 0;
            for (Object k : map.keySet()) {
                if (i++ == idx) return String.valueOf(k);
            }
            return null;
        }
        if (valor instanceof List<?>) return idx;
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON → árbol
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Colapsa todo el árbol y expande sólo la ruta hasta el nodo que
     * corresponde a la ruta JSON, partiendo del nodo que se serializó.
     */
    private void ubicarEnArbol(DefaultMutableTreeNode origen, List<Object> ruta) {
        DefaultMutableTreeNode nodo = contenedor(origen);
        DefaultMutableTreeNode destino = nodo;
        for (int s = 0; s < ruta.size(); s++) {
            Object valor = ((TreeNodeValue) nodo.getUserObject()).value();
            int idx = indiceHijo(valor, ruta.get(s));
            if (idx < 0 || idx >= nodo.getChildCount()) {
                lblEstado.setText("⚠ No se encontró en el árbol: " + com.dynamicparser.ui.fold.JsonPath.format(ruta));
                return;
            }
            destino = (DefaultMutableTreeNode) nodo.getChildAt(idx);
            if (s < ruta.size() - 1) nodo = contenedor(destino);
        }

        // Si el destino quedó fuera de la raíz actual (raíz re-establecida), restaurar
        var raizActual = (DefaultMutableTreeNode) arbolResultado.getModel().getRoot();
        if (destino != raizActual && !raizActual.isNodeDescendant(destino)) {
            arbolResultado.setModel(new DefaultTreeModel(raizOriginal));
            raizActual = raizOriginal;
        }

        colapsarTodo();
        var path = new TreePath(((DefaultTreeModel) arbolResultado.getModel()).getPathToRoot(destino));
        arbolResultado.setSelectionPath(path);
        centrarEnArbol(path);
        lblEstado.setText("Ubicado: " + com.dynamicparser.ui.fold.JsonPath.format(ruta));
    }

    /**
     * Desciende por nodos-etiqueta ("📄 Entrada", "▸ key", "[i]") hasta el nodo
     * que enumera las propiedades/elementos del valor.
     */
    private static DefaultMutableTreeNode contenedor(DefaultMutableTreeNode nodo) {
        while (nodo.getChildCount() == 1) {
            var hijo = (DefaultMutableTreeNode) nodo.getChildAt(0);
            if (!(hijo.getUserObject() instanceof TreeNodeValue h)
                    || !(nodo.getUserObject() instanceof TreeNodeValue p)
                    || h.value() != p.value()) break;
            nodo = hijo;
        }
        return nodo;
    }

    /** Posición del hijo para un segmento (clave o índice) según el valor del contenedor. */
    private static int indiceHijo(Object valor, Object segmento) {
        if (valor instanceof DynamicObject obj) valor = obj.properties();
        if (valor instanceof Map<?, ?> map && segmento instanceof String clave) {
            int i = 0;
            for (Object k : map.keySet()) {
                if (String.valueOf(k).equals(clave)) return i;
                i++;
            }
            return -1;
        }
        if (valor instanceof List<?> && segmento instanceof Integer i) return i;
        return -1;
    }

    /** Hace visible la ruta y deja su fila centrada verticalmente en el árbol. */
    private void centrarEnArbol(TreePath path) {
        arbolResultado.scrollPathToVisible(path);
        var r = arbolResultado.getPathBounds(path);
        if (r == null) return;
        var vis = arbolResultado.getVisibleRect();
        int y = Math.max(0, r.y - (vis.height - r.height) / 2);
        arbolResultado.scrollRectToVisible(new Rectangle(vis.x, y, vis.width, vis.height));
    }

    private void colapsarTodo() {
        for (int i = arbolResultado.getRowCount() - 1; i >= 1; i--) {
            arbolResultado.collapseRow(i);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Búsqueda en el árbol
    // ─────────────────────────────────────────────────────────────────────────

    /** Busca en las etiquetas de todos los nodos (incluso no expandidos). */
    private String buscarEnArbol(String texto, boolean adelante, boolean desdeInicio, boolean matchCase) {
        if (texto.isEmpty()) return "";
        var raiz = (DefaultMutableTreeNode) arbolResultado.getModel().getRoot();
        String q = matchCase ? texto : texto.toLowerCase();

        var hits = new ArrayList<DefaultMutableTreeNode>();
        for (var en = raiz.preorderEnumeration(); en.hasMoreElements(); ) {
            var n = (DefaultMutableTreeNode) en.nextElement();
            String label = String.valueOf(n.getUserObject());
            if ((matchCase ? label : label.toLowerCase()).contains(q)) hits.add(n);
        }
        if (hits.isEmpty()) return "0 resultados";

        int actual = -1;
        var sel = arbolResultado.getSelectionPath();
        if (sel != null && !desdeInicio) actual = hits.indexOf(sel.getLastPathComponent());
        int idx;
        if (desdeInicio) {
            idx = 0;
        } else if (actual >= 0) {
            idx = Math.floorMod(actual + (adelante ? 1 : -1), hits.size());
        } else {
            idx = adelante ? 0 : hits.size() - 1;
        }

        // getPath() llega hasta la raíz real; recortar a la raíz actual del modelo
        var completo = hits.get(idx).getPath();
        int desde = java.util.Arrays.asList(completo).indexOf(raiz);
        var path = new TreePath(java.util.Arrays.copyOfRange(completo, desde, completo.length));

        arbolResultado.setSelectionPath(path);
        centrarEnArbol(path);
        return (idx + 1) + " / " + hits.size();
    }

    /** Pone el panel como el JSON de este trío (reemplaza al anterior). */
    private void mostrarJson(JsonViewerPanel panel) {
        if (jsonPanel != null) zonaJson.remove(jsonPanel);
        jsonPanel = panel;
        zonaJson.add(panel, "json");
        ((CardLayout) zonaJson.getLayout()).show(zonaJson, "json");
        zonaJson.revalidate();
    }

    /** Quita el JSON (vuelve a la barra deshabilitada). */
    private void quitarJson() {
        if (jsonPanel != null) zonaJson.remove(jsonPanel);
        jsonPanel = null;
        ((CardLayout) zonaJson.getLayout()).show(zonaJson, "vacio");
    }

    /**
     * Objeto que serializa el JSON. Nunca se inventa un arreglo que agrupe objetos
     * distintos del log: si hay uno solo, ese; si hay varios, el objeto (de primer
     * nivel) que contiene la selección del árbol; si no hay selección, ninguno.
     */
    private DefaultMutableTreeNode origenJson() {
        int n = raizOriginal == null ? 0 : raizOriginal.getChildCount();
        if (n == 0) return null;
        if (n == 1) return (DefaultMutableTreeNode) raizOriginal.getChildAt(0);
        var sel = arbolResultado.getSelectionPath();
        if (sel == null) return null;
        for (Object o : sel.getPath()) {
            if (o instanceof DefaultMutableTreeNode d && d.getParent() == raizOriginal) return d;
        }
        return null;
    }

    /** Crea el JSON si no existe, o lo rehace si cambió su origen (otra entrada seleccionada, 1 ↔ varias…). */
    private void asegurarJson() {
        var origen = origenJson();
        if (jsonPanel != null && jsonPanel.isModificado()) return;   // no descartar ediciones a mano
        if (origen == null) {
            quitarJson();
            placeholderJson.deshabilitar(raizOriginal.getChildCount() > 1
                    ? "El texto tiene " + raizOriginal.getChildCount() + " objetos: seleccione uno en el árbol"
                    : "Aún no hay serialización: cargue o pegue un log");
            return;
        }
        if (jsonPanel == null || !java.util.Objects.equals(jsonPanel.getClientProperty(ID_ORIGEN), idDe(origen))) {
            serializarNodo(origen);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────────────────

    private void construirLayout() {
        var contenido = new JPanel(new BorderLayout(8, 8));
        contenido.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setLayout(new BorderLayout());
        add(contenido, BorderLayout.CENTER);

        // JSplitPane vertical: fuente de datos (arriba) | resultado (abajo)
        var splitVertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, panelFuente(), panelResultado());
        splitVertical.setResizeWeight(0.0);
        splitVertical.setContinuousLayout(true);
        splitVertical.setOneTouchExpandable(true);
        contenido.add(splitVertical, BorderLayout.CENTER);
        contenido.add(panelEstado(), BorderLayout.SOUTH);
    }

    /** Panel superior: selección de fuente + botón instanciar. */
    private JPanel panelFuente() {
        var panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Fuente de datos"),
                BorderFactory.createEmptyBorder(2, 6, 4, 6)));

        // Barra: [📂 Abrir archivo…] archivo | [›] 🔍 [buscar ▾] Aa ab .* n de m ↑ ↓
        var barra = new JToolBar();
        barra.setFloatable(false);
        barra.setRollover(true);
        barra.add(lblSync);
        barra.addSeparator();
        barra.add(btnAbrir);
        barra.add(lblArchivo);
        barra.addSeparator();
        barra.add(buscarFuente.getPanel());
        barra.add(Box.createHorizontalGlue());
        barra.add(tglAjustar);                       // "Wrap Lines" a la derecha, como en Postman


        var scroll = new RTextScrollPane(textArea);
        scroll.setLineNumbersEnabled(true);
        scroll.setFoldIndicatorEnabled(false);
        scroll.setMinimumSize(new Dimension(100, 60));

        panel.add(barra, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    /** Panel central: árbol de resultado. */
    private JPanel panelResultado() {
        var panelArbol = new JPanel(new BorderLayout());
        panelArbol.setBorder(BorderFactory.createTitledBorder("Resultado — Objeto instanciado dinámicamente"));
        panelArbol.add(new BarraBusqueda("Buscar en el árbol", this::buscarEnArbol), BorderLayout.NORTH);
        panelArbol.add(new JScrollPane(arbolResultado), BorderLayout.CENTER);

        var panelJson = new JPanel(new BorderLayout());
        panelJson.setBorder(BorderFactory.createTitledBorder("JSON — serialización de este árbol"));
        placeholderJson = new JsonViewerPanel("", null);
        placeholderJson.deshabilitar("Aún no hay serialización: cargue o pegue un log");
        zonaJson.add(placeholderJson, "vacio");
        panelJson.add(zonaJson, BorderLayout.CENTER);

        panelArbol.setPreferredSize(new Dimension(460, 300));   // ancho inicial razonable para el árbol
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, panelArbol, panelJson);
        split.setResizeWeight(0.4);
        split.setContinuousLayout(true);
        // El panel JSON no debe imponer su ancho mínimo (la barra se recorta);
        // al mostrarse por primera vez, el árbol ocupa ~38 % del ancho.
        panelJson.setMinimumSize(new Dimension(200, 100));
        panelArbol.setMinimumSize(new Dimension(150, 100));
        split.addHierarchyListener(new java.awt.event.HierarchyListener() {
            @Override public void hierarchyChanged(java.awt.event.HierarchyEvent e) {
                if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && split.isShowing()) {
                    split.removeHierarchyListener(this);
                    SwingUtilities.invokeLater(() -> split.setDividerLocation(0.38));
                }
            }
        });

        var panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    /** Barra de estado inferior. */
    private JPanel panelEstado() {
        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 0, 4));
        panel.add(lblEstado, BorderLayout.WEST);
        return panel;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Acciones
    // ─────────────────────────────────────────────────────────────────────────

    private static final String PREF_ULTIMO_ARCHIVO = "ultimoArchivoLog";

    private void elegirArchivo() {
        // Recordar la carpeta (y el archivo) de la última selección entre ejecuciones
        var prefs = Preferences.userNodeForPackage(TrioPanel.class);
        String ultimo = prefs.get(PREF_ULTIMO_ARCHIVO, null);
        var chooser = new JFileChooser();
        if (ultimo != null) {
            var f = new java.io.File(ultimo);
            if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                chooser.setCurrentDirectory(f.getParentFile());
                if (f.isFile()) chooser.setSelectedFile(f);
            }
        }
        chooser.setDialogTitle("Seleccionar archivo Log");
        chooser.setFileFilter(new FileNameExtensionFilter("Archivos Log (*.log)", "log"));
        chooser.setAcceptAllFileFilterUsed(true);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            var archivo = chooser.getSelectedFile();
            prefs.put(PREF_ULTIMO_ARCHIVO, archivo.getAbsolutePath());
            cargarArchivo(archivo.toPath());
        }
    }

    /** Carga el archivo en el editor (UTF-8; si no es válido, ISO-8859-1). */
    private void cargarArchivo(Path ruta) {
        String contenido;
        try {
            try {
                contenido = Files.readString(ruta, StandardCharsets.UTF_8);
            } catch (MalformedInputException ex) {
                contenido = Files.readString(ruta, StandardCharsets.ISO_8859_1);
            }
        } catch (IOException ex) {
            mostrarError("Error al leer el archivo:\n" + ex.getMessage());
            return;
        }
        if (tglAjustar.isSelected()) {          // nunca cargar con ajuste activo
            tglAjustar.setSelected(false);
            textArea.setLineWrap(false);
        }
        quitarJson();                          // nuevo documento: la serialización anterior no aplica
        autoSerializar = true;
        textArea.setText(contenido);
        archivoCargado = ruta;
        textoCargado = textArea.getText();     // normalizado por el editor (saltos de línea)
        textArea.setCaretPosition(0);
        textArea.discardAllEdits();
        lblArchivo.setText(ruta.getFileName().toString());
        alCambiarTitulo.accept(ruta.getFileName().toString());
        lblArchivo.setToolTipText(ruta.toAbsolutePath().toString());
        // Tamaño fijo (con margen) para que la caja de búsqueda no lo recorte
        lblArchivo.setPreferredSize(null);
        var d = lblArchivo.getPreferredSize();
        d = new Dimension(d.width + 24, d.height);
        lblArchivo.setPreferredSize(d);
        lblArchivo.setMinimumSize(d);
        lblArchivo.setMaximumSize(d);
        lblEstado.setText("Archivo cargado: " + ruta.toAbsolutePath()
                + "  (" + textArea.getLineCount() + " líneas)");
        autoInstanciar.stop();
        instanciar(null);     // al abrir un archivo se instancia de inmediato
    }

    /** Mismo aspecto para los botones principales de la barra (Instanciar, Abrir archivo…). */
    private static void estiloBotonPrincipal(JButton b) {
        b.setFont(UIManager.getFont("Button.font").deriveFont(Font.BOLD));
        b.setIconTextGap(6);
        b.setMargin(new Insets(3, 8, 3, 10));
        b.setFocusable(false);
    }

    /** Activa/desactiva el ajuste de línea, salvo que haya líneas demasiado largas. */
    private void cambiarAjuste() {
        if (tglAjustar.isSelected()) {
            int max = 0;
            for (int i = 0; i < textArea.getLineCount(); i++) {
                try {
                    max = Math.max(max, textArea.getLineEndOffset(i) - textArea.getLineStartOffset(i));
                } catch (javax.swing.text.BadLocationException ignored) { }
            }
            if (max > MAX_LINEA_AJUSTE) {
                tglAjustar.setSelected(false);
                JOptionPane.showMessageDialog(this,
                        "El contenido tiene una línea de " + max + " caracteres.\n"
                        + "Ajustar líneas tan largas congelaría la aplicación;\n"
                        + "use la barra de desplazamiento horizontal.",
                        "Ajustar líneas", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        }
        textArea.setLineWrap(tglAjustar.isSelected());
    }

    /** Al cambiar el texto: marcar como pendiente y reprogramar la sincronización. */
    private void actualizarBtnInstanciar() {
        if (textArea.getDocument().getLength() == 0) {
            autoInstanciar.stop();
            sync(" ", null);
            return;
        }
        if (!textArea.getText().equals(textoInstanciado)) {
            sync("⏳ Actualizando…", "El árbol y el JSON se actualizarán al dejar de escribir (Ctrl+Enter: ya)");
            autoInstanciar.restart();
        }
    }

    private void instanciarSiCambio() {
        if (textArea.getDocument().getLength() > 0 && !textArea.getText().equals(textoInstanciado)) {
            instanciar(null);
        }
    }

    private void sync(String texto, String tip) {
        lblSync.setText(texto);
        lblSync.setToolTipText(tip);
        lblSync.setForeground(texto.startsWith("⚠") ? new Color(190, 60, 30)
                : texto.startsWith("✔") ? new Color(30, 130, 60) : Color.DARK_GRAY);
    }

    private void doInstanciar() {
        instanciar(null);
    }

    /**
     * Parsea el contenido del editor en segundo plano y construye el árbol.
     * @param despues acción a ejecutar (en la EDT) si la instanciación tuvo éxito, o null
     */
    /** Número de línea (1-based) de un offset del texto. */
    private static int lineaDe(String texto, int offset) {
        int n = 1;
        for (int i = 0, fin = Math.min(offset, texto.length()); i < fin; i++) if (texto.charAt(i) == '\n') n++;
        return n;
    }

    private void instanciar(Runnable despues) {
        String contenido = textArea.getText();
        if (contenido.trim().isEmpty()) return;

        sync("⏳ Actualizando…", null);

        // Parsear en hilo worker para no bloquear la EDT
        SwingWorker<List<ParsedEntry>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<ParsedEntry> doInBackground() {
                return LogExtractor.extractAll(contenido);
            }

            @Override
            protected void done() {
                if (!contenido.equals(textArea.getText())) return;   // el texto siguió cambiando
                try {
                    List<ParsedEntry> entradas = get();
                    boolean todasConError = entradas.stream()
                            .allMatch(en -> en.parsed() instanceof String s && s.startsWith("⚠"));
                    if (entradas.isEmpty() || todasConError) {
                        // Mantener la última versión válida
                        textoInstanciado = null;
                        sync("⚠ Sin objetos válidos", entradas.isEmpty()
                                ? "No se encontraron objetos parseables; se muestra la última versión válida."
                                : "El texto no se pudo parsear; se muestra la última versión válida.");
                        return;
                    }

                    // La app serializa UN objeto: varios objetos independientes es un error
                    // (agruparlos en un arreglo o bajo una raíz común sería inventar estructura).
                    var validas = entradas.stream()
                            .filter(en -> !(en.parsed() instanceof String s && s.startsWith("⚠"))).toList();
                    if (validas.size() > 1) {
                        String lineas = validas.stream()
                                .map(en -> en.baseOffset() < 0 ? "?" : String.valueOf(lineaDe(contenido, en.baseOffset())))
                                .distinct().limit(10)
                                .collect(java.util.stream.Collectors.joining(", "));
                        String msg = "El texto contiene " + validas.size() + " objetos independientes (líneas "
                                + lineas + (validas.size() > 10 ? ", …" : "")
                                + "). Se serializa un solo objeto: deje sólo uno en el texto.";
                        raizOriginal = null;
                        entradasActuales = List.of();
                        textoInstanciado = null;
                        arbolResultado.setModel(new DefaultTreeModel(
                                new DefaultMutableTreeNode("⚠ " + validas.size() + " objetos independientes")));
                        quitarJson();
                        placeholderJson.deshabilitar("⚠ " + validas.size() + " objetos: se serializa uno solo");
                        sync("⚠ Varios objetos", msg);
                        lblEstado.setText("⚠  " + msg);
                        return;
                    }

                    var estado = capturarEstadoArbol();
                    var raiz = ObjectTreeBuilder.buildRoot(entradas);
                    raizOriginal = raiz;
                    entradasActuales = entradas;
                    textoInstanciado = contenido;
                    arbolResultado.setModel(new DefaultTreeModel(raiz));
                    if (estado == null) expandirPrimerosNiveles(3);
                    else restaurarEstadoArbol(estado);
                    refrescarPestanasJson();
                    asegurarJson();
                    boolean intacto = archivoCargado != null && contenido.equals(textoCargado);
                    if (intacto) alCambiarTitulo.accept(archivoCargado.getFileName().toString());
                    else if (!esRama) alCambiarTitulo.accept("Texto pegado");

                    String dialectos = entradas.stream().map(ParsedEntry::dialecto)
                            .filter(java.util.Objects::nonNull).distinct()
                            .collect(java.util.stream.Collectors.joining(", "));
                    String detalle = entradas.size() + " objeto(s) instanciado(s) dinámicamente"
                            + (dialectos.isEmpty() ? "" : "  ·  dialecto: " + dialectos);
                    sync("✔ Sincronizado", detalle);
                    lblEstado.setText("✔  " + detalle + ".");
                } catch (Exception ex) {
                    Throwable causa = ex.getCause() != null ? ex.getCause() : ex;
                    causa.printStackTrace();
                    textoInstanciado = null;
                    sync("⚠ Error al parsear", String.valueOf(causa));
                }
            }
        };
        worker.execute();
        if (despues != null) {
            worker.addPropertyChangeListener(ev -> {
                if ("state".equals(ev.getPropertyName()) && ev.getNewValue() == SwingWorker.StateValue.DONE) {
                    // done() ya corrió en la EDT; ejecutar después de él
                    SwingUtilities.invokeLater(() -> {
                        if (contenido.equals(textoInstanciado)) despues.run();
                    });
                }
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sincronización: identidad estable de nodos, estado del árbol, pestañas
    // ─────────────────────────────────────────────────────────────────────────

    /** Identidad (entrada + ruta) de un nodo del árbol actual; null para la raíz "Resultado". */
    private IdNodo idDe(DefaultMutableTreeNode nodo) {
        if (raizOriginal == null || nodo == raizOriginal) return null;
        var entrada = nodo;
        while (entrada.getParent() != null && entrada.getParent() != raizOriginal) {
            entrada = (DefaultMutableTreeNode) entrada.getParent();
        }
        if (entrada.getParent() != raizOriginal) return null;
        return new IdNodo(raizOriginal.getIndex(entrada), rutaJson(entrada, nodo));
    }

    /** Nodo del árbol actual con esa identidad, o null si ya no existe. */
    private DefaultMutableTreeNode nodoDe(IdNodo id) {
        if (id == null) return raizOriginal;
        if (id.entrada() < 0 || id.entrada() >= raizOriginal.getChildCount()) return null;
        return buscarNodo((DefaultMutableTreeNode) raizOriginal.getChildAt(id.entrada()), id.ruta());
    }

    /** Desciende desde el nodo de una entrada siguiendo la ruta JSON; null si no existe. */
    private static DefaultMutableTreeNode buscarNodo(DefaultMutableTreeNode origen, List<Object> ruta) {
        DefaultMutableTreeNode nodo = contenedor(origen);
        DefaultMutableTreeNode destino = nodo;
        for (int s = 0; s < ruta.size(); s++) {
            Object valor = ((TreeNodeValue) nodo.getUserObject()).value();
            int idx = indiceHijo(valor, ruta.get(s));
            if (idx < 0 || idx >= nodo.getChildCount()) return null;
            destino = (DefaultMutableTreeNode) nodo.getChildAt(idx);
            if (s < ruta.size() - 1) nodo = contenedor(destino);
        }
        return destino;
    }

    /** Estado visual del árbol, expresado con identidades estables. */
    private record EstadoArbol(boolean hayRaizCambiada, IdNodo raizVista,
                               List<IdNodo> expandidos, IdNodo seleccion) { }

    private EstadoArbol capturarEstadoArbol() {
        if (raizOriginal == null) return null;
        var raizVista = (DefaultMutableTreeNode) arbolResultado.getModel().getRoot();
        var expandidos = new ArrayList<IdNodo>();
        var en = arbolResultado.getExpandedDescendants(new TreePath(
                ((DefaultTreeModel) arbolResultado.getModel()).getPathToRoot(raizVista)));
        if (en != null) {
            while (en.hasMoreElements()) {
                var n = (DefaultMutableTreeNode) en.nextElement().getLastPathComponent();
                if (n != raizOriginal) expandidos.add(idDe(n));
            }
        }
        var sel = arbolResultado.getSelectionPath();
        IdNodo seleccion = sel == null ? null : idDe((DefaultMutableTreeNode) sel.getLastPathComponent());
        return new EstadoArbol(raizVista != raizOriginal, idDe(raizVista), expandidos, seleccion);
    }

    private void restaurarEstadoArbol(EstadoArbol e) {
        var vista = raizOriginal;
        if (e.hayRaizCambiada()) {
            var n = nodoDe(e.raizVista());
            if (n != null) {
                vista = n;
                arbolResultado.setModel(new DefaultTreeModel(vista));
            }
        }
        var modelo = (DefaultTreeModel) arbolResultado.getModel();
        arbolResultado.expandPath(new TreePath(modelo.getPathToRoot(vista)));
        for (IdNodo id : e.expandidos()) {
            var n = nodoDe(id);
            if (n != null && (n == vista || vista.isNodeDescendant(n))) {
                arbolResultado.expandPath(new TreePath(modelo.getPathToRoot(n)));
            }
        }
        if (e.seleccion() != null) {
            var n = nodoDe(e.seleccion());
            if (n != null && (n == vista || vista.isNodeDescendant(n))) {
                arbolResultado.setSelectionPath(new TreePath(modelo.getPathToRoot(n)));
            }
        }
    }

    /** Regenera cada pestaña JSON con el objeto equivalente del árbol nuevo. */
    private void refrescarPestanasJson() {
        for (var panel : jsonPanel == null ? List.<JsonViewerPanel>of() : List.of(jsonPanel)) {
            var id = (IdNodo) panel.getClientProperty(ID_ORIGEN);
            var nodo = nodoDe(id);
            if (nodo == null || !(nodo.getUserObject() instanceof TreeNodeValue tnv)) {
                panel.marcarDesactualizado("El objeto de esta pestaña ya no existe en el texto");
                continue;
            }
            panel.putClientProperty(ORIGEN, nodo);
            if (panel.isModificado()) {
                panel.marcarDesactualizado("Editado a mano: no se sobrescribe con el texto de origen");
                continue;
            }
            try {
                panel.actualizarJson(JsonSerializer.toPrettyJson(tnv.value()));
            } catch (Exception ex) {
                panel.marcarDesactualizado("No se pudo serializar: " + ex.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Árbol / JSON → texto de origen
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Selecciona y centra en el editor de la fuente la propiedad correspondiente a
     * (nodo origen + ruta relativa). Sólo mueve la selección: no dispara ninguna
     * búsqueda ni ubicación de vuelta, así que no hay ciclos.
     */
    private void ubicarEnFuente(DefaultMutableTreeNode origen, List<Object> rutaRelativa) {
        if (entradasActuales == null || !textArea.getText().equals(textoInstanciado)) {
            lblEstado.setText("⚠ El texto cambió: espere a que se sincronice para ubicar en él.");
            return;
        }
        IdNodo id = idDe(origen);
        if (id == null) {                      // origen = raíz: el primer segmento es la entrada
            if (rutaRelativa.isEmpty() || !(rutaRelativa.get(0) instanceof Integer i)) return;
            id = new IdNodo(i, List.of());
            rutaRelativa = rutaRelativa.subList(1, rutaRelativa.size());
        }
        var rutaCompleta = new ArrayList<Object>(id.ruta());
        rutaCompleta.addAll(rutaRelativa);

        var entrada = entradasActuales.get(id.entrada());
        int inicio, fin;
        if (rutaCompleta.isEmpty()) {
            inicio = entrada.baseOffset();
            fin = inicio + 1;
        } else {
            var span = entrada.spans().stream().filter(sp -> sp.path().equals(rutaCompleta)).findFirst().orElse(null);
            if (span == null) {
                lblEstado.setText("⚠ No se encontró en el texto: " + com.dynamicparser.ui.fold.JsonPath.format(rutaCompleta));
                return;
            }
            inicio = span.start();
            Object ultimo = rutaCompleta.get(rutaCompleta.size() - 1);
            // Seleccionar la clave (si es propiedad) o el elemento completo (si es índice)
            fin = ultimo instanceof String k && textArea.getText().startsWith(k, inicio)
                    ? inicio + k.length() : span.end();
        }
        textArea.select(inicio, fin);
        textArea.getCaret().setSelectionVisible(true);
        Centrar.en(textArea, inicio);
    }

    /**
     * Extrae la rama del nodo a una pestaña nueva: su texto es exactamente el
     * fragmento del texto de esta pestaña que corresponde al valor del nodo.
     */
    private void extraerRama(DefaultMutableTreeNode nodo) {
        if (entradasActuales == null || !textArea.getText().equals(textoInstanciado)) {
            lblEstado.setText("⚠ El texto cambió: espere a que se sincronice para extraer.");
            return;
        }
        IdNodo id = idDe(nodo);
        if (id == null) return;
        var entrada = entradasActuales.get(id.entrada());
        String texto = textArea.getText();
        int ini, fin;
        if (id.ruta().isEmpty()) {
            ini = entrada.baseOffset();
            fin = entrada.finOffset();
        } else {
            var span = entrada.spans().stream().filter(sp -> sp.path().equals(id.ruta())).findFirst().orElse(null);
            if (span == null) {
                lblEstado.setText("⚠ No se encontró la rama en el texto.");
                return;
            }
            ini = inicioDelValor(texto, span.start(), id.ruta().get(id.ruta().size() - 1));
            fin = span.end();
        }
        String fragmento = texto.substring(ini, fin).trim();
        String titulo = ((TreeNodeValue) nodo.getUserObject()).label().replaceFirst("\\s*\\(\\d+\\)$", "");
        String ruta = id.ruta().isEmpty() ? titulo : com.dynamicparser.ui.fold.JsonPath.format(id.ruta());
        alExtraer.extraer(titulo, ruta, fragmento);
        lblEstado.setText("Rama extraída: " + ruta);
    }

    /**
     * Inicio del VALOR dentro del tramo de una propiedad: salta la clave (con o sin
     * comillas) y el separador ('=' o ':'). Para elementos de lista, el tramo ya es el valor.
     */
    private static int inicioDelValor(String texto, int inicioTramo, Object ultimoSegmento) {
        if (!(ultimoSegmento instanceof String)) return inicioTramo;
        int p = inicioTramo;
        if (p < texto.length() && texto.charAt(p) == '"') {             // clave JSON entre comillas
            p++;
            while (p < texto.length() && texto.charAt(p) != '"') p += texto.charAt(p) == '\\' ? 2 : 1;
            p++;
        } else {
            while (p < texto.length() && texto.charAt(p) != '=' && texto.charAt(p) != ':') p++;
        }
        while (p < texto.length() && Character.isWhitespace(texto.charAt(p))) p++;
        if (p < texto.length() && (texto.charAt(p) == '=' || texto.charAt(p) == ':')) p++;
        while (p < texto.length() && Character.isWhitespace(texto.charAt(p))) p++;
        return p;
    }

    /** Carga un texto (p. ej. una rama extraída) y lo instancia de inmediato. */
    public void cargarTexto(String texto) {
        esRama = true;
        quitarJson();
        textArea.setText(texto);
        textArea.setCaretPosition(0);
        textArea.discardAllEdits();
        autoInstanciar.stop();
        instanciar(null);
    }

    /**
     * Archivo propuesto al guardar un JSON: el .log cargado con extensión .json,
     * salvo que el texto se haya pegado o editado desde entonces → nombre por defecto (config: json.nombrePorDefecto).
     */
    private java.io.File propuestaJson() {
        // Carpeta: SIEMPRE la del último .log abierto, desde cualquier sesión o pestaña
        // (es la misma preferencia global que usa "Abrir archivo…").
        java.io.File dir = carpetaUltimoLog();

        boolean intacto = archivoCargado != null && textArea.getText().equals(textoCargado);
        String nombre;
        if (intacto) {
            String n = archivoCargado.getFileName().toString();
            int punto = n.lastIndexOf('.');
            nombre = (punto > 0 ? n.substring(0, punto) : n) + com.dynamicparser.Config.texto("json.extension");
        } else {
            nombre = com.dynamicparser.Config.texto("json.nombrePorDefecto");
        }
        return new java.io.File(dir, nombre);
    }

    /** Carpeta del último .log abierto (preferencia global), o null si no hay o ya no existe. */
    private static java.io.File carpetaUltimoLog() {
        String ultimo = Preferences.userNodeForPackage(TrioPanel.class).get(PREF_ULTIMO_ARCHIVO, null);
        if (ultimo == null) return null;
        var carpeta = new java.io.File(ultimo).getParentFile();
        return carpeta != null && carpeta.isDirectory() ? carpeta : null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fuente → árbol
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ubica en el árbol la propiedad/elemento que está en el offset del editor
     * de la fuente. Si el contenido no está instanciado (o cambió), lo instancia antes.
     */
    private void ubicarDesdeFuente(int offset) {
        if (entradasActuales == null || !textArea.getText().equals(textoInstanciado)) {
            lblEstado.setText("Instanciando para ubicar…");
            instanciar(() -> ubicarDesdeFuente(offset));
            return;
        }
        for (int i = 0; i < entradasActuales.size(); i++) {
            var entrada = entradasActuales.get(i);
            if (!entrada.contiene(offset)) continue;
            List<Object> ruta = entrada.rutaEn(offset);
            var nodoEntrada = (DefaultMutableTreeNode) raizOriginal.getChildAt(i);
            ubicarEnArbol(nodoEntrada, ruta);
            arbolResultado.requestFocusInWindow();
            return;
        }
        lblEstado.setText("⚠ La posición no está dentro de un objeto instanciado.");
    }

    /** Contenido del editor de la fuente; null (con aviso) si está vacío. */
    private String obtenerContenido() {
        String texto = textArea.getText();
        if (texto.trim().isEmpty()) {
            mostrarError("No hay contenido: pegue el log o use «Abrir archivo…».");
            return null;
        }
        return texto;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidades de UI
    // ─────────────────────────────────────────────────────────────────────────

    /** Expande automáticamente los primeros N niveles del árbol. */
    private void expandirPrimerosNiveles(int maxNivel) {
        for (int i = 0; i < arbolResultado.getRowCount(); i++) {
            var path = arbolResultado.getPathForRow(i);
            if (path != null && path.getPathCount() <= maxNivel) {
                arbolResultado.expandRow(i);
            }
        }
    }

    private void mostrarError(String mensaje) {
        JOptionPane.showMessageDialog(this, mensaje, "Error", JOptionPane.ERROR_MESSAGE);
    }
}
