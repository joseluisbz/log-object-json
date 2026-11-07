package com.logobjectjson.ui;

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
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;

/**
 * Tree search controls, with the same look as the editors' find box:
 *
 *   [🔍 text                    Cc ]  3 / 12  ↑  ↓
 *
 *  • Enter = next, Shift+Enter = previous, Esc = clear.
 *  • Typing searches from the start (incremental search).
 *  • The box stretches with the available width (BoxLayout).
 *
 * A transparent JPanel so it can be embedded in a JToolBar.
 */
public class TreeSearchBar extends JPanel {

    private static final Color ERROR_BG     = new Color(255, 225, 225);
    private static final Color BOX_BORDER   = new Color(190, 190, 190);
    private static final Color FOCUS_BORDER = new Color(60, 120, 220);
    private static final int BOX_HEIGHT = 24;

    /** Search logic: returns the status text (e.g. "3 / 12"). */
    @FunctionalInterface
    public interface Searcher {
        String search(String text, boolean forward, boolean fromStart, boolean matchCase);
    }

    private final JTextField txt = new JTextField(14);
    private final FindReplacePanel.FlatButton tglCase =
            FindReplacePanel.FlatButton.toggle(Messages.get("search.matchCase.short"), Messages.get("search.matchCase"));
    private final JLabel lblStatus = new JLabel(" ");
    private final JPanel box = new JPanel(new BorderLayout(2, 0));
    private final Searcher searcher;

    public TreeSearchBar(String placeholder, Searcher searcher) {
        this.searcher = searcher;
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setOpaque(false);

        // Compound box: [🔍 | text | Cc]
        var magnifier = new JLabel(new MagnifierIcon());
        magnifier.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 1));
        magnifier.setToolTipText(placeholder);
        txt.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        txt.setToolTipText(placeholder + "  " + Messages.get("treeSearch.keys"));
        txt.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { paintBorder(); }
            @Override public void focusLost(FocusEvent e)   { paintBorder(); }
        });
        box.setBackground(UIManager.getColor("TextField.background"));
        paintBorder();
        box.add(magnifier, BorderLayout.WEST);
        box.add(txt, BorderLayout.CENTER);
        var ops = new JPanel(new FlowLayout(FlowLayout.RIGHT, 1, 0));
        ops.setOpaque(false);
        ops.add(tglCase);
        box.add(ops, BorderLayout.EAST);
        box.setMinimumSize(new Dimension(120, BOX_HEIGHT));
        box.setPreferredSize(new Dimension(260, BOX_HEIGHT));
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, BOX_HEIGHT));

        lblStatus.setForeground(Color.DARK_GRAY);
        lblStatus.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
        lblStatus.setPreferredSize(new Dimension(70, BOX_HEIGHT));
        lblStatus.setMinimumSize(new Dimension(50, BOX_HEIGHT));
        lblStatus.setMaximumSize(new Dimension(70, BOX_HEIGHT));

        var btnPrev = FindReplacePanel.FlatButton.action("↑", Messages.get("treeSearch.previous"), () -> run(false, false));
        var btnNext = FindReplacePanel.FlatButton.action("↓", Messages.get("treeSearch.next"), () -> run(true, false));

        txt.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { run(true, true); }
            @Override public void removeUpdate(DocumentEvent e)  { run(true, true); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        tglCase.addActionListener(e -> run(true, true));

        var im = txt.getInputMap(JComponent.WHEN_FOCUSED);
        var am = txt.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "next");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "previous");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear");
        am.put("next", action(() -> run(true, false)));
        am.put("previous", action(() -> run(false, false)));
        am.put("clear", action(() -> txt.setText("")));

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

    private void run(boolean forward, boolean fromStart) {
        String t = txt.getText();
        String status = searcher.search(t, forward, fromStart, tglCase.isSelected());
        lblStatus.setText(status == null || status.isEmpty() ? " " : status);
        boolean noResults = !t.isEmpty() && status != null && status.startsWith("0");
        var bg = noResults ? ERROR_BG : UIManager.getColor("TextField.background");
        box.setBackground(bg);
        txt.setBackground(bg);
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

    /** Drawn magnifier (same stroke as the editors' find box). */
    private static final class MagnifierIcon implements Icon {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            g2.setColor(new Color(90, 90, 90));
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(new Ellipse2D.Double(1.5, 2.5, 8, 8));
            g2.draw(new Line2D.Double(8.6, 9.6, 12, 13));
            g2.dispose();
        }
        @Override public int getIconWidth()  { return 14; }
        @Override public int getIconHeight() { return 16; }
    }
}
