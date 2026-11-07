package com.dynamicparser.ui.fold;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.folding.Fold;
import org.fife.ui.rsyntaxtextarea.folding.FoldManager;
import org.fife.ui.rsyntaxtextarea.folding.FoldParserManager;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Editor JSON (RSyntaxTextArea ⊂ JTextArea) con plegado estilo Postman.
 * Dibuja una cajita "⋯" tras la llave de cada rango colapsado; un click la expande.
 * Tooltip con la ruta JSON de la clave/valor bajo el mouse y menú contextual
 * "Ubicar en el árbol" / "Copiar ruta".
 */
public class JsonEditor extends RSyntaxTextArea {

    static {
        FoldParserManager.get().addFoldParserMapping(
                SyntaxConstants.SYNTAX_STYLE_JSON, new JsonFoldParser());
    }

    private static final Color BOX_BG     = new Color(232, 238, 248);
    private static final Color BOX_BORDER = new Color(160, 180, 215);
    private static final Color BOX_DOTS   = new Color(70, 100, 160);

    /** Cajitas "⋯" pintadas en el último repaint → fold que expanden. */
    private final Map<Rectangle, Fold> cajas = new LinkedHashMap<>();

    /** Acción "Ubicar en el árbol" (la provee quien crea el editor). */
    private Consumer<List<Object>> ubicador;
    /** Formato de la ruta para el tooltip (permite marcar las claves Map con "<,>"). */
    private java.util.function.Function<List<Object>, String> formateadorTooltip = JsonPath::format;
    /** Punto del último click (para el menú contextual). */
    private Point ultimoClick;

    public JsonEditor(String text) {
        super(text);
        ToolTipManager.sharedInstance().registerComponent(this);
        setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        setCodeFoldingEnabled(true);

        var mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                ultimoClick = e.getPoint();
                Fold f = cajaEn(e.getPoint());
                if (f != null) {
                    f.setCollapsed(false);
                    e.consume();
                }
            }
            @Override public void mouseMoved(MouseEvent e) {
                setCursor(cajaEn(e.getPoint()) != null
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public void setFormateadorTooltip(java.util.function.Function<List<Object>, String> f) {
        this.formateadorTooltip = f != null ? f : JsonPath::format;
    }

    public void setUbicador(Consumer<List<Object>> ubicador) {
        this.ubicador = ubicador;
    }

    /** Ruta JSON en un punto de la vista, o null. */
    public List<Object> rutaEn(Point p) {
        if (p == null) return null;
        int off = viewToModel2D(p);
        if (off < 0) return null;
        try {
            // Ignorar el espacio a la derecha del final de la línea
            Rectangle2D r = modelToView2D(off);
            if (r != null && p.x > r.getX() + r.getWidth() + 8 && off == getLineEndOffset(getLineOfOffset(off)) - 1) {
                return null;
            }
        } catch (BadLocationException ex) {
            return null;
        }
        return JsonPath.pathAt(getText(), off);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        if (cajaEn(e.getPoint()) != null) return "Expandir";
        List<Object> ruta = rutaEn(e.getPoint());
        return ruta != null ? formateadorTooltip.apply(ruta) : super.getToolTipText(e);
    }

    @Override
    protected void configurePopupMenu(JPopupMenu popupMenu) {
        super.configurePopupMenu(popupMenu);
        // Quitar ítems agregados en un despliegue anterior
        for (int i = popupMenu.getComponentCount() - 1; i >= 0; i--) {
            if (Boolean.TRUE.equals(((JComponent) popupMenu.getComponent(i)).getClientProperty("json.ruta"))) {
                popupMenu.remove(i);
            }
        }
        List<Object> ruta = rutaEn(ultimoClick);
        String texto = ruta != null ? JsonPath.format(ruta) : null;

        var sep = new JPopupMenu.Separator();
        var ubicar = new JMenuItem("Ubicar en el árbol" + (texto != null ? "  (" + texto + ")" : ""));
        ubicar.setEnabled(ruta != null && ubicador != null);
        ubicar.addActionListener(e -> ubicador.accept(ruta));
        var copiar = new JMenuItem("Copiar ruta");
        copiar.setEnabled(ruta != null);
        copiar.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(texto), null));

        for (JComponent c : List.of(sep, ubicar, copiar)) c.putClientProperty("json.ruta", true);
        popupMenu.insert(copiar, 0);
        popupMenu.insert(ubicar, 0);
        popupMenu.insert(sep, 2);
    }

    private Fold cajaEn(Point p) {
        for (var e : cajas.entrySet()) {
            if (e.getKey().contains(p)) return e.getValue();
        }
        return null;
    }

    /** Todos los folds (recorrido en profundidad). */
    public List<Fold> todosLosFolds() {
        var out = new ArrayList<Fold>();
        FoldManager fm = getFoldManager();
        for (int i = 0; i < fm.getFoldCount(); i++) recolectar(fm.getFold(i), out);
        return out;
    }

    private static void recolectar(Fold f, List<Fold> out) {
        out.add(f);
        for (int i = 0; i < f.getChildCount(); i++) recolectar(f.getChild(i), out);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        cajas.clear();
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        FoldManager fm = getFoldManager();
        for (Fold f : todosLosFolds()) {
            if (!f.isCollapsed()) continue;
            int lineA = f.getStartLine();
            if (fm.isLineHidden(lineA)) continue;
            try {
                int fin = getLineEndOffset(lineA) - 1;   // antes del salto de línea
                Rectangle2D r = modelToView2D(Math.max(fin, getLineStartOffset(lineA)));
                if (r == null) continue;
                int h = getLineHeight();
                int w = 20;
                var caja = new Rectangle((int) r.getX() + 4, (int) r.getY() + 3, w, h - 6);
                g2.setColor(BOX_BG);
                g2.fillRoundRect(caja.x, caja.y, caja.width, caja.height, 6, 6);
                g2.setColor(BOX_BORDER);
                g2.drawRoundRect(caja.x, caja.y, caja.width, caja.height, 6, 6);
                g2.setColor(BOX_DOTS);
                int cy = caja.y + caja.height / 2 - 1;
                for (int d = 0; d < 3; d++) g2.fillOval(caja.x + 4 + d * 5, cy, 3, 3);
                cajas.put(caja, f);
            } catch (BadLocationException ignored) { }
        }
        g2.dispose();
    }
}
