package com.logobjectjson.ui;

import com.logobjectjson.Config;
import com.logobjectjson.Messages;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Find / Replace on an RSyntaxTextArea, IntelliJ look, with persistent
 * history for both the search and the replacement.
 *
 *   ┌───┬───────────────────────────────────────────┬─────────────────────────┐
 *   │ › │ [🔍▾ find…                    Cc  W  .* ] ⋮ │ 3 of 12   ↑  ↓           │
 *   │   │ [🔍▾ replace…                           ]   │ [Replace] [Replace all] │ ← [›] or Ctrl+H
 *   └───┴───────────────────────────────────────────┴─────────────────────────┘
 *
 *  • The options (Cc = match case, W = whole word, .* = regex) live INSIDE the box.
 *  • The magnifier ▾ opens the history (also Alt+↓ in the box).
 *  • Flat buttons: filled only when active or hovered.
 *  • Replace is only available when the editor is editable.
 *
 * Editor shortcuts: Ctrl+F find · Ctrl+H replace · F3 / Shift+F3 next/previous.
 * In the boxes: Enter next · Shift+Enter previous · Esc back to the editor · Alt+↓ history.
 */
public class FindReplacePanel {

    private static final int MAX_HISTORY = Config.integer("search.maxHistory");
    private static final Preferences PREFS = Preferences.userNodeForPackage(FindReplacePanel.class);
    private static final String HIST_FIND = "history.find";
    private static final String HIST_REPLACE = "history.replace";
    private static final char SEP = '\u0001';
    private static final int MAX_HISTORY_ITEM_TEXT = 80;

    private static final Color ERROR_BG      = new Color(255, 225, 225);
    private static final Color BOX_BORDER    = new Color(190, 190, 190);
    private static final Color FOCUS_BORDER  = new Color(60, 120, 220);
    private static final Color ACTIVE_BG     = new Color(205, 222, 250);
    private static final Color ACTIVE_BORDER = new Color(110, 150, 220);
    private static final Color HOVER_BG      = new Color(228, 228, 228);

    /** More matches than this are not all highlighted (it would be slow on huge lines). */
    private static final int MAX_MARK_ALL = Config.integer("search.maxMarkAll");
    /** In documents with lines longer than LONG_LINE characters, highlight at most this many. */
    private static final int LONG_LINE = Config.integer("search.longLine");
    private static final int MAX_MARK_LONG_LINE = Config.integer("search.maxMarkLongLine");

    private static final String PREF_WIDTH = "boxes.width";
    private static final int WIDTH_MIN = Config.integer("search.minWidth");
    private static final int WIDTH_MAX = Config.integer("search.maxWidth");
    private static final int WIDTH_DEFAULT = Config.integer("search.defaultWidth");

    private final RSyntaxTextArea editor;

    private final JTextField txtFind = new JTextField();
    private final JTextField txtReplace = new JTextField();
    private final Box findBox;
    private final Box replaceBox;

    private final FlatButton tglCase  = FlatButton.toggle(Messages.get("search.matchCase.short"), Messages.get("search.matchCase"));
    private final FlatButton tglWord  = FlatButton.toggle("W",  Messages.get("search.wholeWord"));
    private final FlatButton tglRegex = FlatButton.toggle(".*", Messages.get("search.regex"));
    private final FlatButton tglReplace = FlatButton.toggle("›", Messages.get("search.toggleReplace"));
    private final JLabel lblStatus = new JLabel();
    private final JButton btnReplace = new JButton(Messages.get("search.replace"));
    private final JButton btnReplaceAll = new JButton(Messages.get("search.replaceAll"));

    private final JPanel findRow = new JPanel();
    private final JPanel replaceRow = new JPanel();

    /** Fixed-size components: recomputed when the widget joins the window (LAF already applied). */
    private final List<JComponent> fixedComponents = new ArrayList<>();

    /** Whole widget: [›] | find / replace rows. */
    private final JPanel widget = new JPanel(new BorderLayout(2, 0)) {
        @Override public void addNotify() {
            super.addNotify();
            recomputeSizes();
        }
    };

    /** Avoids searching when the text is set from the history. */
    private boolean silent;

    public FindReplacePanel(RSyntaxTextArea editor) {
        this.editor = editor;
        findBox = new Box(txtFind, HIST_FIND, Messages.get("search.find"), tglCase, tglWord, tglRegex);
        replaceBox = new Box(txtReplace, HIST_REPLACE, Messages.get("search.replaceWith"));
        build();
        registerEvents();
        updateReplaceAvailability();
        replaceRow.setVisible(false);
    }

    /** The whole find/replace widget (to insert it into a toolbar or panel). */
    public JComponent getPanel() { return widget; }

    // ─────────────────────────────────────────────────────────────────────
    // UI
    // ─────────────────────────────────────────────────────────────────────

    private void build() {
        lblStatus.setPreferredSize(new Dimension(95, lblStatus.getPreferredSize().height));
        lblStatus.setForeground(Color.DARK_GRAY);
        showStatus(lblStatus, Messages.get("search.noResults"), false);
        fixed(lblStatus);
        fixed(tglReplace);
        fixed(tglCase);
        fixed(tglWord);
        fixed(tglRegex);

        for (var b : List.of(btnReplace, btnReplaceAll)) {
            b.setMargin(new Insets(1, 8, 1, 8));
            b.setFocusable(false);
            fixed(b);
        }
        btnReplace.setToolTipText(Messages.get("search.replace.tip"));
        btnReplaceAll.setToolTipText(Messages.get("search.replaceAll.tip"));

        int width = PREFS.getInt(PREF_WIDTH, WIDTH_DEFAULT);
        findBox.setBoxWidth(width);
        replaceBox.setBoxWidth(width);
        var grip = grip();

        // Find row
        findRow.setLayout(new BoxLayout(findRow, BoxLayout.X_AXIS));
        findRow.setOpaque(false);
        findRow.add(findBox);
        findRow.add(grip);
        findRow.add(lblStatus);
        findRow.add(fixed(FlatButton.action("↑", Messages.get("search.previous"), () -> navigate(false))));
        findRow.add(fixed(FlatButton.action("↓", Messages.get("search.next"), () -> navigate(true))));
        findRow.add(javax.swing.Box.createHorizontalGlue());

        // Replace row (below, aligned with the find row)
        replaceRow.setLayout(new BoxLayout(replaceRow, BoxLayout.X_AXIS));
        replaceRow.setOpaque(false);
        replaceRow.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
        replaceRow.add(replaceBox);
        replaceRow.add(javax.swing.Box.createHorizontalStrut(grip.getPreferredSize().width));
        replaceRow.add(btnReplace);
        replaceRow.add(javax.swing.Box.createHorizontalStrut(4));
        replaceRow.add(btnReplaceAll);
        replaceRow.add(javax.swing.Box.createHorizontalGlue());

        var rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        findRow.setAlignmentX(0f);
        replaceRow.setAlignmentX(0f);
        rows.add(findRow);
        rows.add(replaceRow);

        // [›] spans the height of both rows, as in IntelliJ / VS Code
        var toggleColumn = new JPanel(new BorderLayout());
        toggleColumn.setOpaque(false);
        toggleColumn.add(tglReplace, BorderLayout.CENTER);

        widget.setOpaque(false);
        widget.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 2));
        widget.add(toggleColumn, BorderLayout.WEST);
        widget.add(rows, BorderLayout.CENTER);
    }

    /** Recomputes the fixed sizes with the final Look&Feel/fonts. */
    private void recomputeSizes() {
        for (var c : fixedComponents) {
            c.setPreferredSize(null);
            c.setMinimumSize(null);
            c.setMaximumSize(null);
            applyFixed(c);
        }
        var d = tglReplace.getPreferredSize();
        tglReplace.setMinimumSize(new Dimension(d.width, 0));
        tglReplace.setMaximumSize(new Dimension(d.width, Integer.MAX_VALUE));
        widget.revalidate();
    }

    private void registerEvents() {
        // Incremental search while typing
        txtFind.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { incremental(); }
            @Override public void removeUpdate(DocumentEvent e)  { incremental(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        for (var t : List.of(tglCase, tglWord, tglRegex)) t.addActionListener(e -> incremental());

        tglReplace.addActionListener(e -> showReplace(tglReplace.isSelected()));
        btnReplace.addActionListener(e -> replace());
        btnReplaceAll.addActionListener(e -> replaceAll());

        // Keys in the boxes
        keys(txtFind, findBox, () -> navigate(true), () -> navigate(false));
        keys(txtReplace, replaceBox, this::replace, () -> navigate(false));

        // Editor shortcuts
        shortcut(editor, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "fr.find", this::focusFind);
        shortcut(editor, KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "fr.replace", () -> {
            if (editor.isEditable()) {
                showReplace(true);
                txtReplace.requestFocusInWindow();
            } else {
                focusFind();
            }
        });
        shortcut(editor, KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "fr.next", () -> navigate(true));
        shortcut(editor, KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "fr.previous", () -> navigate(false));

        // Enable/disable replace according to the edit mode
        editor.addPropertyChangeListener("editable", e -> updateReplaceAvailability());
    }

    public void focusFind() {
        String sel = editor.getSelectedText();
        if (sel != null && !sel.isEmpty() && !sel.contains("\n")) {
            txtFind.setText(sel);
        }
        txtFind.requestFocusInWindow();
        txtFind.selectAll();
    }

    private void showReplace(boolean show) {
        show &= editor.isEditable();
        tglReplace.setSelected(show);
        tglReplace.setText(show ? "⌄" : "›");
        replaceRow.setVisible(show);
        widget.revalidate();
        widget.repaint();
    }

    private void updateReplaceAvailability() {
        boolean editable = editor.isEditable();
        tglReplace.setEnabled(editable);
        tglReplace.setToolTipText(Messages.get(editable ? "search.toggleReplace" : "search.replaceNeedsEdit"));
        if (!editable) showReplace(false);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Search
    // ─────────────────────────────────────────────────────────────────────

    private String findText() {
        return txtFind.getText();
    }

    /** Pattern according to the options; null if the regex is invalid. */
    private Pattern pattern() {
        return buildPattern(findText(), tglCase.isSelected(), tglWord.isSelected(), tglRegex.isSelected());
    }

    /** Search pattern for the Cc / W / .* options (shared with the tree search); null if the regex is invalid. */
    static Pattern buildPattern(String q, boolean matchCase, boolean wholeWord, boolean regex) {
        String p = regex ? q : Pattern.quote(q);
        if (wholeWord) p ="(?<![\\p{L}\\p{N}_])(?:" + p + ")(?![\\p{L}\\p{N}_])";
        int flags = Pattern.MULTILINE;
        if (!matchCase) flags |=Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            return Pattern.compile(p, flags);
        } catch (PatternSyntaxException ex) {
            return null;
        }
    }

    /** Matches {start, end} in the whole text (includes collapsed parts). */
    private List<int[]> matches(Pattern p) {
        var out = new ArrayList<int[]>();
        Matcher m = p.matcher(editor.getText());
        while (m.find()) {
            if (m.end() == m.start()) {        // avoid loops with empty regexes
                if (m.end() >= editor.getDocument().getLength()) break;
                continue;
            }
            out.add(new int[]{m.start(), m.end()});
        }
        return out;
    }

    /** Debounced incremental search: runs a while after the last keystroke. */
    private final Timer incrementalDelay = new Timer(Config.integer("search.delayMs"), e -> search(true, true));
    {
        incrementalDelay.setRepeats(false);
    }

    private void incremental() {
        if (silent) return;
        incrementalDelay.restart();
    }

    /**
     * Is it worth highlighting every match? Painting each highlight on a huge
     * line walks the whole line, so the limit is much lower there.
     */
    private boolean shouldMarkAll(int count) {
        if (count > MAX_MARK_ALL) return false;
        int maxLine = 0;
        var root = editor.getDocument().getDefaultRootElement();
        for (int i = 0; i < root.getElementCount(); i++) {
            var el = root.getElement(i);
            maxLine = Math.max(maxLine, el.getEndOffset() - el.getStartOffset());
        }
        return maxLine <= LONG_LINE || count <= MAX_MARK_LONG_LINE;
    }

    private void navigate(boolean forward) {
        saveHistory(HIST_FIND, findText());
        search(forward, false);
    }

    /**
     * @param fromStart incremental search: first match from the start of the
     *                  current selection (does not advance).
     */
    private void search(boolean forward, boolean fromStart) {
        String q = findText();
        markAll(null);
        if (q.isEmpty()) {
            status(Messages.get("search.noResults"), false);
            return;
        }
        Pattern p = pattern();
        if (p == null) {
            status(Messages.get("search.invalidRegex"), true);
            return;
        }
        List<int[]> hits = matches(p);
        if (hits.isEmpty()) {
            status(Messages.get("search.noResults"), true);
            return;
        }
        if (shouldMarkAll(hits.size())) markAll(q);

        int ref = editor.getSelectionStart();
        int idx;
        if (forward) {
            idx = -1;
            for (int i = 0; i < hits.size(); i++) {
                if (fromStart ? hits.get(i)[0] >= ref : hits.get(i)[0] > ref) { idx = i; break; }
            }
            if (idx < 0) idx = 0;                       // wrap to the start
        } else {
            idx = -1;
            for (int i = hits.size() - 1; i >= 0; i--) {
                if (hits.get(i)[0] < ref) { idx = i; break; }
            }
            if (idx < 0) idx = hits.size() - 1;         // wrap to the end
        }
        select(hits.get(idx));
        status(Messages.get("search.position", idx + 1, hits.size()), false);
        txtFind.requestFocusInWindow();
    }

    private void select(int[] hit) {
        editor.getFoldManager().ensureOffsetNotInClosedFold(hit[0]);
        editor.select(hit[0], hit[1]);
        editor.getCaret().setSelectionVisible(true);   // visible even with the focus on the box
        Centering.on(editor, hit[0]);                  // centered vertically and horizontally
    }

    /** Highlights every match (null = clear). */
    private void markAll(String q) {
        var ctx = new SearchContext(q == null ? "" : q, tglCase.isSelected());
        ctx.setRegularExpression(tglRegex.isSelected());
        ctx.setWholeWord(tglWord.isSelected());
        ctx.setMarkAll(q != null);
        try {
            SearchEngine.markAll(editor, ctx);
        } catch (RuntimeException ignored) { }
    }

    private void status(String text, boolean error) {
        showStatus(lblStatus, text, error && !findText().isEmpty());
        findBox.setError(error && !findText().isEmpty());
    }

    /** "No results" is shown as an icon (with the text as tooltip); counts like "3 of 12" as text. */
    static void showStatus(JLabel label, String text, boolean error) {
        boolean none = text.equals(Messages.get("search.noResults"));
        var icon = none ? new NoResultsIcon(error) : null;
        label.setIcon(icon);
        label.setDisabledIcon(icon);       // drawn icons are not painted on disabled labels otherwise
        label.setText(none ? "" : text);
        label.setToolTipText(none ? text : null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Replace
    // ─────────────────────────────────────────────────────────────────────

    private String replaceText() {
        return txtReplace.getText();
    }

    /** Replaces the selected match (if it is one) and moves to the next one. */
    private void replace() {
        if (!editor.isEditable()) return;
        Pattern p = pattern();
        if (p == null || findText().isEmpty()) return;
        saveHistory(HIST_FIND, findText());
        saveHistory(HIST_REPLACE, replaceText());

        int s = editor.getSelectionStart(), e = editor.getSelectionEnd();
        boolean selectionIsMatch = matches(p).stream().anyMatch(h -> h[0] == s && h[1] == e);
        if (selectionIsMatch) {
            String sel = editor.getSelectedText();
            String replacement = tglRegex.isSelected()
                    ? p.matcher(sel).replaceFirst(replaceText())
                    : replaceText();
            editor.replaceSelection(replacement);
            editor.select(s, s + replacement.length());   // the next search starts here
        }
        search(true, false);
    }

    private void replaceAll() {
        if (!editor.isEditable()) return;
        Pattern p = pattern();
        if (p == null || findText().isEmpty()) return;
        saveHistory(HIST_FIND, findText());
        saveHistory(HIST_REPLACE, replaceText());

        int n = matches(p).size();
        if (n == 0) {
            status(Messages.get("search.noResults"), true);
            return;
        }
        String repl = tglRegex.isSelected() ? replaceText() : Matcher.quoteReplacement(replaceText());
        String result;
        try {
            result = p.matcher(editor.getText()).replaceAll(repl);
        } catch (RuntimeException ex) {          // e.g. a non-existent $3
            status(Messages.get("search.invalidReplacement"), true);
            return;
        }
        int caret = Math.min(editor.getCaretPosition(), result.length());
        editor.beginAtomicEdit();
        try {
            editor.getDocument().remove(0, editor.getDocument().getLength());
            editor.getDocument().insertString(0, result, null);
        } catch (BadLocationException ignored) {
        } finally {
            editor.endAtomicEdit();
        }
        editor.setCaretPosition(caret);
        markAll(null);
        status(Messages.get("search.replaced", n), false);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Persistent history
    // ─────────────────────────────────────────────────────────────────────

    static List<String> loadHistory(String key) {
        String raw = PREFS.get(key, "");
        var out = new ArrayList<String>();
        if (!raw.isEmpty()) {
            for (String s : raw.split(String.valueOf(SEP))) if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** Puts the text at the top of the history (no duplicates) and persists it. */
    static void saveHistory(String key, String text) {
        if (text == null || text.isEmpty()) return;
        var list = loadHistory(key);       // reload: other tabs may have added entries
        list.remove(text);
        list.add(0, text);
        while (list.size() > MAX_HISTORY) list.remove(list.size() - 1);
        PREFS.put(key, String.join(String.valueOf(SEP), list));
    }

    /** Shows the history under the box; picking an entry sets the text (and searches, for the find box). */
    private void showHistory(Box box) {
        var list = loadHistory(box.historyKey);
        var menu = new JPopupMenu();
        if (list.isEmpty()) {
            var empty = new JMenuItem(Messages.get("search.noHistory"));
            empty.setEnabled(false);
            menu.add(empty);
        }
        for (String h : list) {
            var item = new JMenuItem(h.length() > MAX_HISTORY_ITEM_TEXT ? h.substring(0, MAX_HISTORY_ITEM_TEXT) + "…" : h);
            item.setToolTipText(h);
            item.addActionListener(e -> {
                box.field.setText(h);
                box.field.requestFocusInWindow();
                if (box == findBox) search(true, true);
            });
            menu.add(item);
        }
        menu.show(box, 0, box.getHeight());
    }

    /**
     * "⋮" grip dragged horizontally to change the width of the find and
     * replace boxes (the width is remembered across runs).
     */
    private JComponent grip() {
        var g = new JLabel("⋮");
        g.setForeground(Color.GRAY);
        g.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 5));
        g.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
        g.setToolTipText(Messages.get("search.resize.tip"));
        var mouse = new MouseAdapter() {
            int x0, width0;
            @Override public void mousePressed(MouseEvent e) {
                x0 = e.getXOnScreen();
                width0 = findBox.getWidth();
            }
            @Override public void mouseDragged(MouseEvent e) {
                int width = Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, width0 + e.getXOnScreen() - x0));
                findBox.setBoxWidth(width);
                replaceBox.setBoxWidth(width);
                widget.revalidate();
            }
            @Override public void mouseReleased(MouseEvent e) {
                PREFS.putInt(PREF_WIDTH, findBox.getWidth());
            }
        };
        g.addMouseListener(mouse);
        g.addMouseMotionListener(mouse);
        return g;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────

    private void keys(JTextField field, Box box, Runnable enter, Runnable shiftEnter) {
        shortcut(field, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "fr.enter", enter);
        shortcut(field, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "fr.shiftEnter", shiftEnter);
        shortcut(field, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "fr.esc", () -> {
            markAll(null);
            editor.requestFocusInWindow();
        });
        shortcut(field, KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "fr.next", () -> navigate(true));
        shortcut(field, KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "fr.previous", () -> navigate(false));
        shortcut(field, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "fr.history", () -> showHistory(box));
    }

    private static void shortcut(JComponent c, KeyStroke ks, String name, Runnable r) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(ks, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    /** Registers a fixed-size component (the toolbar's BoxLayout does not shrink it). */
    private <T extends JComponent> T fixed(T c) {
        fixedComponents.add(c);
        applyFixed(c);
        return c;
    }

    static void applyFixed(JComponent c) {
        var d = c.getPreferredSize();
        d = new Dimension(d.width + 2, d.height);   // margin: the border may vary
        c.setPreferredSize(d);
        c.setMinimumSize(d);
        c.setMaximumSize(d);
    }

    // ─────────────────────────────────────────────────────────────────────
    // IntelliJ-style compound text box: [🔍▾ | text | options]
    // ─────────────────────────────────────────────────────────────────────

    private final class Box extends JPanel {
        final JTextField field;
        final String historyKey;

        Box(JTextField field, String historyKey, String tip, JComponent... options) {
            super(new BorderLayout(2, 0));
            this.field = field;
            this.historyKey = historyKey;
            setBackground(UIManager.getColor("TextField.background"));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BOX_BORDER),
                    BorderFactory.createEmptyBorder(1, 2, 1, 2)));

            var history = FlatButton.action("", Messages.get("search.history"), () -> showHistory(this));
            history.setIcon(new HistoryMagnifierIcon());
            history.setMargin(new Insets(0, 2, 0, 2));

            field.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
            field.setToolTipText(tip + "  " + Messages.get("search.keys"));
            field.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { repaintBorder(); }
                @Override public void focusLost(FocusEvent e)   { repaintBorder(); }
            });

            add(history, BorderLayout.WEST);
            add(field, BorderLayout.CENTER);
            if (options.length > 0) {
                var ops = new JPanel(new FlowLayout(FlowLayout.RIGHT, 1, 0));
                ops.setOpaque(false);
                for (var o : options) ops.add(o);
                add(ops, BorderLayout.EAST);
            }
        }

        void setBoxWidth(int width) {
            setPreferredSize(null);
            int h = Math.max(24, super.getPreferredSize().height);
            var d = new Dimension(width, h);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
            revalidate();
        }

        void setError(boolean error) {
            var bg = error ? ERROR_BG : UIManager.getColor("TextField.background");
            setBackground(bg);
            field.setBackground(bg);
            repaint();
        }

        private void repaintBorder() {
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(field.hasFocus() ? FOCUS_BORDER : BOX_BORDER),
                    BorderFactory.createEmptyBorder(1, 2, 1, 2)));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Flat button (IntelliJ style): no relief; filled only when active/hovered
    // ─────────────────────────────────────────────────────────────────────

    static final class FlatButton extends JToggleButton {
        private final boolean isToggle;

        private FlatButton(String text, String tip, boolean isToggle) {
            super(text);
            this.isToggle = isToggle;
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

        static FlatButton toggle(String text, String tip) {
            return new FlatButton(text, tip, true);
        }

        /** Flat action button (does not stay pressed). */
        static JButton action(String text, String tip, Runnable r) {
            var b = new JButton(text) {
                /** Short texts (↑ ↓ ✕ ⧉) get a compact width instead of the Look&Feel's minimum. */
                @Override public Dimension getPreferredSize() {
                    var d = super.getPreferredSize();
                    String t = getText();
                    if (getIcon() == null && t != null && !t.isEmpty() && t.length() <= 2) {
                        var in = getInsets();   // margin + border: the text must fit, or it shows as "…"
                        int w = getFontMetrics(getFont()).stringWidth(t) + in.left + in.right + 4;
                        d = new Dimension(Math.min(d.width, w), d.height);
                    }
                    return d;
                }
                @Override protected void paintComponent(Graphics g) {
                    paintBackground(this, (Graphics2D) g, false, getModel().isRollover(), getModel().isPressed());
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
            paintBackground(this, (Graphics2D) g, isToggle && isSelected(), getModel().isRollover(), getModel().isPressed());
            super.paintComponent(g);
        }

        private static void paintBackground(AbstractButton b, Graphics2D g, boolean active, boolean hover, boolean pressed) {
            if (!b.isEnabled()) return;
            if (!active && !hover && !pressed) return;
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = b.getWidth() - 1, h = b.getHeight() - 1;
            g2.setColor(active ? ACTIVE_BG : HOVER_BG);
            g2.fillRoundRect(0, 0, w, h, 6, 6);
            if (active) {
                g2.setColor(ACTIVE_BORDER);
                g2.drawRoundRect(0, 0, w, h, 6, 6);
            }
            g2.dispose();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Drawn magnifier icon with ▾ (opens the history)
    // ─────────────────────────────────────────────────────────────────────

    static final class HistoryMagnifierIcon implements Icon {
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
