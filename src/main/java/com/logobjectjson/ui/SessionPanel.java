package com.logobjectjson.ui;

import com.logobjectjson.Messages;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * A session (one window tab). Holds a JTabbedPane of text → tree → JSON
 * "trios" ({@link TrioPanel}):
 *
 *   [⬡ Root]  [branch ✕]  [branch ✕] …
 *
 *  • Root: the loaded or pasted text. Cannot be closed.
 *  • Branches: created with a right click on a node → "Analyze this node in a new tab".
 *    Each has its own text (only the branch's part), tree, JSON and buttons,
 *    independent of Root. They can be closed without affecting anything.
 *  • Branches can also be extracted from a branch (they open at this same level).
 */
public class SessionPanel extends JPanel {

    private static final int MAX_TAB_TITLE = 40;

    private final JTabbedPane trios = new JTabbedPane();
    private final TrioPanel main;
    private JToggleButton compareToggle;

    /**
     * @param onTitleChange reports the session title (loaded file or "Pasted text")
     */
    public SessionPanel(Consumer<String> onTitleChange) {
        super(new BorderLayout());
        main = new TrioPanel(onTitleChange, this::openBranch);

        trios.addTab(Messages.get("trio.root"), TreeIcons.OBJECT, main, Messages.get("trio.root.tip"));
        trios.setTabComponentAt(0, rootHeader());
        add(trios, BorderLayout.CENTER);
    }

    /**
     * Root tab header: "⬡ Root  ⇄". The ⇄ button (raised on hover, pressed while
     * comparing) turns the comparison mode on and off.
     */
    private JComponent rootHeader() {
        var panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        // CAREFUL: no tooltip on the label (it would capture the clicks and the tab could not be selected)
        var title = new JLabel(Messages.get("trio.root"), TreeIcons.OBJECT, SwingConstants.LEFT);
        var compare = FindReplacePanel.FlatButton.toggle("⇄", Messages.get("compare.toggle.tip"));
        compareToggle = compare;
        compare.setMargin(new Insets(0, 4, 0, 4));
        compare.addActionListener(e -> {
            main.setComparing(compare.isSelected());
            trios.setSelectedComponent(main);
        });
        panel.add(title);
        panel.add(compare);
        return panel;
    }

    /** Restores the Root trio (and its comparison, if it was active). */
    public void restoreRoot(TrioPanel.Snapshot s) {
        main.restore(s);
        compareToggle.setSelected(main.isComparing());
    }

    /** The Root trio (loaded or pasted text). */
    public TrioPanel root() {
        return main;
    }

    /** Opens an extracted branch in a new (closable) tab and selects it. */
    private void openBranch(String title, String path, String text) {
        var label = new JLabel(shorten(title), TreeIcons.OBJECT, SwingConstants.LEFT);
        // CAREFUL: no tooltip on the label: a component with a tooltip captures the clicks
        // and the tab could no longer be selected. The tooltip goes on the tab.
        var branch = new TrioPanel(t -> label.setText(shorten(t)), this::openBranch);

        trios.addTab(shorten(title), branch);
        int idx = trios.indexOfComponent(branch);
        trios.setToolTipTextAt(idx, path);
        trios.setTabComponentAt(idx, header(label, branch));
        trios.setSelectedIndex(idx);
        branch.loadText(text);
    }

    private JComponent header(JLabel title, TrioPanel branch) {
        var panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        var close = new JButton("✕");
        close.setMargin(new Insets(0, 2, 0, 2));
        close.setBorderPainted(false);
        close.setContentAreaFilled(false);
        close.setFocusable(false);
        close.setToolTipText(Messages.get("trio.branch.close.tip"));
        close.addActionListener(e -> trios.remove(branch));
        panel.add(title);
        panel.add(close);
        return panel;
    }

    private static String shorten(String t) {
        return t.length() > MAX_TAB_TITLE ? t.substring(0, MAX_TAB_TITLE) + "…" : t;
    }
}
