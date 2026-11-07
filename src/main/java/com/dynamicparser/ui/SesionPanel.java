package com.dynamicparser.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Una sesión (una pestaña de la ventana). Contiene un JTabbedPane de "tríos"
 * texto → árbol → JSON ({@link TrioPanel}):
 *
 *   [⬡ Principal]  [rama ✕]  [rama ✕] …
 *
 *  • Principal: el texto cargado o pegado. No se puede cerrar.
 *  • Ramas: se crean con clic secundario en un nodo → "Extraer rama". Cada una
 *    tiene su propio texto (sólo la parte de la rama), árbol, JSON y botones,
 *    independientes de la Principal. Se pueden cerrar sin afectar nada.
 *  • De una rama también se pueden extraer ramas (se abren en este mismo nivel).
 */
public class SesionPanel extends JPanel {

    private final JTabbedPane trios = new JTabbedPane();
    private final TrioPanel principal;

    /**
     * @param alCambiarTitulo avisa el título de la sesión (archivo cargado o "Texto pegado")
     */
    public SesionPanel(java.util.function.Consumer<String> alCambiarTitulo) {
        super(new BorderLayout());
        principal = new TrioPanel(alCambiarTitulo, this::abrirRama);

        trios.addTab("Principal", IconosArbol.OBJETO, principal,
                "Texto cargado o pegado (no se puede cerrar)");
        add(trios, BorderLayout.CENTER);
    }

    /** Abre una rama extraída en una pestaña nueva (cerrable) y la selecciona. */
    private void abrirRama(String titulo, String ruta, String texto) {
        var titulo0 = new JLabel(corto(titulo), IconosArbol.OBJETO, SwingConstants.LEFT);
        // OJO: sin tooltip en la etiqueta: un componente con tooltip captura los clics
        // y la pestaña dejaría de poder seleccionarse. El tooltip va en la pestaña.
        var rama = new TrioPanel(t -> titulo0.setText(corto(t)), this::abrirRama);

        trios.addTab(corto(titulo), rama);
        int idx = trios.indexOfComponent(rama);
        trios.setToolTipTextAt(idx, ruta);
        trios.setTabComponentAt(idx, cabecera(titulo0, rama));
        trios.setSelectedIndex(idx);
        rama.cargarTexto(texto);
    }

    private JComponent cabecera(JLabel titulo, TrioPanel rama) {
        var panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        var cerrar = new JButton("✕");
        cerrar.setMargin(new Insets(0, 2, 0, 2));
        cerrar.setBorderPainted(false);
        cerrar.setContentAreaFilled(false);
        cerrar.setFocusable(false);
        cerrar.setToolTipText("Cerrar esta rama");
        cerrar.addActionListener(e -> trios.remove(rama));
        panel.add(titulo);
        panel.add(cerrar);
        return panel;
    }

    private static String corto(String t) {
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }
}
