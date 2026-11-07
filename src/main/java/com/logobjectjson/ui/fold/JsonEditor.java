package com.logobjectjson.ui.fold;

import com.logobjectjson.Messages;
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
import java.util.function.Function;

/**
 * JSON editor (RSyntaxTextArea ⊂ JTextArea) with Postman-style folding.
 * Draws a "⋯" box after the brace of each collapsed range; a click expands it.
 * Tooltip with the JSON path of the key/value under the mouse, and a context
 * menu "Locate in tree" / "Copy path".
 */
public class JsonEditor extends RSyntaxTextArea {

    static {
        FoldParserManager.get().addFoldParserMapping(
                SyntaxConstants.SYNTAX_STYLE_JSON, new JsonFoldParser());
    }

    private static final Color BOX_BG     = new Color(232, 238, 248);
    private static final Color BOX_BORDER = new Color(160, 180, 215);
    private static final Color BOX_DOTS   = new Color(70, 100, 160);
    private static final String MENU_MARK = "json.path";

    /** "⋯" boxes painted in the last repaint → fold they expand. */
    private final Map<Rectangle, Fold> boxes = new LinkedHashMap<>();

    /** "Locate in tree" action (provided by whoever creates the editor). */
    private Consumer<List<Object>> locator;
    /** Path format for the tooltip (allows marking Map keys with "<,>"). */
    private Function<List<Object>, String> tooltipFormatter = JsonPath::format;
    /** Point of the last click (for the context menu). */
    private Point lastClick;

    public JsonEditor(String text) {
        super(text);
        ToolTipManager.sharedInstance().registerComponent(this);
        setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        setCodeFoldingEnabled(true);

        var mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                lastClick = e.getPoint();
                Fold f = boxAt(e.getPoint());
                if (f != null) {
                    f.setCollapsed(false);
                    e.consume();
                }
            }
            @Override public void mouseMoved(MouseEvent e) {
                setCursor(boxAt(e.getPoint()) != null
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public void setTooltipFormatter(Function<List<Object>, String> f) {
        this.tooltipFormatter = f != null ? f : JsonPath::format;
    }

    public void setLocator(Consumer<List<Object>> locator) {
        this.locator = locator;
    }

    /** JSON path at a view point, or null. */
    public List<Object> pathAt(Point p) {
        if (p == null) return null;
        int off = viewToModel2D(p);
        if (off < 0) return null;
        try {
            // Ignore the space to the right of the end of the line
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
        if (boxAt(e.getPoint()) != null) return Messages.get("json.expand");
        List<Object> path = pathAt(e.getPoint());
        return path != null ? tooltipFormatter.apply(path) : super.getToolTipText(e);
    }

    @Override
    protected void configurePopupMenu(JPopupMenu popupMenu) {
        super.configurePopupMenu(popupMenu);
        // Remove items added by a previous popup
        for (int i = popupMenu.getComponentCount() - 1; i >= 0; i--) {
            if (Boolean.TRUE.equals(((JComponent) popupMenu.getComponent(i)).getClientProperty(MENU_MARK))) {
                popupMenu.remove(i);
            }
        }
        List<Object> path = pathAt(lastClick);
        String text = path != null ? JsonPath.format(path) : null;

        var sep = new JPopupMenu.Separator();
        var locate = new JMenuItem(Messages.get("menu.locateInTree") + (text != null ? "  (" + text + ")" : ""));
        locate.setEnabled(path != null && locator != null);
        locate.addActionListener(e -> locator.accept(path));
        var copy = new JMenuItem(Messages.get("json.copyPath"));
        copy.setEnabled(path != null);
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null));

        for (JComponent c : List.of(sep, locate, copy)) c.putClientProperty(MENU_MARK, true);
        popupMenu.insert(copy, 0);
        popupMenu.insert(locate, 0);
        popupMenu.insert(sep, 2);
    }

    private Fold boxAt(Point p) {
        for (var e : boxes.entrySet()) {
            if (e.getKey().contains(p)) return e.getValue();
        }
        return null;
    }

    /** All folds (depth-first). */
    public List<Fold> allFolds() {
        var out = new ArrayList<Fold>();
        FoldManager fm = getFoldManager();
        for (int i = 0; i < fm.getFoldCount(); i++) collect(fm.getFold(i), out);
        return out;
    }

    private static void collect(Fold f, List<Fold> out) {
        out.add(f);
        for (int i = 0; i < f.getChildCount(); i++) collect(f.getChild(i), out);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        boxes.clear();
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        FoldManager fm = getFoldManager();
        for (Fold f : allFolds()) {
            if (!f.isCollapsed()) continue;
            int lineA = f.getStartLine();
            if (fm.isLineHidden(lineA)) continue;
            try {
                int end = getLineEndOffset(lineA) - 1;   // before the line break
                Rectangle2D r = modelToView2D(Math.max(end, getLineStartOffset(lineA)));
                if (r == null) continue;
                int h = getLineHeight();
                int w = 20;
                var box = new Rectangle((int) r.getX() + 4, (int) r.getY() + 3, w, h - 6);
                g2.setColor(BOX_BG);
                g2.fillRoundRect(box.x, box.y, box.width, box.height, 6, 6);
                g2.setColor(BOX_BORDER);
                g2.drawRoundRect(box.x, box.y, box.width, box.height, 6, 6);
                g2.setColor(BOX_DOTS);
                int cy = box.y + box.height / 2 - 1;
                for (int d = 0; d < 3; d++) g2.fillOval(box.x + 4 + d * 5, cy, 3, 3);
                boxes.put(box, f);
            } catch (BadLocationException ignored) { }
        }
        g2.dispose();
    }
}
