package com.dynamicparser.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Buscar / Reemplazar sobre un RSyntaxTextArea, con aspecto de IntelliJ y
 * historial persistente para lo buscado y para el reemplazo.
 *
 *   ┌───┬───────────────────────────────────────────┬───────────────────────────────┐
 *   │ › │ [🔍▾ buscar…                  Cc  W  .* ] ⋮ │ 3 de 12   ↑  ↓   → Árbol       │
 *   │   │ [🔍▾ reemplazar…                        ]   │ [Reemplazar] [Reemplazar todo] │ ← [›] o Ctrl+H
 *   └───┴───────────────────────────────────────────┴───────────────────────────────┘
 *
 *  • Las opciones (Cc = mayúsculas, W = palabra completa, .* = regex) van DENTRO de la caja.
 *  • La lupa ▾ abre el historial (también Alt+↓ en la caja).
 *  • Botones planos: se rellenan sólo al estar activos o con el mouse encima.
 *  • El reemplazo sólo está disponible cuando el editor es editable.
 *
 * Atajos en el editor: Ctrl+F buscar · Ctrl+H reemplazar · F3 / Shift+F3 siguiente/anterior.
 * En las cajas: Enter siguiente · Shift+Enter anterior · Esc volver al editor · Alt+↓ historial.
 */
public class BuscarReemplazar {

    private static final int MAX_HISTORIAL = com.dynamicparser.Config.entero("busqueda.maxHistorial");
    private static final Preferences PREFS = Preferences.userNodeForPackage(BuscarReemplazar.class);
    private static final String HIST_BUSCAR = "historial.buscar";
    private static final String HIST_REEMPLAZAR = "historial.reemplazar";
    private static final char SEP = '\u0001';

    private static final Color FONDO_ERROR   = new Color(255, 225, 225);
    private static final Color BORDE_CAJA    = new Color(190, 190, 190);
    private static final Color BORDE_FOCO    = new Color(60, 120, 220);
    private static final Color ACTIVO_FONDO  = new Color(205, 222, 250);
    private static final Color ACTIVO_BORDE  = new Color(110, 150, 220);
    private static final Color HOVER_FONDO   = new Color(228, 228, 228);

    /** Más coincidencias que esto no se resaltan todas (en líneas enormes sería lento). */
    private static final int MAX_MARCAR_TODO = com.dynamicparser.Config.entero("busqueda.maxMarcarTodo");
    /** En documentos con líneas de más de LINEA_LARGA caracteres, resaltar como máximo esto. */
    private static final int LINEA_LARGA = com.dynamicparser.Config.entero("busqueda.lineaLarga");
    private static final int MAX_MARCAR_LINEA_LARGA = com.dynamicparser.Config.entero("busqueda.maxMarcarLineaLarga");

    private static final String PREF_ANCHO = "ancho.cajas";
    private static final int ANCHO_MIN = com.dynamicparser.Config.entero("busqueda.anchoMin");
    private static final int ANCHO_MAX = com.dynamicparser.Config.entero("busqueda.anchoMax");
    private static final int ANCHO_DEF = com.dynamicparser.Config.entero("busqueda.anchoDefecto");

    private final RSyntaxTextArea editor;

    private final JTextField txtBuscar = new JTextField();
    private final JTextField txtReemplazar = new JTextField();
    private final Caja cajaBuscar;
    private final Caja cajaReemplazar;

    private final BotonPlano tglCase  = BotonPlano.toggle("Cc", "Distinguir mayúsculas/minúsculas");
    private final BotonPlano tglWord  = BotonPlano.toggle("W",  "Palabra completa");
    private final BotonPlano tglRegex = BotonPlano.toggle(".*", "Expresión regular");
    private final BotonPlano tglReemplazo = BotonPlano.toggle("›", "Mostrar/ocultar reemplazar (Ctrl+H)");
    private final JLabel lblEstado = new JLabel("Sin resultados");
    private final JButton btnReemplazar = new JButton("Reemplazar");
    private final JButton btnReemplazarTodo = new JButton("Reemplazar todo");

    private final JPanel filaBuscar = new JPanel();
    private final JPanel filaReemplazar = new JPanel();

    /** Componentes de tamaño fijo: se recalculan cuando el widget entra a la ventana (LAF ya aplicado). */
    private final List<JComponent> fijos = new ArrayList<>();

    /** Widget completo: [›] | filas buscar / reemplazar. */
    private final JPanel widget = new JPanel(new BorderLayout(2, 0)) {
        @Override public void addNotify() {
            super.addNotify();
            recalcularTamanos();
        }
    };

    /** Evita búsquedas al poner texto desde el historial. */
    private boolean silenciar;

    public BuscarReemplazar(RSyntaxTextArea editor) {
        this.editor = editor;
        cajaBuscar = new Caja(txtBuscar, HIST_BUSCAR, "Buscar", tglCase, tglWord, tglRegex);
        cajaReemplazar = new Caja(txtReemplazar, HIST_REEMPLAZAR,
                "Reemplazar con…  (con .* admite $1, $2…)");
        construir();
        registrarEventos();
        actualizarDisponibilidadReemplazo();
        filaReemplazar.setVisible(false);
    }

    /**
     * Agrega un botón de acción al final de la fila de buscar (p. ej. "→ Árbol").
     * La acción suele operar sobre la coincidencia seleccionada en el editor.
     */
    public JButton agregarAccion(String texto, String tip, Runnable accion) {
        var b = BotonPlano.accion(texto, tip, accion);
        fijo(b);
        int idx = filaBuscar.getComponentCount() - 1;   // antes del glue final
        filaBuscar.add(Box.createHorizontalStrut(6), idx);
        filaBuscar.add(b, idx + 1);
        return b;
    }

    /** El widget completo de buscar/reemplazar (para insertarlo en una barra o panel). */
    public JComponent getPanel() { return widget; }

    // ─────────────────────────────────────────────────────────────────────
    // UI
    // ─────────────────────────────────────────────────────────────────────

    private void construir() {
        lblEstado.setPreferredSize(new Dimension(95, lblEstado.getPreferredSize().height));
        lblEstado.setForeground(Color.DARK_GRAY);
        fijo(lblEstado);
        fijo(tglReemplazo);
        fijo(tglCase);
        fijo(tglWord);
        fijo(tglRegex);

        for (var b : List.of(btnReemplazar, btnReemplazarTodo)) {
            b.setMargin(new Insets(1, 8, 1, 8));
            b.setFocusable(false);
            fijo(b);
        }
        btnReemplazar.setToolTipText("Reemplazar la coincidencia seleccionada e ir a la siguiente (Enter en la caja)");
        btnReemplazarTodo.setToolTipText("Reemplazar todas las coincidencias (un solo Deshacer)");

        int ancho = PREFS.getInt(PREF_ANCHO, ANCHO_DEF);
        cajaBuscar.setAncho(ancho);
        cajaReemplazar.setAncho(ancho);
        var agarre = agarre();

        // Fila buscar
        filaBuscar.setLayout(new BoxLayout(filaBuscar, BoxLayout.X_AXIS));
        filaBuscar.setOpaque(false);
        filaBuscar.add(cajaBuscar);
        filaBuscar.add(agarre);
        filaBuscar.add(lblEstado);
        filaBuscar.add(fijo(BotonPlano.accion("↑", "Anterior (Shift+Enter / Shift+F3)", () -> navegar(false))));
        filaBuscar.add(fijo(BotonPlano.accion("↓", "Siguiente (Enter / F3)", () -> navegar(true))));
        filaBuscar.add(Box.createHorizontalGlue());

        // Fila reemplazar (debajo, alineada con la de buscar)
        filaReemplazar.setLayout(new BoxLayout(filaReemplazar, BoxLayout.X_AXIS));
        filaReemplazar.setOpaque(false);
        filaReemplazar.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
        filaReemplazar.add(cajaReemplazar);
        filaReemplazar.add(Box.createHorizontalStrut(agarre.getPreferredSize().width));
        filaReemplazar.add(btnReemplazar);
        filaReemplazar.add(Box.createHorizontalStrut(4));
        filaReemplazar.add(btnReemplazarTodo);
        filaReemplazar.add(Box.createHorizontalGlue());

        var filas = new JPanel();
        filas.setLayout(new BoxLayout(filas, BoxLayout.Y_AXIS));
        filas.setOpaque(false);
        filaBuscar.setAlignmentX(0f);
        filaReemplazar.setAlignmentX(0f);
        filas.add(filaBuscar);
        filas.add(filaReemplazar);

        // [›] ocupa la altura de ambas filas, como en IntelliJ / VS Code
        var colToggle = new JPanel(new BorderLayout());
        colToggle.setOpaque(false);
        colToggle.add(tglReemplazo, BorderLayout.CENTER);

        widget.setOpaque(false);
        widget.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 2));
        widget.add(colToggle, BorderLayout.WEST);
        widget.add(filas, BorderLayout.CENTER);
    }

    /** Recalcula los tamaños fijos con el Look&Feel/fuentes definitivos. */
    private void recalcularTamanos() {
        for (var c : fijos) {
            c.setPreferredSize(null);
            c.setMinimumSize(null);
            c.setMaximumSize(null);
            aplicarFijo(c);
        }
        var d = tglReemplazo.getPreferredSize();
        tglReemplazo.setMinimumSize(new Dimension(d.width, 0));
        tglReemplazo.setMaximumSize(new Dimension(d.width, Integer.MAX_VALUE));
        widget.revalidate();
    }

    private void registrarEventos() {
        // Búsqueda incremental al escribir
        txtBuscar.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { incremental(); }
            @Override public void removeUpdate(DocumentEvent e)  { incremental(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        for (var t : List.of(tglCase, tglWord, tglRegex)) t.addActionListener(e -> incremental());

        tglReemplazo.addActionListener(e -> mostrarReemplazo(tglReemplazo.isSelected()));
        btnReemplazar.addActionListener(e -> reemplazar());
        btnReemplazarTodo.addActionListener(e -> reemplazarTodo());

        // Teclas en las cajas
        teclas(txtBuscar, cajaBuscar, () -> navegar(true), () -> navegar(false));
        teclas(txtReemplazar, cajaReemplazar, this::reemplazar, () -> navegar(false));

        // Atajos en el editor
        atajo(editor, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "br.buscar", this::enfocarBuscar);
        atajo(editor, KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "br.reemplazar", () -> {
            if (editor.isEditable()) {
                mostrarReemplazo(true);
                txtReemplazar.requestFocusInWindow();
            } else {
                enfocarBuscar();
            }
        });
        atajo(editor, KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "br.sig", () -> navegar(true));
        atajo(editor, KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "br.ant", () -> navegar(false));

        // Habilitar/deshabilitar reemplazo según el modo de edición
        editor.addPropertyChangeListener("editable", e -> actualizarDisponibilidadReemplazo());
    }

    public void enfocarBuscar() {
        String sel = editor.getSelectedText();
        if (sel != null && !sel.isEmpty() && !sel.contains("\n")) {
            txtBuscar.setText(sel);
        }
        txtBuscar.requestFocusInWindow();
        txtBuscar.selectAll();
    }

    private void mostrarReemplazo(boolean mostrar) {
        mostrar &= editor.isEditable();
        tglReemplazo.setSelected(mostrar);
        tglReemplazo.setText(mostrar ? "⌄" : "›");
        filaReemplazar.setVisible(mostrar);
        widget.revalidate();
        widget.repaint();
    }

    private void actualizarDisponibilidadReemplazo() {
        boolean editable = editor.isEditable();
        tglReemplazo.setEnabled(editable);
        tglReemplazo.setToolTipText(editable ? "Mostrar/ocultar reemplazar (Ctrl+H)"
                                             : "Reemplazar: habilite la edición");
        if (!editable) mostrarReemplazo(false);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Búsqueda
    // ─────────────────────────────────────────────────────────────────────

    private String textoBuscar() {
        return txtBuscar.getText();
    }

    /** Patrón según las opciones; null si la regex es inválida. */
    private Pattern patron() {
        String q = textoBuscar();
        String p = tglRegex.isSelected() ? q : Pattern.quote(q);
        if (tglWord.isSelected()) p = "(?<![\\p{L}\\p{N}_])(?:" + p + ")(?![\\p{L}\\p{N}_])";
        int flags = Pattern.MULTILINE;
        if (!tglCase.isSelected()) flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            return Pattern.compile(p, flags);
        } catch (PatternSyntaxException ex) {
            return null;
        }
    }

    /** Coincidencias {inicio, fin} en todo el texto (incluye lo colapsado). */
    private List<int[]> coincidencias(Pattern p) {
        var out = new ArrayList<int[]>();
        Matcher m = p.matcher(editor.getText());
        while (m.find()) {
            if (m.end() == m.start()) {        // evitar bucles con regex vacías
                if (m.end() >= editor.getDocument().getLength()) break;
                continue;
            }
            out.add(new int[]{m.start(), m.end()});
        }
        return out;
    }

    /** Búsqueda incremental con espera: se ejecuta 250 ms después de la última tecla. */
    private final Timer esperaIncremental = new Timer(com.dynamicparser.Config.entero("busqueda.esperaMs"), e -> buscar(true, true));
    {
        esperaIncremental.setRepeats(false);
    }

    private void incremental() {
        if (silenciar) return;
        esperaIncremental.restart();
    }

    /**
     * ¿Conviene resaltar todas las coincidencias? Pintar cada resaltado en una
     * línea enorme recorre la línea completa, así que ahí se limita mucho más.
     */
    private boolean convieneMarcarTodo(int cantidad) {
        if (cantidad > MAX_MARCAR_TODO) return false;
        int maxLinea = 0;
        var root = editor.getDocument().getDefaultRootElement();
        for (int i = 0; i < root.getElementCount(); i++) {
            var el = root.getElement(i);
            maxLinea = Math.max(maxLinea, el.getEndOffset() - el.getStartOffset());
        }
        return maxLinea <= LINEA_LARGA || cantidad <= MAX_MARCAR_LINEA_LARGA;
    }

    private void navegar(boolean adelante) {
        guardarHistorial(HIST_BUSCAR, textoBuscar());
        buscar(adelante, false);
    }

    /**
     * @param desdeInicio búsqueda incremental: primera coincidencia desde el
     *                    inicio de la selección actual (no avanza).
     */
    private void buscar(boolean adelante, boolean desdeInicio) {
        String q = textoBuscar();
        marcarTodo(null);
        if (q.isEmpty()) {
            estado("Sin resultados", false);
            return;
        }
        Pattern p = patron();
        if (p == null) {
            estado("Regex inválida", true);
            return;
        }
        List<int[]> hits = coincidencias(p);
        if (hits.isEmpty()) {
            estado("Sin resultados", true);
            return;
        }
        if (convieneMarcarTodo(hits.size())) marcarTodo(q);

        int ref = editor.getSelectionStart();
        int idx;
        if (adelante) {
            idx = -1;
            for (int i = 0; i < hits.size(); i++) {
                if (desdeInicio ? hits.get(i)[0] >= ref : hits.get(i)[0] > ref) { idx = i; break; }
            }
            if (idx < 0) idx = 0;                       // vuelta al inicio
        } else {
            idx = -1;
            for (int i = hits.size() - 1; i >= 0; i--) {
                if (hits.get(i)[0] < ref) { idx = i; break; }
            }
            if (idx < 0) idx = hits.size() - 1;         // vuelta al final
        }
        seleccionar(hits.get(idx));
        estado((idx + 1) + " de " + hits.size(), false);
        txtBuscar.requestFocusInWindow();
    }

    private void seleccionar(int[] hit) {
        editor.getFoldManager().ensureOffsetNotInClosedFold(hit[0]);
        editor.select(hit[0], hit[1]);
        editor.getCaret().setSelectionVisible(true);   // visible aunque el foco esté en la caja
        Centrar.en(editor, hit[0]);                    // centrado vertical y horizontal
    }

    /** Resalta todas las coincidencias (null = limpiar). */
    private void marcarTodo(String q) {
        var ctx = new SearchContext(q == null ? "" : q, tglCase.isSelected());
        ctx.setRegularExpression(tglRegex.isSelected());
        ctx.setWholeWord(tglWord.isSelected());
        ctx.setMarkAll(q != null);
        try {
            SearchEngine.markAll(editor, ctx);
        } catch (RuntimeException ignored) { }
    }

    private void estado(String texto, boolean error) {
        lblEstado.setText(texto);
        cajaBuscar.setError(error && !textoBuscar().isEmpty());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Reemplazo
    // ─────────────────────────────────────────────────────────────────────

    private String textoReemplazo() {
        return txtReemplazar.getText();
    }

    /** Reemplaza la coincidencia seleccionada (si lo es) y avanza a la siguiente. */
    private void reemplazar() {
        if (!editor.isEditable()) return;
        Pattern p = patron();
        if (p == null || textoBuscar().isEmpty()) return;
        guardarHistorial(HIST_BUSCAR, textoBuscar());
        guardarHistorial(HIST_REEMPLAZAR, textoReemplazo());

        int s = editor.getSelectionStart(), e = editor.getSelectionEnd();
        boolean seleccionEsCoincidencia = coincidencias(p).stream().anyMatch(h -> h[0] == s && h[1] == e);
        if (seleccionEsCoincidencia) {
            String sel = editor.getSelectedText();
            String nuevo = tglRegex.isSelected()
                    ? p.matcher(sel).replaceFirst(textoReemplazo())
                    : textoReemplazo();
            editor.replaceSelection(nuevo);
            editor.select(s, s + nuevo.length());   // la siguiente búsqueda parte de aquí
        }
        buscar(true, false);
    }

    private void reemplazarTodo() {
        if (!editor.isEditable()) return;
        Pattern p = patron();
        if (p == null || textoBuscar().isEmpty()) return;
        guardarHistorial(HIST_BUSCAR, textoBuscar());
        guardarHistorial(HIST_REEMPLAZAR, textoReemplazo());

        int n = coincidencias(p).size();
        if (n == 0) {
            estado("Sin resultados", true);
            return;
        }
        String repl = tglRegex.isSelected() ? textoReemplazo() : Matcher.quoteReplacement(textoReemplazo());
        String nuevo;
        try {
            nuevo = p.matcher(editor.getText()).replaceAll(repl);
        } catch (RuntimeException ex) {          // p. ej. $3 inexistente
            estado("Reemplazo inválido", true);
            return;
        }
        int caret = Math.min(editor.getCaretPosition(), nuevo.length());
        editor.beginAtomicEdit();
        try {
            editor.getDocument().remove(0, editor.getDocument().getLength());
            editor.getDocument().insertString(0, nuevo, null);
        } catch (BadLocationException ignored) {
        } finally {
            editor.endAtomicEdit();
        }
        editor.setCaretPosition(caret);
        marcarTodo(null);
        estado(n + " reemplazada(s)", false);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Historial persistente
    // ─────────────────────────────────────────────────────────────────────

    private static List<String> cargarHistorial(String clave) {
        String raw = PREFS.get(clave, "");
        var out = new ArrayList<String>();
        if (!raw.isEmpty()) {
            for (String s : raw.split(String.valueOf(SEP))) if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** Pone el texto al principio del historial (sin duplicados) y lo persiste. */
    private static void guardarHistorial(String clave, String texto) {
        if (texto == null || texto.isEmpty()) return;
        var lista = cargarHistorial(clave);       // recargar: otras pestañas pudieron agregar
        lista.remove(texto);
        lista.add(0, texto);
        while (lista.size() > MAX_HISTORIAL) lista.remove(lista.size() - 1);
        PREFS.put(clave, String.join(String.valueOf(SEP), lista));
    }

    /** Muestra el historial bajo la caja; al elegir, pone el texto (y busca si es la de buscar). */
    private void mostrarHistorial(Caja caja) {
        var lista = cargarHistorial(caja.claveHistorial);
        var menu = new JPopupMenu();
        if (lista.isEmpty()) {
            var vacio = new JMenuItem("(sin historial)");
            vacio.setEnabled(false);
            menu.add(vacio);
        }
        for (String h : lista) {
            var item = new JMenuItem(h.length() > 80 ? h.substring(0, 80) + "…" : h);
            item.setToolTipText(h);
            item.addActionListener(e -> {
                caja.campo.setText(h);
                caja.campo.requestFocusInWindow();
                if (caja == cajaBuscar) buscar(true, true);
            });
            menu.add(item);
        }
        menu.show(caja, 0, caja.getHeight());
    }

    /**
     * Agarre "⋮" que se arrastra horizontalmente para cambiar el ancho de las
     * cajas de buscar y reemplazar (el ancho se recuerda entre ejecuciones).
     */
    private JComponent agarre() {
        var g = new JLabel("⋮");
        g.setForeground(Color.GRAY);
        g.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 5));
        g.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
        g.setToolTipText("Arrastre para cambiar el ancho de las cajas");
        var mouse = new java.awt.event.MouseAdapter() {
            int x0, ancho0;
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                x0 = e.getXOnScreen();
                ancho0 = cajaBuscar.getWidth();
            }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                int ancho = Math.max(ANCHO_MIN, Math.min(ANCHO_MAX, ancho0 + e.getXOnScreen() - x0));
                cajaBuscar.setAncho(ancho);
                cajaReemplazar.setAncho(ancho);
                widget.revalidate();
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                PREFS.putInt(PREF_ANCHO, cajaBuscar.getWidth());
            }
        };
        g.addMouseListener(mouse);
        g.addMouseMotionListener(mouse);
        return g;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Utilidades
    // ─────────────────────────────────────────────────────────────────────

    private void teclas(JTextField campo, Caja caja, Runnable enter, Runnable shiftEnter) {
        atajo(campo, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "br.enter", enter);
        atajo(campo, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "br.shiftEnter", shiftEnter);
        atajo(campo, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "br.esc", () -> {
            marcarTodo(null);
            editor.requestFocusInWindow();
        });
        atajo(campo, KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "br.sig", () -> navegar(true));
        atajo(campo, KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "br.ant", () -> navegar(false));
        atajo(campo, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "br.hist", () -> mostrarHistorial(caja));
    }

    private static void atajo(JComponent c, KeyStroke ks, String nombre, Runnable r) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(ks, nombre);
        c.getActionMap().put(nombre, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    /** Registra un componente de tamaño fijo (no lo encoge el BoxLayout de la barra). */
    private <T extends JComponent> T fijo(T c) {
        fijos.add(c);
        aplicarFijo(c);
        return c;
    }

    private static void aplicarFijo(JComponent c) {
        var d = c.getPreferredSize();
        d = new Dimension(d.width + 2, d.height);   // margen: el borde puede variar
        c.setPreferredSize(d);
        c.setMinimumSize(d);
        c.setMaximumSize(d);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Caja de texto compuesta estilo IntelliJ: [🔍▾ | texto | opciones]
    // ─────────────────────────────────────────────────────────────────────

    private final class Caja extends JPanel {
        final JTextField campo;
        final String claveHistorial;
        private boolean error;

        Caja(JTextField campo, String claveHistorial, String tip, JComponent... opciones) {
            super(new BorderLayout(2, 0));
            this.campo = campo;
            this.claveHistorial = claveHistorial;
            setBackground(UIManager.getColor("TextField.background"));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDE_CAJA),
                    BorderFactory.createEmptyBorder(1, 2, 1, 2)));

            var historial = BotonPlano.accion("", "Historial (Alt+↓)", () -> mostrarHistorial(this));
            historial.setIcon(new IconoLupaHistorial());
            historial.setMargin(new Insets(0, 2, 0, 2));

            campo.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
            campo.setToolTipText(tip + "  (Enter: siguiente · Shift+Enter: anterior · Alt+↓: historial · Esc)");
            campo.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { repintarBorde(); }
                @Override public void focusLost(FocusEvent e)   { repintarBorde(); }
            });

            add(historial, BorderLayout.WEST);
            add(campo, BorderLayout.CENTER);
            if (opciones.length > 0) {
                var ops = new JPanel(new FlowLayout(FlowLayout.RIGHT, 1, 0));
                ops.setOpaque(false);
                for (var o : opciones) ops.add(o);
                add(ops, BorderLayout.EAST);
            }
        }

        void setAncho(int ancho) {
            int h = Math.max(24, getPreferredSize().height);
            setPreferredSize(null);
            h = Math.max(24, super.getPreferredSize().height);
            var d = new Dimension(ancho, h);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
            revalidate();
        }

        void setError(boolean error) {
            this.error = error;
            var fondo = error ? FONDO_ERROR : UIManager.getColor("TextField.background");
            setBackground(fondo);
            campo.setBackground(fondo);
            repaint();
        }

        private void repintarBorde() {
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(campo.hasFocus() ? BORDE_FOCO : BORDE_CAJA),
                    BorderFactory.createEmptyBorder(1, 2, 1, 2)));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Botón plano (estilo IntelliJ): sin relieve; relleno sólo activo/hover
    // ─────────────────────────────────────────────────────────────────────

    static final class BotonPlano extends JToggleButton {
        private final boolean esToggle;

        private BotonPlano(String texto, String tip, boolean esToggle) {
            super(texto);
            this.esToggle = esToggle;
            setToolTipText(tip);
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setMargin(new Insets(1, 4, 1, 4));
            setFont(getFont().deriveFont(Font.BOLD));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        static BotonPlano toggle(String texto, String tip) {
            return new BotonPlano(texto, tip, true);
        }

        /** Botón plano de acción (no queda presionado). */
        static JButton accion(String texto, String tip, Runnable r) {
            var b = new JButton(texto) {
                @Override protected void paintComponent(Graphics g) {
                    pintarFondo(this, (Graphics2D) g, false, getModel().isRollover(), getModel().isPressed());
                    super.paintComponent(g);
                }
            };
            b.setToolTipText(tip);
            b.setFocusable(false);
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setRolloverEnabled(true);
            b.setMargin(new Insets(1, 5, 1, 5));
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.addActionListener(e -> r.run());
            return b;
        }

        @Override
        protected void paintComponent(Graphics g) {
            pintarFondo(this, (Graphics2D) g, esToggle && isSelected(), getModel().isRollover(), getModel().isPressed());
            super.paintComponent(g);
        }

        private static void pintarFondo(AbstractButton b, Graphics2D g, boolean activo, boolean hover, boolean presionado) {
            if (!b.isEnabled()) return;
            if (!activo && !hover && !presionado) return;
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = b.getWidth() - 1, h = b.getHeight() - 1;
            g2.setColor(activo ? ACTIVO_FONDO : HOVER_FONDO);
            g2.fillRoundRect(0, 0, w, h, 6, 6);
            if (activo) {
                g2.setColor(ACTIVO_BORDE);
                g2.drawRoundRect(0, 0, w, h, 6, 6);
            }
            g2.dispose();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Ícono lupa con ▾ (abre el historial), dibujado
    // ─────────────────────────────────────────────────────────────────────

    private static final class IconoLupaHistorial implements Icon {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            g2.setColor(new Color(90, 90, 90));
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(new Ellipse2D.Double(1.5, 2.5, 8, 8));
            g2.draw(new Line2D.Double(8.6, 9.6, 12, 13));
            // ▾
            var tri = new Polygon(new int[]{13, 19, 16}, new int[]{7, 7, 10}, 3);
            g2.fill(tri);
            g2.dispose();
        }
        @Override public int getIconWidth()  { return 20; }
        @Override public int getIconHeight() { return 16; }
    }
}
