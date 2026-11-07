package com.dynamicparser.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Muestra cualquier excepción no capturada (de cualquier hilo, incluida la EDT)
 * en un diálogo con la traza completa (stack trace) copiable, además de
 * imprimirla en la consola (stderr).
 */
public final class DialogoErrores {

    private DialogoErrores() { }

    /** Instala el manejador global. Llamar una vez al iniciar la app. */
    public static void instalar() {
        Thread.setDefaultUncaughtExceptionHandler((hilo, ex) -> {
            ex.printStackTrace();
            mostrar(null, "Error no controlado en el hilo «" + hilo.getName() + "»", ex);
        });
    }

    /** Muestra el diálogo con la traza (se puede llamar desde cualquier hilo). */
    public static void mostrar(Component padre, String titulo, Throwable ex) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> mostrar(padre, titulo, ex));
            return;
        }
        var sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        String traza = sw.toString();

        var area = new JTextArea(traza, 18, 90);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);

        var btnCopiar = new JButton("📋 Copiar traza");
        btnCopiar.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(traza), null));

        var panel = new JPanel(new BorderLayout(0, 6));
        var msg = new JLabel("<html><b>" + escapar(String.valueOf(ex)) + "</b></html>");
        panel.add(msg, BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        var sur = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        sur.add(btnCopiar);
        panel.add(sur, BorderLayout.SOUTH);

        JOptionPane.showMessageDialog(padre, panel, titulo, JOptionPane.ERROR_MESSAGE);
    }

    private static String escapar(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
