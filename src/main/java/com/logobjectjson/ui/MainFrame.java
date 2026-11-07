package com.logobjectjson.ui;

import com.logobjectjson.Messages;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

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
        super(Messages.get("app.title"));

        // "+" tab: selecting it creates a new session
        sessions.addTab("+", newTab);
        sessions.setToolTipTextAt(0, Messages.get("session.new.tip"));
        sessions.addChangeListener(e -> {
            if (!adjusting && sessions.getSelectedComponent() == newTab) newSession();
        });

        newSession();
        setContentPane(sessions);

        shortcut(KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK), "session.new", this::newSession);
        shortcut(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), "session.close", () -> {
            if (sessions.getSelectedComponent() instanceof SessionPanel s) closeSession(s);
        });

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1300, 820);
        setMinimumSize(new Dimension(780, 580));
        setLocationRelativeTo(null);
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
