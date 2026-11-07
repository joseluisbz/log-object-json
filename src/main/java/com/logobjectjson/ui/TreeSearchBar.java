package com.logobjectjson.ui;

import com.logobjectjson.Messages;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Reusable search controls: 🔍 [text] ▲ ▼ Aa status.
 *  • Enter = next, Shift+Enter = previous, Esc = clear.
 *  • Typing searches from the start (incremental search).
 *  • The text box stretches with the available width (BoxLayout).
 *
 * A transparent JPanel so it can be embedded in a JToolBar.
 */
public class TreeSearchBar extends JPanel {

    private static final Color NO_RESULTS_BG = new Color(255, 225, 225);

    /** Search logic: returns the status text (e.g. "3 / 12"). */
    @FunctionalInterface
    public interface Searcher {
        String search(String text, boolean forward, boolean fromStart, boolean matchCase);
    }

    private final JTextField txt = new JTextField(14);
    private final JCheckBox chkCase = new JCheckBox(Messages.get("search.matchCase.short"));
    private final JLabel lblStatus = new JLabel(" ");
    private final Searcher searcher;

    public TreeSearchBar(String placeholder, Searcher searcher) {
        this.searcher = searcher;
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setOpaque(false);

        txt.setToolTipText(placeholder + "  " + Messages.get("treeSearch.keys"));
        txt.setMinimumSize(new Dimension(80, txt.getPreferredSize().height));
        txt.setMaximumSize(new Dimension(Integer.MAX_VALUE, txt.getPreferredSize().height));
        chkCase.setToolTipText(Messages.get("search.matchCase"));
        chkCase.setOpaque(false);
        chkCase.setFocusable(false);
        lblStatus.setMinimumSize(new Dimension(70, 1));
        lblStatus.setPreferredSize(new Dimension(80, txt.getPreferredSize().height));

        var btnPrev = button("▲", Messages.get("treeSearch.previous"), () -> run(false, false));
        var btnNext = button("▼", Messages.get("treeSearch.next"), () -> run(true, false));

        txt.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { run(true, true); }
            @Override public void removeUpdate(DocumentEvent e)  { run(true, true); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        chkCase.addActionListener(e -> run(true, true));

        var im = txt.getInputMap(JComponent.WHEN_FOCUSED);
        var am = txt.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "next");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "previous");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear");
        am.put("next", action(() -> run(true, false)));
        am.put("previous", action(() -> run(false, false)));
        am.put("clear", action(() -> txt.setText("")));

        var magnifier = new JLabel("🔍");
        magnifier.setToolTipText(placeholder);

        add(magnifier);
        add(Box.createHorizontalStrut(4));
        add(txt);
        add(Box.createHorizontalStrut(3));
        add(btnPrev);
        add(btnNext);
        add(Box.createHorizontalStrut(3));
        add(chkCase);
        add(Box.createHorizontalStrut(6));
        add(lblStatus);
    }

    /** Moves the focus to the search field (e.g. with Ctrl+F). */
    public void focus() {
        txt.requestFocusInWindow();
        txt.selectAll();
    }

    private void run(boolean forward, boolean fromStart) {
        String t = txt.getText();
        String status = searcher.search(t, forward, fromStart, chkCase.isSelected());
        lblStatus.setText(status == null || status.isEmpty() ? " " : status);
        boolean noResults = !t.isEmpty() && status != null && status.startsWith("0");
        txt.setBackground(noResults ? NO_RESULTS_BG : UIManager.getColor("TextField.background"));
    }

    private static JButton button(String text, String tip, Runnable r) {
        var b = new JButton(text);
        b.setMargin(new Insets(1, 5, 1, 5));
        b.setFocusable(false);
        b.setToolTipText(tip);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static Action action(Runnable r) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        };
    }
}
