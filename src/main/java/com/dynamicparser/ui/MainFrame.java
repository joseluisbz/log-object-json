package com.dynamicparser.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Ventana principal: un JTabbedPane de SESIONES. Cada sesión ({@link SesionPanel})
 * tiene su propia fuente de datos, árbol y serializaciones JSON, de modo que se
 * pueden trabajar varios archivos/logs a la vez en la misma aplicación.
 *
 *   • Pestaña "+"           → nueva sesión (también Ctrl+T).
 *   • ✕ en cada pestaña      → cerrar la sesión (también Ctrl+W).
 *   • Título de la pestaña  → nombre del archivo cargado, o "Texto pegado".
 */
public class MainFrame extends JFrame {

    private final JTabbedPane sesiones = new JTabbedPane();
    private final JPanel tabNueva = new JPanel();
    private int contador;
    /** Evita crear sesiones al reorganizar pestañas por código. */
    private boolean ajustando;

    public MainFrame() {
        super("Parser de Objetos Dinámico — Instanciación sin clase fija");

        // Pestaña "+": al seleccionarla se crea una sesión nueva
        sesiones.addTab("+", tabNueva);
        sesiones.setToolTipTextAt(0, "Nueva sesión (Ctrl+T)");
        sesiones.addChangeListener(e -> {
            if (!ajustando && sesiones.getSelectedComponent() == tabNueva) nuevaSesion();
        });

        nuevaSesion();
        setContentPane(sesiones);

        atajo(KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK), "sesion.nueva", this::nuevaSesion);
        atajo(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), "sesion.cerrar", () -> {
            if (sesiones.getSelectedComponent() instanceof SesionPanel s) cerrarSesion(s);
        });

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1300, 820);
        setMinimumSize(new Dimension(780, 580));
        setLocationRelativeTo(null);
    }

    /** Crea una sesión vacía antes de la pestaña "+" y la selecciona. */
    public SesionPanel nuevaSesion() {
        ajustando = true;
        try {
            String inicial = "Sesión " + (++contador);
            var titulo = new JLabel(inicial);
            var sesion = new SesionPanel(t -> {
                titulo.setText(t);
                int i = indiceDe(titulo);
                if (i >= 0) sesiones.setToolTipTextAt(i, t);
            });
            int idx = sesiones.indexOfComponent(tabNueva);
            sesiones.insertTab(inicial, null, sesion, inicial, idx);
            sesiones.setTabComponentAt(idx, cabecera(titulo, sesion));
            sesiones.setSelectedIndex(idx);
            return sesion;
        } finally {
            ajustando = false;
        }
    }

    private void cerrarSesion(SesionPanel sesion) {
        ajustando = true;
        try {
            int idx = sesiones.indexOfComponent(sesion);
            if (idx < 0) return;
            sesiones.remove(idx);
            if (sesiones.getTabCount() == 1) {       // sólo queda "+": siempre dejar una sesión
                ajustando = false;
                nuevaSesion();
                return;
            }
            sesiones.setSelectedIndex(Math.max(0, Math.min(idx, sesiones.getTabCount() - 2)));
        } finally {
            ajustando = false;
        }
    }

    private JComponent cabecera(JLabel titulo, SesionPanel sesion) {
        var panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        titulo.setIcon(new IconoCarpeta(14));
        var cerrar = new JButton("✕");
        cerrar.setMargin(new Insets(0, 2, 0, 2));
        cerrar.setBorderPainted(false);
        cerrar.setContentAreaFilled(false);
        cerrar.setFocusable(false);
        cerrar.setToolTipText("Cerrar sesión (Ctrl+W)");
        cerrar.addActionListener(e -> cerrarSesion(sesion));
        panel.add(titulo);
        panel.add(cerrar);
        return panel;
    }

    private int indiceDe(JLabel titulo) {
        for (int i = 0; i < sesiones.getTabCount(); i++) {
            if (sesiones.getTabComponentAt(i) instanceof JPanel p && p.isAncestorOf(titulo)) return i;
        }
        return -1;
    }

    private void atajo(KeyStroke ks, String nombre, Runnable r) {
        var raiz = getRootPane();
        raiz.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, nombre);
        raiz.getActionMap().put(nombre, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }
}
