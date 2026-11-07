package com.dynamicparser.ui.fold;

import com.dynamicparser.ui.fold.FoldIcons.Kind;
import org.fife.ui.rsyntaxtextarea.folding.Fold;
import org.fife.ui.rsyntaxtextarea.folding.FoldManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Margen de plegado con DOS espacios por línea:
 *
 *   [cierre] gap [apertura]
 *
 *  • Cierre   : rango plegable (más externo) que cierra en la línea → ⌃ expandido / « colapsado
 *  • Apertura : rango plegable (más externo) que abre en la línea   → ⌄ expandido / » colapsado
 *  • Separador tenue de 1 px sólo cuando la línea tiene ambos íconos.
 *  • Hover: resalta el ícono y el del otro extremo del mismo rango + tooltip.
 *  • Click: colapsa/expande el rango.
 */
public class FoldGutter extends JComponent {

    private static final int PAD = 4;
    private static final int GAP = 3;
    private static final int X_CIERRE   = PAD;
    private static final int X_APERTURA = PAD + FoldIcons.SIZE + GAP;
    private static final int ANCHO      = X_APERTURA + FoldIcons.SIZE + PAD;

    private static final Color C_REPOSO     = new Color(95, 95, 95);
    private static final Color C_COLAPSADO  = new Color(25, 95, 205);
    private static final Color C_HOVER      = new Color(220, 110, 0);
    private static final Color C_SEPARADOR  = new Color(215, 215, 215);

    private final JsonEditor editor;
    private Fold hovered;

    // Recalculados en cada paint
    private final Map<Integer, Fold> abreEn  = new HashMap<>();
    private final Map<Integer, Fold> cierraEn = new HashMap<>();

    public FoldGutter(JsonEditor editor) {
        this.editor = editor;
        setOpaque(true);
        setToolTipText("");   // registra el componente en el ToolTipManager

        var mouse = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                Fold f = foldEn(e.getPoint());
                if (f != hovered) { hovered = f; repaint(); }
                setCursor(f != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                    : Cursor.getDefaultCursor());
            }
            @Override public void mouseExited(MouseEvent e) {
                if (hovered != null) { hovered = null; repaint(); }
            }
            @Override public void mousePressed(MouseEvent e) {
                Fold f = foldEn(e.getPoint());
                if (f != null && SwingUtilities.isLeftMouseButton(e)) {
                    f.toggleCollapsedState();
                    refrescar();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);

        // Mantener tamaño/pintado sincronizado con el editor
        editor.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { refrescar(); }
        });
        editor.getFoldManager().addPropertyChangeListener(e -> refrescar());
        editor.addPropertyChangeListener("RSTA.foldToggled", e -> refrescar());
    }

    private void refrescar() {
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(ANCHO, editor.getPreferredSize().height);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Modelo de slots
    // ─────────────────────────────────────────────────────────────────────

    /** Línea de cierre C de un fold (el fold termina en C−1). */
    private static int lineaCierre(Fold f) {
        return f.getEndLine() + 1;
    }

    /** Recalcula qué fold ocupa cada slot (el más externo = menor offset de apertura). */
    private void calcularSlots() {
        abreEn.clear();
        cierraEn.clear();
        for (Fold f : editor.todosLosFolds()) {
            abreEn.merge(f.getStartLine(), f, FoldGutter::masExterno);
            cierraEn.merge(lineaCierre(f), f, FoldGutter::masExterno);
        }
    }

    private static Fold masExterno(Fold a, Fold b) {
        return a.getStartOffset() <= b.getStartOffset() ? a : b;
    }

    /** Línea visible bajo la coordenada y, o -1. */
    private int lineaEnY(int y) {
        int off = editor.viewToModel2D(new Point(0, y));
        if (off < 0) return -1;
        try {
            int line = editor.getLineOfOffset(off);
            int top = editor.yForLine(line);
            return (top >= 0 && y >= top && y < top + editor.getLineHeight()) ? line : -1;
        } catch (Exception ex) {
            return -1;
        }
    }

    private Fold foldEn(Point p) {
        calcularSlots();
        int line = lineaEnY(p.y);
        if (line < 0) return null;
        if (p.x >= X_CIERRE - 1 && p.x < X_CIERRE + FoldIcons.SIZE + 1) return cierraEn.get(line);
        if (p.x >= X_APERTURA - 1 && p.x < X_APERTURA + FoldIcons.SIZE + 1) return abreEn.get(line);
        return null;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        Fold f = foldEn(e.getPoint());
        if (f == null) return null;
        int a = f.getStartLine() + 1;
        int c = lineaCierre(f) + 1;
        return f.isCollapsed()
                ? "Expandir líneas " + a + "–" + c + " (" + (c - a - 1) + " ocultas)"
                : "Colapsar líneas " + a + "–" + c;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Pintado
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        Rectangle clip = g.getClipBounds();
        g.setColor(getBackground() != null ? getBackground() : Color.WHITE);
        g.fillRect(clip.x, clip.y, clip.width, clip.height);

        calcularSlots();
        FoldManager fm = editor.getFoldManager();
        int lineH = editor.getLineHeight();

        int primera;
        try {
            primera = editor.getLineOfOffset(Math.max(0, editor.viewToModel2D(new Point(0, clip.y))));
        } catch (Exception ex) {
            primera = 0;
        }

        for (int line = primera; line < editor.getLineCount(); line++) {
            if (fm.isLineHidden(line)) continue;
            int y;
            try { y = editor.yForLine(line); } catch (Exception ex) { continue; }
            if (y < 0) continue;
            if (y > clip.y + clip.height) break;

            int iy = y + (lineH - FoldIcons.SIZE) / 2;
            Fold cierre = cierraEn.get(line);
            Fold abre   = abreEn.get(line);

            if (cierre != null) {
                FoldIcons.paint(g, cierre.isCollapsed() ? Kind.LEFT : Kind.UP,
                        X_CIERRE, iy, color(cierre));
            }
            if (abre != null) {
                FoldIcons.paint(g, abre.isCollapsed() ? Kind.RIGHT : Kind.DOWN,
                        X_APERTURA, iy, color(abre));
            }
            if (cierre != null && abre != null) {
                int sx = X_CIERRE + FoldIcons.SIZE + GAP / 2;
                int sh = Math.round(lineH * 0.6f);
                int sy = y + (lineH - sh) / 2;
                g.setColor(C_SEPARADOR);
                g.fillRect(sx, sy, 1, sh);
            }
        }
    }

    private Color color(Fold f) {
        if (f == hovered) return C_HOVER;
        return f.isCollapsed() ? C_COLAPSADO : C_REPOSO;
    }
}
