package com.logobjectjson.ui;

import com.logobjectjson.Config;
import com.logobjectjson.Messages;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tree search, with the same look and options as the editors' find box
 * (there is no replace: the tree is not edited):
 *
 *   [🔍▾ text               Cc  W  .* ]  No results  ↑  ↓
 *
 *  • Cc = match case, W = whole word, .* = regular expression.
 *  • 🔍▾ (or Alt+↓) opens the persistent search history.
 *  • Enter = next, Shift+Enter = previous, Esc = clear; typing searches from the start.
 */
public class TreeSearchBar extends JPanel {

    private static final String HISTORY_KEY = "history.tree";
    private static final Color ERROR_BG     = new Color(255, 225, 225);
    private static final Color BOX_BORDER   = new Color(190, 190, 190);
    private static final Color FOCUS_BORDER = new Color(60, 120, 220);
    private static final int BOX_HEIGHT = 24;
    private static final int MAX_HISTORY_ITEM_TEXT = 80;

    /**
     * Search logic: receives the pattern for the current options (never null)
     * and returns the status text: "3 / 12", or empty/null if nothing matched.
     */
    @FunctionalInterface
    public interface Searcher {
        String search(Pattern pattern, boolean forward, boolean fromStart);
    }

    private final JTextField txt = new JTextField(14);
    private final FindReplacePanel.FlatButton tglCase =
            FindReplacePanel.FlatButton.toggle(Messages.get("search.matchCase.short"), Messages.get("search.matchCase"));
    private final FindReplacePanel.FlatButton tglWord =
            FindReplacePanel.FlatButton.toggle("W", Messages.get("search.wholeWord"));
    private final FindReplacePanel.FlatButton tglRegex =
            FindReplacePanel.FlatButton.toggle(".*", Messages.get("search.regex"));
    private final JLabel lblStatus = new JLabel(Messages.get("search.noResults"));
    private final JPanel box = new JPanel(new BorderLayout(2, 0));
    private final Searcher searcher;

    /** Debounced incremental search (same delay as the editors). */
    private final Timer incremental = new Timer(Config.integer("search.delayMs"), e -> run(true, true));

    public TreeSearchBar(String placeholder, Searcher searcher) {
        this.searcher = searcher;
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setOpaque(false);
        incremental.setRepeats(false);

        // Compound box: [🔍▾ | text | Cc W .*]
        var history = FindReplacePanel.FlatButton.action("", Messages.get("search.history"), this::showHistory);
        history.setIcon(new FindReplacePanel.HistoryMagnifierIcon());
        history.setMargin(new Insets(0, 2, 0, 2));
        txt.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        txt.setToolTipText(placeholder + "  " + Messages.get("search.keys"));
        txt.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { paintBorder(); }
            @Override public void focusLost(FocusEvent e)   { paintBorder(); }
        });
        box.setBackground(UIManager.getColor("TextField.background"));
        paintBorder();
        box.add(history, BorderLayout.WEST);
        box.add(txt, BorderLayout.CENTER);
        var ops = new JPanel(new FlowLayout(FlowLayout.RIGHT, 1, 0));
        ops.setOpaque(false);
        ops.add(tglCase);
        ops.add(tglWord);
        ops.add(tglRegex);
        box.add(ops, BorderLayout.EAST);
        box.setMinimumSize(new Dimension(160, BOX_HEIGHT));
        box.setPreferredSize(new Dimension(300, BOX_HEIGHT));
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, BOX_HEIGHT));

        lblStatus.setForeground(Color.DARK_GRAY);
        lblStatus.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
        var statusSize = new Dimension(95, BOX_HEIGHT);
        lblStatus.setPreferredSize(statusSize);
        lblStatus.setMinimumSize(statusSize);
        lblStatus.setMaximumSize(statusSize);

        var btnPrev = FindReplacePanel.FlatButton.action("↑", Messages.get("treeSearch.previous"), () -> navigate(false));
        var btnNext = FindReplacePanel.FlatButton.action("↓", Messages.get("treeSearch.next"), () -> navigate(true));

        txt.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { incremental.restart(); }
            @Override public void removeUpdate(DocumentEvent e)  { incremental.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        for (var t : List.of(tglCase, tglWord, tglRegex)) t.addActionListener(e -> run(true, true));

        var im = txt.getInputMap(JComponent.WHEN_FOCUSED);
        var am = txt.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "next");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "previous");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "history");
        am.put("next", action(() -> navigate(true)));
        am.put("previous", action(() -> navigate(false)));
        am.put("clear", action(() -> txt.setText("")));
        am.put("history", action(this::showHistory));

        add(box);
        add(lblStatus);
        add(btnPrev);
        add(btnNext);
    }

    /** Moves the focus to the search field (e.g. with Ctrl+F). */
    public void focus() {
        txt.requestFocusInWindow();
        txt.selectAll();
    }

    private void navigate(boolean forward) {
        FindReplacePanel.saveHistory(HISTORY_KEY, txt.getText());
        run(forward, false);
    }

    private void run(boolean forward, boolean fromStart) {
        String q = txt.getText();
        if (q.isEmpty()) {
            status(Messages.get("search.noResults"), false);
            return;
        }
        Pattern p = FindReplacePanel.buildPattern(q, tglCase.isSelected(), tglWord.isSelected(), tglRegex.isSelected());
        if (p == null) {
            status(Messages.get("search.invalidRegex"), true);
            return;
        }
        String result = searcher.search(p, forward, fromStart);
        boolean none = result == null || result.isEmpty();
        status(none ? Messages.get("search.noResults") : result, none);
    }

    private void status(String text, boolean error) {
        lblStatus.setText(text);
        var bg = error ? ERROR_BG : UIManager.getColor("TextField.background");
        box.setBackground(bg);
        txt.setBackground(bg);
    }

    /** Shows the history under the box; picking an entry searches it. */
    private void showHistory() {
        var list = FindReplacePanel.loadHistory(HISTORY_KEY);
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
                txt.setText(h);
                txt.requestFocusInWindow();
                run(true, true);
            });
            menu.add(item);
        }
        menu.show(box, 0, box.getHeight());
    }

    private void paintBorder() {
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(txt.hasFocus() ? FOCUS_BORDER : BOX_BORDER),
                BorderFactory.createEmptyBorder(1, 2, 1, 2)));
    }

    private static Action action(Runnable r) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        };
    }
}
