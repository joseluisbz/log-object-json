package com.logobjectjson.ui;

import com.logobjectjson.Messages;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Main window: a JTabbedPane of SESSIONS. Each session ({@link SessionPanel})
 * has its own data source, tree and JSON, so several files/logs can be worked
 * on at the same time in the same application.
 *
 *   • "+" tab            → new session (also Ctrl+T).
 *   • ✕ on each tab      → close the session (also Ctrl+W).
 *   • Tab title          → name of the loaded file, or "Pasted text".
 */
public class MainFrame extends JFrame {

    private final JTabbedPane sessions = new JTabbedPane();
    private final JPanel newTab = new JPanel();
    private int counter;
    /** Avoids creating sessions while rearranging tabs from code. */
    private boolean adjusting;

    public MainFrame() {
        this(List.of());
    }

    /** @param restore Root texts to restore, one per session (e.g. after a language change) */
    public MainFrame(List<TrioPanel.Snapshot> restore) {
        super(Messages.get("app.title"));

        // "+" tab: selecting it creates a new session
        sessions.addTab("+", newTab);
        sessions.setToolTipTextAt(0, Messages.get("session.new.tip"));
        sessions.addChangeListener(e -> {
            if (!adjusting && sessions.getSelectedComponent() == newTab) newSession();
        });

        if (restore.isEmpty()) newSession();
        else restore.forEach(s -> newSession().restoreRoot(s));
        if (sessions.getTabCount() > 1) sessions.setSelectedIndex(0);
        setContentPane(sessions);
        setJMenuBar(languageBar());

        shortcut(KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK), "session.new", this::newSession);
        shortcut(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), "session.close", () -> {
            if (sessions.getSelectedComponent() instanceof SessionPanel s) closeSession(s);
        });

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1300, 820);
        setMinimumSize(new Dimension(780, 580));
        setLocationRelativeTo(null);
    }

    /** Thin bar with the language selector (flag + name) on the right. */
    private JMenuBar languageBar() {
        var combo = new JComboBox<>(Messages.languages().toArray(String[]::new));
        combo.setSelectedItem(Messages.language());
        combo.setFocusable(false);
        combo.setToolTipText(Messages.get("lang.tip"));
        combo.setMaximumSize(combo.getPreferredSize());
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                String lang = String.valueOf(value);
                setIcon(new FlagIcon(lang));
                setText(Messages.in(lang, "lang.name"));
                setIconTextGap(6);
                return this;
            }
        });
        combo.setMaximumSize(new Dimension(combo.getPreferredSize().width + 30, combo.getPreferredSize().height));
        combo.addActionListener(e -> {
            String lang = (String) combo.getSelectedItem();
            if (lang != null && !lang.equals(Messages.language())) SwingUtilities.invokeLater(() -> switchLanguage(lang));
        });
        var bar = new JMenuBar();
        bar.add(Box.createHorizontalGlue());
        bar.add(combo);
        bar.add(Box.createHorizontalStrut(4));
        return bar;
    }

    /**
     * Changes the UI language: rebuilds the window in the new language, keeping
     * each session's Root text and loaded file (and the window bounds).
     */
    public MainFrame switchLanguage(String lang) {
        var snapshots = new ArrayList<TrioPanel.Snapshot>();
        for (int i = 0; i < sessions.getTabCount(); i++) {
            if (sessions.getComponentAt(i) instanceof SessionPanel s) snapshots.add(s.root().snapshot());
        }
        Messages.setLanguage(lang);
        var frame = new MainFrame(snapshots);
        frame.setBounds(getBounds());
        frame.setExtendedState(getExtendedState());
        frame.setVisible(true);
        dispose();
        return frame;
    }

    /** Creates an empty session before the "+" tab and selects it. */
    public SessionPanel newSession() {
        adjusting = true;
        try {
            String initial = Messages.get("session.title", ++counter);
            var title = new JLabel(initial);
            var session = new SessionPanel(t -> {
                title.setText(t);
                int i = indexOf(title);
                if (i >= 0) sessions.setToolTipTextAt(i, t);
            });
            int idx = sessions.indexOfComponent(newTab);
            sessions.insertTab(initial, null, session, initial, idx);
            sessions.setTabComponentAt(idx, header(title, session));
            sessions.setSelectedIndex(idx);
            return session;
        } finally {
            adjusting = false;
        }
    }

    private void closeSession(SessionPanel session) {
        adjusting = true;
        try {
            int idx = sessions.indexOfComponent(session);
            if (idx < 0) return;
            sessions.remove(idx);
            if (sessions.getTabCount() == 1) {       // only "+" is left: always keep one session
                adjusting = false;
                newSession();
                return;
            }
            sessions.setSelectedIndex(Math.max(0, Math.min(idx, sessions.getTabCount() - 2)));
        } finally {
            adjusting = false;
        }
    }

    private JComponent header(JLabel title, SessionPanel session) {
        var panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        title.setIcon(new FolderIcon(14));
        var close = new JButton("✕");
        close.setMargin(new Insets(0, 2, 0, 2));
        close.setBorderPainted(false);
        close.setContentAreaFilled(false);
        close.setFocusable(false);
        close.setToolTipText(Messages.get("session.close.tip"));
        close.addActionListener(e -> closeSession(session));
        panel.add(title);
        panel.add(close);
        return panel;
    }

    private int indexOf(JLabel title) {
        for (int i = 0; i < sessions.getTabCount(); i++) {
            if (sessions.getTabComponentAt(i) instanceof JPanel p && p.isAncestorOf(title)) return i;
        }
        return -1;
    }

    private void shortcut(KeyStroke ks, String name, Runnable r) {
        var root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }
}
