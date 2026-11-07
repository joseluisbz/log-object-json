package com.logobjectjson.ui;

import com.logobjectjson.Config;
import com.logobjectjson.Messages;
import com.logobjectjson.validation.Problem;
import org.fife.ui.rsyntaxtextarea.ErrorStrip;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.parser.AbstractParser;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParseResult;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParserNotice;
import org.fife.ui.rsyntaxtextarea.parser.ParseResult;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Shows the problems of an editor's text, in every way at once:
 *
 *  • red (errors) / amber (warnings) wavy underline, with the message as tooltip;
 *  • ✖/⚠ marks in the line-number gutter;
 *  • an {@link ErrorStrip} next to the scroll bar: one mark per problem at its
 *    relative position in the document (click = go there);
 *  • F2 / Shift+F2 in the editor: next / previous problem;
 *  • a "Problems (n)" list, embedded at the bottom (resizable divider: it does not
 *    cover the text) or detached as a translucent, movable, resizable floating box
 *    that becomes opaque under the cursor.
 *
 * The text is analyzed by the given function, debounced while typing (RSyntaxTextArea
 * parser mechanism). This component wraps the editor's scroll pane.
 */
public class ProblemsView extends JPanel {

    private static final Color ERROR = new Color(200, 50, 40);
    private static final Color WARNING = new Color(215, 140, 0);
    private static final Icon ICON_ERROR = new HoverActionMenu.GlyphIcon("✖", ERROR);
    private static final Icon ICON_WARNING = new HoverActionMenu.GlyphIcon("⚠", WARNING);
    private static final int LIST_HEIGHT = Config.integer("problems.listHeight");
    private static final float FLOATING_OPACITY = Float.parseFloat(Config.string("problems.floatingOpacity"));

    private final RSyntaxTextArea editor;
    private final RTextScrollPane scroll;
    private final Function<String, List<Problem>> analyzer;
    private final Consumer<List<Problem>> onResult;
    private final NoticesParser parser = new NoticesParser();

    private final JPanel content = new JPanel(new BorderLayout());
    private final JPanel listPanel = new JPanel(new BorderLayout());
    private final JPanel header = new JPanel(new BorderLayout());
    private final JLabel title = new JLabel();
    private final DefaultListModel<Problem> model = new DefaultListModel<>();
    private final JList<Problem> list = new JList<>(model);
    private final JScrollPane listScroll = new JScrollPane(list);
    private final JButton btnFloat;
    private JSplitPane split;

    private List<Problem> problems = List.of();
    private boolean floating;
    /** Hidden with ✕ by the user: stays hidden until the problems change. */
    private boolean closedByUser;
    private FloatingBox box;

    /**
     * @param analyzer text → problems (called on the EDT, debounced while typing)
     * @param onResult called with every new result (e.g. to enable/disable Save)
     */
    public ProblemsView(RSyntaxTextArea editor, RTextScrollPane scroll,
                        Function<String, List<Problem>> analyzer, Consumer<List<Problem>> onResult) {
        super(new BorderLayout());
        this.editor = editor;
        this.scroll = scroll;
        this.analyzer = analyzer;
        this.onResult = onResult != null ? onResult : p -> { };

        // Editor area = scroll pane + error strip
        var strip = new ErrorStrip(editor);
        strip.setLevelThreshold(ParserNotice.Level.WARNING);
        content.add(scroll, BorderLayout.CENTER);
        content.add(strip, BorderLayout.EAST);
        scroll.setIconRowHeaderEnabled(true);
        add(content, BorderLayout.CENTER);

        editor.setParserDelay(Config.integer("sync.delayMs"));
        editor.addParser(parser);

        // Problems list
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        buttons.setOpaque(false);
        buttons.add(FindReplacePanel.FlatButton.action("↑", Messages.get("problems.previous"), () -> next(false)));
        buttons.add(FindReplacePanel.FlatButton.action("↓", Messages.get("problems.next"), () -> next(true)));
        btnFloat = FindReplacePanel.FlatButton.action("⧉", Messages.get("problems.float"), this::toggleFloating);
        buttons.add(btnFloat);
        buttons.add(FindReplacePanel.FlatButton.action("✕", Messages.get("problems.close"), this::closeList));
        header.add(title, BorderLayout.CENTER);
        header.add(buttons, BorderLayout.EAST);
        header.setBackground(new Color(238, 240, 244));
        header.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(200, 205, 215)));

        list.setCellRenderer(new ProblemRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) goTo(model.get(i));
            }
        });
        listPanel.add(header, BorderLayout.NORTH);
        listPanel.add(listScroll, BorderLayout.CENTER);

        // F2 / Shift+F2: next / previous problem
        shortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "problems.next", () -> next(true));
        shortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F2, InputEvent.SHIFT_DOWN_MASK), "problems.previous", () -> next(false));
    }

    /** Re-analyzes the text now (e.g. after the problems of the source changed). */
    public void refresh() {
        editor.forceReparsing(parser);
    }

    /** Analyzes now and shows the result immediately; returns it. */
    public List<Problem> validateNow() {
        var ps = analyzer.apply(editor.getText());
        closedByUser = false;
        refresh();
        show(ps);
        return ps;
    }

    public List<Problem> getProblems() {
        return problems;
    }

    /** Goes to the first problem (errors first). */
    public void goToFirst() {
        problems.stream().filter(Problem::isError).findFirst()
                .or(() -> problems.stream().findFirst())
                .ifPresent(this::goTo);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Showing the result
    // ─────────────────────────────────────────────────────────────────────

    private void show(List<Problem> found) {
        var sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingInt(Problem::offset));
        boolean changed = !sorted.equals(problems);
        problems = List.copyOf(sorted);

        // Gutter marks (one per line, the most severe)
        var gutter = scroll.getGutter();
        gutter.removeAllTrackingIcons();
        int lastLine = -1;
        for (var p : problems) {
            int line = lineOf(p.offset());
            if (line == lastLine) continue;
            lastLine = line;
            boolean error = problems.stream().anyMatch(q -> q.isError() && lineOf(q.offset()) == line);
            try {
                gutter.addLineTrackingIcon(line, error ? ICON_ERROR : ICON_WARNING, p.message());
            } catch (BadLocationException ignored) { }
        }

        model.clear();
        problems.forEach(model::addElement);
        long errors = problems.stream().filter(Problem::isError).count();
        title.setText(Messages.get("problems.title", problems.size(), errors, problems.size() - errors));

        if (problems.isEmpty()) {
            closedByUser = false;
            hideList();
        } else {
            if (changed && closedByUser && errors > 0) closedByUser = false;
            if (!closedByUser) showList();
        }
        onResult.accept(problems);
    }

    private void showList() {
        if (floating) {
            if (box == null) openFloating();
            box.setVisible(true);
            return;
        }
        if (split != null && split.getParent() == this) return;
        removeAll();
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, content, listPanel);
        split.setResizeWeight(1.0);
        split.setContinuousLayout(true);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        revalidate();
        SwingUtilities.invokeLater(() -> split.setDividerLocation(Math.max(80, getHeight() - LIST_HEIGHT)));
    }

    private void hideList() {
        if (box != null) box.setVisible(false);
        if (split != null && split.getParent() == this) {
            removeAll();
            add(content, BorderLayout.CENTER);
            revalidate();
            repaint();
        }
    }

    private void closeList() {
        closedByUser = true;
        hideList();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Embedded ↔ floating
    // ─────────────────────────────────────────────────────────────────────

    private void toggleFloating() {
        if (floating) dock();
        else floatList();
    }

    private void floatList() {
        hideList();
        floating = true;
        openFloating();
        btnFloat.setText("⤓");
        btnFloat.setToolTipText(Messages.get("problems.dock"));
    }

    private void dock() {
        if (box != null) {
            box.dispose();
            box = null;
        }
        floating = false;
        setTranslucentChildren(false);
        btnFloat.setText("⧉");
        btnFloat.setToolTipText(Messages.get("problems.float"));
        if (!problems.isEmpty() && !closedByUser) showList();
    }

    private void openFloating() {
        var root = SwingUtilities.getRootPane(this);
        if (root == null) return;
        setTranslucentChildren(true);
        box = new FloatingBox(listPanel, header);
        var layers = root.getLayeredPane();
        var at = SwingUtilities.convertPoint(this, 0, 0, layers);
        int w = Math.max(320, getWidth() * 3 / 4), h = LIST_HEIGHT;
        box.setBounds(at.x + (getWidth() - w) / 2, at.y + getHeight() - h - 12, w, h);
        layers.add(box, JLayeredPane.PALETTE_LAYER);
        layers.revalidate();
        layers.repaint();
    }

    /** Non-opaque children so the floating box's translucency applies to them too. */
    private void setTranslucentChildren(boolean translucent) {
        list.setOpaque(!translucent);
        listScroll.setOpaque(!translucent);
        listScroll.getViewport().setOpaque(!translucent);
        header.setOpaque(!translucent);
        listPanel.setOpaque(!translucent);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Navigation
    // ─────────────────────────────────────────────────────────────────────

    private void next(boolean forward) {
        if (problems.isEmpty()) return;
        int caret = editor.getSelectionStart();
        Problem target = null;
        if (forward) {
            for (var p : problems) if (p.offset() > caret) { target = p; break; }
            if (target == null) target = problems.get(0);
        } else {
            for (int i = problems.size() - 1; i >= 0; i--) {
                if (problems.get(i).offset() < caret) { target = problems.get(i); break; }
            }
            if (target == null) target = problems.get(problems.size() - 1);
        }
        goTo(target);
    }

    /** Selects the problem's fragment in the editor (expanding folds) and centers it. */
    public void goTo(Problem p) {
        int len = editor.getDocument().getLength();
        int start = Math.min(p.offset(), len), end = Math.min(len, start + p.length());
        editor.getFoldManager().ensureOffsetNotInClosedFold(start);
        editor.requestFocusInWindow();
        editor.select(start, end);
        editor.getCaret().setSelectionVisible(true);
        SwingUtilities.invokeLater(() -> Centering.on(editor, start));
        int idx = problems.indexOf(p);
        if (idx >= 0) {
            list.setSelectedIndex(idx);
            list.ensureIndexIsVisible(idx);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────

    private int lineOf(int offset) {
        var root = editor.getDocument().getDefaultRootElement();
        return root.getElementIndex(Math.min(offset, editor.getDocument().getLength()));
    }

    private void shortcut(KeyStroke ks, String name, Runnable r) {
        editor.getInputMap(JComponent.WHEN_FOCUSED).put(ks, name);
        editor.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    /** RSyntaxTextArea parser: runs the analyzer (debounced) and turns problems into notices. */
    private final class NoticesParser extends AbstractParser {
        @Override
        public ParseResult parse(RSyntaxDocument doc, String style) {
            var result = new DefaultParseResult(this);
            String text;
            try {
                text = doc.getText(0, doc.getLength());
            } catch (BadLocationException ex) {
                return result;
            }
            var found = analyzer.apply(text);
            var root = doc.getDefaultRootElement();
            for (var p : found) {
                int off = Math.min(p.offset(), Math.max(0, doc.getLength() - 1));
                int len = Math.max(1, Math.min(p.length(), doc.getLength() - off));
                var n = new DefaultParserNotice(this, p.message(), root.getElementIndex(off), off, len);
                n.setLevel(p.isError() ? ParserNotice.Level.ERROR : ParserNotice.Level.WARNING);
                n.setColor(p.isError() ? ERROR : WARNING);
                n.setShowInEditor(true);
                result.addNotice(n);
            }
            SwingUtilities.invokeLater(() -> show(found));
            return result;
        }
    }

    /** List row: ✖/⚠  Line L, col C   message. */
    private final class ProblemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                      boolean selected, boolean focus) {
            super.getListCellRendererComponent(l, value, index, selected, focus);
            var p = (Problem) value;
            int off = Math.min(p.offset(), editor.getDocument().getLength());
            var root = editor.getDocument().getDefaultRootElement();
            int line = root.getElementIndex(off);
            int col = off - root.getElement(line).getStartOffset();
            setText(Messages.get("problems.position", line + 1, col + 1) + "     " + p.message());
            setIcon(p.isError() ? ICON_ERROR : ICON_WARNING);
            setToolTipText(p.message());
            setOpaque(selected || l.isOpaque());
            return this;
        }
    }

    /**
     * Floating, translucent, movable and resizable box (in the window's layered pane).
     * Opaque while the cursor is over it, translucent otherwise (the JSON stays visible).
     */
    private static final class FloatingBox extends JPanel {
        private static final Color BG = new Color(250, 251, 253);
        private static final Color BORDER = new Color(150, 160, 180);
        private float alpha = FLOATING_OPACITY;
        private final Timer hover;

        FloatingBox(JComponent inner, JComponent dragHandle) {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(BorderFactory.createLineBorder(BORDER));
            add(inner, BorderLayout.CENTER);

            // Move by dragging the header
            var move = new MouseAdapter() {
                Point grab;
                @Override public void mousePressed(MouseEvent e) { grab = e.getPoint(); }
                @Override public void mouseDragged(MouseEvent e) {
                    if (grab == null || getParent() == null) return;
                    var p = SwingUtilities.convertPoint(dragHandle, e.getPoint(), getParent());
                    int x = Math.max(0, Math.min(p.x - grab.x, getParent().getWidth() - getWidth()));
                    int y = Math.max(0, Math.min(p.y - grab.y, getParent().getHeight() - getHeight()));
                    setLocation(x, y);
                }
            };
            dragHandle.addMouseListener(move);
            dragHandle.addMouseMotionListener(move);
            dragHandle.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));

            // Resize from the bottom-right corner
            var grip = new JLabel("◢");
            grip.setForeground(Color.GRAY);
            grip.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
            var resize = new MouseAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    var p = SwingUtilities.convertPoint(grip, e.getPoint(), FloatingBox.this);
                    setSize(Math.max(260, p.x + 4), Math.max(80, p.y + 4));
                    revalidate();
                }
            };
            grip.addMouseMotionListener(resize);
            var south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            south.setOpaque(false);
            south.add(grip);
            add(south, BorderLayout.SOUTH);

            // Opaque under the cursor, translucent otherwise
            hover = new Timer(120, e -> {
                if (!isShowing()) return;
                var pi = MouseInfo.getPointerInfo();
                if (pi == null) return;
                var p = pi.getLocation();
                SwingUtilities.convertPointFromScreen(p, this);
                float target = contains(p) ? 1f : FLOATING_OPACITY;
                if (target != alpha) {
                    alpha = target;
                    repaint();
                }
            });
            hover.start();
        }

        void dispose() {
            hover.stop();
            var parent = getParent();
            removeAll();
            if (parent != null) {
                parent.remove(this);
                parent.repaint();
            }
        }

        @Override
        public void paint(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g2.setColor(BG);
            g2.fillRect(0, 0, getWidth(), getHeight());
            super.paint(g2);
            g2.dispose();
        }
    }
}
