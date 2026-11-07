package com.logobjectjson.ui;

import com.logobjectjson.Messages;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Shows any uncaught exception (from any thread, including the EDT) in a
 * dialog with the full, copyable stack trace, besides printing it to the
 * console (stderr).
 */
public final class ErrorDialog {

    private ErrorDialog() { }

    /** Installs the global handler. Call once at startup. */
    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            ex.printStackTrace();
            show(null, Messages.get("error.uncaught", thread.getName()), ex);
        });
    }

    /** Shows the dialog with the stack trace (may be called from any thread). */
    public static void show(Component parent, String title, Throwable ex) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> show(parent, title, ex));
            return;
        }
        var sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();

        var area = new JTextArea(trace, 18, 90);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);

        var btnCopy = new JButton(Messages.get("error.copyTrace"));
        btnCopy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(trace), null));

        var panel = new JPanel(new BorderLayout(0, 6));
        var msg = new JLabel("<html><b>" + escape(String.valueOf(ex)) + "</b></html>");
        panel.add(msg, BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        var south = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        south.add(btnCopy);
        panel.add(south, BorderLayout.SOUTH);

        JOptionPane.showMessageDialog(parent, panel, title, JOptionPane.ERROR_MESSAGE);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
