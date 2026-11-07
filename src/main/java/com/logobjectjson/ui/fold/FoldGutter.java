package com.logobjectjson.ui.fold;

import com.logobjectjson.Messages;
import com.logobjectjson.ui.fold.FoldIcons.Kind;
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
 * Fold gutter with TWO slots per line:
 *
 *   [close] gap [open]
 *
 *  • Close : (outermost) foldable range closing on the line → ⌃ expanded / « collapsed
 *  • Open  : (outermost) foldable range opening on the line → ⌄ expanded / » collapsed
 *  • Faint 1 px separator only when the line has both icons.
 *  • Hover: highlights the icon and the one at the other end of the same range + tooltip.
 *  • Click: collapses/expands the range.
 */
public class FoldGutter extends JComponent {

    private static final int PAD = 4;
    private static final int GAP = 3;
    private static final int X_CLOSE   = PAD;
    private static final int X_OPEN = PAD + FoldIcons.SIZE + GAP;
    private static final int WIDTH      = X_OPEN + FoldIcons.SIZE + PAD;

    private static final Color C_IDLE     = new Color(95, 95, 95);
    private static final Color C_COLLAPSED  = new Color(25, 95, 205);
    private static final Color C_HOVER      = new Color(220, 110, 0);
    private static final Color C_SEPARATOR  = new Color(215, 215, 215);

    private final JsonEditor editor;
    private Fold hovered;

    // Recomputed on every paint
    private final Map<Integer, Fold> opensAt  = new HashMap<>();
    private final Map<Integer, Fold> closesAt = new HashMap<>();

    public FoldGutter(JsonEditor editor) {
        this.editor = editor;
        setOpaque(true);
        setToolTipText("");   // registers the component with the ToolTipManager

        var mouse = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                Fold f = foldAt(e.getPoint());
                if (f != hovered) { hovered = f; repaint(); }
                setCursor(f != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                    : Cursor.getDefaultCursor());
            }
            @Override public void mouseExited(MouseEvent e) {
                if (hovered != null) { hovered = null; repaint(); }
            }
            @Override public void mousePressed(MouseEvent e) {
                Fold f = foldAt(e.getPoint());
                if (f != null && SwingUtilities.isLeftMouseButton(e)) {
                    f.toggleCollapsedState();
                    refresh();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);

        // Keep size/painting in sync with the editor
        editor.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { refresh(); }
        });
        editor.getFoldManager().addPropertyChangeListener(e -> refresh());
        editor.addPropertyChangeListener("RSTA.foldToggled", e -> refresh());
    }

    private void refresh() {
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(WIDTH, editor.getPreferredSize().height);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Slot model
    // ─────────────────────────────────────────────────────────────────────

    /** Closing line C of a fold (the fold ends at C−1). */
    private static int closeLine(Fold f) {
        return f.getEndLine() + 1;
    }

    /** Recomputes which fold occupies each slot (outermost = smallest opening offset). */
    private void computeSlots() {
        opensAt.clear();
        closesAt.clear();
        for (Fold f : editor.allFolds()) {
            opensAt.merge(f.getStartLine(), f, FoldGutter::outermost);
            closesAt.merge(closeLine(f), f, FoldGutter::outermost);
        }
    }

    private static Fold outermost(Fold a, Fold b) {
        return a.getStartOffset() <= b.getStartOffset() ? a : b;
    }

    /** Visible line under the y coordinate, or -1. */
    private int lineAtY(int y) {
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

    private Fold foldAt(Point p) {
        computeSlots();
        int line = lineAtY(p.y);
        if (line < 0) return null;
        if (p.x >= X_CLOSE - 1 && p.x < X_CLOSE + FoldIcons.SIZE + 1) return closesAt.get(line);
        if (p.x >= X_OPEN - 1 && p.x < X_OPEN + FoldIcons.SIZE + 1) return opensAt.get(line);
        return null;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        Fold f = foldAt(e.getPoint());
        if (f == null) return null;
        int a = f.getStartLine() + 1;
        int c = closeLine(f) + 1;
        return f.isCollapsed()
                ? Messages.get("fold.expand.tip", a, c, c - a - 1)
                : Messages.get("fold.collapse.tip", a, c);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Painting
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        Rectangle clip = g.getClipBounds();
        g.setColor(getBackground() != null ? getBackground() : Color.WHITE);
        g.fillRect(clip.x, clip.y, clip.width, clip.height);

        computeSlots();
        FoldManager fm = editor.getFoldManager();
        int lineH = editor.getLineHeight();

        int first;
        try {
            first = editor.getLineOfOffset(Math.max(0, editor.viewToModel2D(new Point(0, clip.y))));
        } catch (Exception ex) {
            first = 0;
        }

        for (int line = first; line < editor.getLineCount(); line++) {
            if (fm.isLineHidden(line)) continue;
            int y;
            try { y = editor.yForLine(line); } catch (Exception ex) { continue; }
            if (y < 0) continue;
            if (y > clip.y + clip.height) break;

            int iy = y + (lineH - FoldIcons.SIZE) / 2;
            Fold closing = closesAt.get(line);
            Fold opening   = opensAt.get(line);

            if (closing != null) {
                FoldIcons.paint(g, closing.isCollapsed() ? Kind.LEFT : Kind.UP,
                        X_CLOSE, iy, color(closing));
            }
            if (opening != null) {
                FoldIcons.paint(g, opening.isCollapsed() ? Kind.RIGHT : Kind.DOWN,
                        X_OPEN, iy, color(opening));
            }
            if (closing != null && opening != null) {
                int sx = X_CLOSE + FoldIcons.SIZE + GAP / 2;
                int sh = Math.round(lineH * 0.6f);
                int sy = y + (lineH - sh) / 2;
                g.setColor(C_SEPARATOR);
                g.fillRect(sx, sy, 1, sh);
            }
        }
    }

    private Color color(Fold f) {
        if (f == hovered) return C_HOVER;
        return f.isCollapsed() ? C_COLLAPSED : C_IDLE;
    }
}
