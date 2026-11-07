package com.logobjectjson.ui;

import com.logobjectjson.Config;
import com.logobjectjson.Messages;
import org.fife.ui.rtextarea.RTextArea;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Discoverable action menu for any component (JTree nodes, JSON keys…):
 *
 *   1. HIDDEN : nothing is shown.
 *   2. GHOST  : while the cursor is over something with actions, a pale,
 *               translucent menu follows the cursor. It does not take the mouse
 *               ({@link #contains} is false), so events reach the host below it.
 *   3. FIXED  : a right click freezes the menu where it was clicked and fades
 *               it to fully opaque and interactive; hovering an option
 *               highlights it, a left click runs it.
 *   4. A left click outside the menu, or Escape, closes it (back to HIDDEN).
 *
 * The menu lives in the window's {@link JLayeredPane} (popup layer), so it can
 * extend beyond the host's bounds. Opacities, offset and fade come from
 * application.yml (hoverMenu.*).
 */
public class HoverActionMenu extends JComponent {

    /** One option: text, icon, whether it applies here, and what it does. */
    public record Item(String text, Icon icon, boolean enabled, String tooltip, Runnable action) { }

    /**
     * Options for a point of the host (host coordinates); empty = nothing there.
     * {@code fixed} is false while following the cursor (offer only what is specific
     * to that point) and true on a right click (may add general actions, e.g. editing).
     */
    @FunctionalInterface
    public interface Provider {
        List<Item> itemsAt(Point p, boolean fixed);
    }

    private enum State { HIDDEN, GHOST, FIXED }

    private static final float GHOST_OPACITY = Float.parseFloat(Config.string("hoverMenu.ghostOpacity"));
    private static final int OFFSET_X = Config.integer("hoverMenu.offsetX");
    private static final int OFFSET_Y = Config.integer("hoverMenu.offsetY");
    private static final int FADE_MS = Config.integer("hoverMenu.fadeMs");
    private static final int FADE_TICK_MS = 15;

    private static final int ROW_H = 24;
    private static final int PAD = 4;
    private static final int ICON_GAP = 8;
    private static final int ARC = 10;
    private static final Color BG = new Color(252, 252, 253);
    private static final Color BORDER = new Color(170, 178, 190);
    private static final Color SHADOW = new Color(0, 0, 0, 40);
    private static final Color HOVER_BG = new Color(210, 225, 250);
    private static final Color TEXT = new Color(30, 30, 30);
    private static final Color TEXT_DISABLED = new Color(160, 160, 160);

    private final JComponent host;
    private final Provider provider;

    private State state = State.HIDDEN;
    private List<Item> items = List.of();
    private int hoverRow = -1;
    private float alpha;
    private float targetAlpha;
    private final Timer fade = new Timer(FADE_TICK_MS, e -> stepFade());
    private AWTEventListener closer;

    private HoverActionMenu(JComponent host, Provider provider) {
        this.host = host;
        this.provider = provider;
        setOpaque(false);
        setFont(UIManager.getFont("MenuItem.font"));

        // Interaction with the menu itself (only reachable in the FIXED state, see contains())
        var self = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { setHoverRow(rowAt(e.getY())); }
            @Override public void mouseExited(MouseEvent e) { setHoverRow(-1); }
            @Override public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                int row = rowAt(e.getY());
                if (row < 0 || !items.get(row).enabled()) return;
                Runnable action = items.get(row).action();
                hideMenu();
                action.run();
            }
        };
        addMouseListener(self);
        addMouseMotionListener(self);
    }

    /**
     * Installs the menu on a host component.
     *
     * @return the menu, e.g. to open it from the keyboard with {@link #fixAt}
     */
    public static HoverActionMenu install(JComponent host, Provider provider) {
        var menu = new HoverActionMenu(host, provider);
        var hostMouse = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { menu.ghostAt(e.getPoint()); }
            @Override public void mouseDragged(MouseEvent e) { if (menu.state == State.GHOST) menu.hideMenu(); }
            @Override public void mouseExited(MouseEvent e) {
                // Ignore the exit caused by the cursor entering the (fixed) menu
                if (menu.state == State.GHOST) menu.hideMenu();
            }
            @Override public void mousePressed(MouseEvent e)  { menu.secondaryClick(e); }
            @Override public void mouseReleased(MouseEvent e) { menu.secondaryClick(e); }
        };
        host.addMouseListener(hostMouse);
        host.addMouseMotionListener(hostMouse);
        return menu;
    }

    // ─────────────────────────────────────────────────────────────────────
    // State transitions
    // ─────────────────────────────────────────────────────────────────────

    /** GHOST: follows the cursor while it is over something with actions. */
    private void ghostAt(Point p) {
        if (state == State.FIXED) return;
        var found = provider.itemsAt(p, false);
        if (found == null || found.isEmpty()) {
            hideMenu();
            return;
        }
        if (state == State.HIDDEN) alpha = 0f;
        state = State.GHOST;
        items = found;
        hoverRow = -1;
        place(p, OFFSET_X, OFFSET_Y);
        fadeTo(GHOST_OPACITY);
    }

    /** Right click (popup trigger) on the host: freeze where it was clicked. */
    private void secondaryClick(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        e.consume();                              // no other context menu
        fixAt(e.getPoint());
    }

    /** FIXED: frozen at p (host coordinates), opaque and interactive. */
    public void fixAt(Point p) {
        var found = provider.itemsAt(p, true);
        if (found == null || found.isEmpty()) {
            hideMenu();
            return;
        }
        if (state == State.HIDDEN) alpha = GHOST_OPACITY;
        state = State.FIXED;
        items = found;
        hoverRow = -1;
        place(p, 0, 0);
        fadeTo(1f);
        installCloser();
    }

    public void hideMenu() {
        state = State.HIDDEN;
        fade.stop();
        removeCloser();
        if (getParent() != null) {
            var parent = getParent();
            parent.remove(this);
            parent.repaint(getX() - 4, getY() - 4, getWidth() + 8, getHeight() + 8);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Placement and fading
    // ─────────────────────────────────────────────────────────────────────

    /** Positions the menu at p (+offset), inside the window's layered pane. */
    private void place(Point p, int dx, int dy) {
        var root = SwingUtilities.getRootPane(host);
        if (root == null) return;
        var layers = root.getLayeredPane();
        if (getParent() != layers) {
            if (getParent() != null) getParent().remove(this);
            layers.add(this, JLayeredPane.POPUP_LAYER);
        }
        var size = preferredMenuSize();
        var at = SwingUtilities.convertPoint(host, p, layers);
        int x = at.x + dx;
        int y = at.y + dy - (dy < 0 ? size.height : 0);   // a negative offset places it above the cursor
        x = Math.max(0, Math.min(x, layers.getWidth() - size.width));
        y = Math.max(0, Math.min(y, layers.getHeight() - size.height));
        var old = getBounds();
        setBounds(x, y, size.width, size.height);
        layers.repaint(old);
        repaint();
    }

    private void fadeTo(float target) {
        targetAlpha = target;
        if (Math.abs(alpha - target) > 0.001f) fade.start();
        else repaint();
    }

    private void stepFade() {
        float step = Math.max(0.02f, (float) FADE_TICK_MS / Math.max(1, FADE_MS));
        alpha = alpha < targetAlpha ? Math.min(targetAlpha, alpha + step) : Math.max(targetAlpha, alpha - step);
        if (alpha == targetAlpha) fade.stop();
        repaint();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Closing: left click outside or Escape
    // ─────────────────────────────────────────────────────────────────────

    private void installCloser() {
        if (closer != null) return;
        closer = event -> {
            if (state != State.FIXED) return;
            if (event instanceof KeyEvent k && k.getID() == KeyEvent.KEY_PRESSED && k.getKeyCode() == KeyEvent.VK_ESCAPE) {
                k.consume();
                hideMenu();
            } else if (event instanceof MouseEvent m && m.getID() == MouseEvent.MOUSE_PRESSED
                    && SwingUtilities.isLeftMouseButton(m) && m.getComponent() != null && m.getComponent().isShowing()
                    && isShowing()) {
                var p = SwingUtilities.convertPoint(m.getComponent(), m.getPoint(), this);
                if (!super.contains(p.x, p.y)) hideMenu();
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(closer, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
    }

    private void removeCloser() {
        if (closer != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(closer);
            closer = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Mouse transparency: only the FIXED menu takes the mouse
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public boolean contains(int x, int y) {
        return state == State.FIXED && super.contains(x, y);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int row = rowAt(e.getY());
        return row < 0 ? null : items.get(row).tooltip();
    }

    private int rowAt(int y) {
        int row = (y - PAD) / ROW_H;
        return y < PAD || row >= items.size() ? -1 : row;
    }

    private void setHoverRow(int row) {
        if (row != hoverRow) {
            hoverRow = row;
            setToolTipText(row < 0 ? null : items.get(row).tooltip());
            repaint();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Painting
    // ─────────────────────────────────────────────────────────────────────

    private Dimension preferredMenuSize() {
        var fm = getFontMetrics(getFont());
        int w = 0;
        for (var it : items) {
            int iw = it.icon() == null ? 16 : it.icon().getIconWidth();
            w = Math.max(w, iw + ICON_GAP + fm.stringWidth(it.text()));
        }
        return new Dimension(w + 2 * PAD + 16 + 3, items.size() * ROW_H + 2 * PAD + 3);
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (state == State.HIDDEN || items.isEmpty()) return;
        var g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
            int w = getWidth() - 3, h = getHeight() - 3;

            g2.setColor(SHADOW);
            g2.fillRoundRect(3, 3, w, h, ARC, ARC);
            g2.setColor(BG);
            g2.fillRoundRect(0, 0, w, h, ARC, ARC);
            g2.setColor(BORDER);
            g2.drawRoundRect(0, 0, w, h, ARC, ARC);

            var fm = g2.getFontMetrics(getFont());
            g2.setFont(getFont());
            for (int i = 0; i < items.size(); i++) {
                var it = items.get(i);
                int y = PAD + i * ROW_H;
                if (state == State.FIXED && i == hoverRow && it.enabled()) {
                    g2.setColor(HOVER_BG);
                    g2.fillRoundRect(2, y, w - 3, ROW_H, 6, 6);
                }
                int x = PAD + 6;
                if (it.icon() != null) {
                    it.icon().paintIcon(this, g2, x, y + (ROW_H - it.icon().getIconHeight()) / 2);
                    x += it.icon().getIconWidth();
                } else {
                    x += 16;
                }
                g2.setColor(it.enabled() ? TEXT : TEXT_DISABLED);
                g2.drawString(it.text(), x + ICON_GAP, y + (ROW_H + fm.getAscent() - fm.getDescent()) / 2);
            }
        } finally {
            g2.dispose();
        }
    }

    /**
     * Standard editing actions for a text editor (replace its own context menu,
     * so there is a single menu): Undo, Cut, Copy, Paste, Select all.
     */
    public static List<Item> editItems(RTextArea editor) {
        boolean editable = editor.isEditable() && editor.isEnabled();
        boolean selection = editor.getSelectionStart() != editor.getSelectionEnd();
        return List.of(
                new Item(Messages.get("edit.undo"), null, editable && editor.canUndo(), null, editor::undoLastAction),
                new Item(Messages.get("edit.cut"), null, editable && selection, null, editor::cut),
                new Item(Messages.get("edit.copy"), null, selection, null, editor::copy),
                new Item(Messages.get("edit.paste"), null, editable, null, editor::paste),
                new Item(Messages.get("edit.selectAll"), null, editor.getDocument().getLength() > 0, null, editor::selectAll));
    }

    /** Joins item lists (e.g. specific actions + editing actions). */
    @SafeVarargs
    public static List<Item> concat(List<Item>... lists) {
        var out = new java.util.ArrayList<Item>();
        for (var l : lists) out.addAll(l);
        return out;
    }

    /** Simple drawn icon: a glyph in a color (keeps the menu free of image files). */
    public record GlyphIcon(String glyph, Color color) implements Icon {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setFont(new Font(Font.DIALOG, Font.BOLD, 12));
            var fm = g2.getFontMetrics();
            g2.drawString(glyph, x + (16 - fm.stringWidth(glyph)) / 2, y + (16 + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
        @Override public int getIconWidth()  { return 16; }
        @Override public int getIconHeight() { return 16; }
    }
}
