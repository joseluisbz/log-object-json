package com.dynamicparser.ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Controles de búsqueda reutilizables: 🔍 [texto] ▲ ▼ Aa estado.
 *  • Enter = siguiente, Shift+Enter = anterior, Esc = limpiar.
 *  • Al escribir se busca desde el inicio (búsqueda incremental).
 *  • La caja de texto se estira con el ancho disponible (BoxLayout).
 *
 * Es un JPanel transparente para poder incrustarse dentro de una JToolBar.
 */
public class BarraBusqueda extends JPanel {

    /** Lógica de búsqueda: devuelve el texto de estado (p. ej. "3 / 12"). */
    @FunctionalInterface
    public interface Buscador {
        String buscar(String texto, boolean adelante, boolean desdeInicio, boolean matchCase);
    }

    private final JTextField txt = new JTextField(14);
    private final JCheckBox chkCase = new JCheckBox("Aa");
    private final JLabel lblEstado = new JLabel(" ");
    private final Buscador buscador;

    public BarraBusqueda(String placeholder, Buscador buscador) {
        this.buscador = buscador;
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setOpaque(false);

        txt.setToolTipText(placeholder + "  (Enter: siguiente · Shift+Enter: anterior · Esc: limpiar)");
        txt.setMinimumSize(new Dimension(80, txt.getPreferredSize().height));
        txt.setMaximumSize(new Dimension(Integer.MAX_VALUE, txt.getPreferredSize().height));
        chkCase.setToolTipText("Distinguir mayúsculas/minúsculas");
        chkCase.setOpaque(false);
        chkCase.setFocusable(false);
        lblEstado.setMinimumSize(new Dimension(70, 1));
        lblEstado.setPreferredSize(new Dimension(80, txt.getPreferredSize().height));

        var btnAnt = boton("▲", "Anterior (Shift+Enter)", () -> ejecutar(false, false));
        var btnSig = boton("▼", "Siguiente (Enter)", () -> ejecutar(true, false));

        txt.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { ejecutar(true, true); }
            @Override public void removeUpdate(DocumentEvent e)  { ejecutar(true, true); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        chkCase.addActionListener(e -> ejecutar(true, true));

        var im = txt.getInputMap(JComponent.WHEN_FOCUSED);
        var am = txt.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "sig");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "ant");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "limpiar");
        am.put("sig", accion(() -> ejecutar(true, false)));
        am.put("ant", accion(() -> ejecutar(false, false)));
        am.put("limpiar", accion(() -> txt.setText("")));

        var lupa = new JLabel("🔍");
        lupa.setToolTipText(placeholder);

        add(lupa);
        add(Box.createHorizontalStrut(4));
        add(txt);
        add(Box.createHorizontalStrut(3));
        add(btnAnt);
        add(btnSig);
        add(Box.createHorizontalStrut(3));
        add(chkCase);
        add(Box.createHorizontalStrut(6));
        add(lblEstado);
    }

    /** Lleva el foco al campo de búsqueda (p. ej. con Ctrl+F). */
    public void enfocar() {
        txt.requestFocusInWindow();
        txt.selectAll();
    }

    private void ejecutar(boolean adelante, boolean desdeInicio) {
        String t = txt.getText();
        String estado = buscador.buscar(t, adelante, desdeInicio, chkCase.isSelected());
        lblEstado.setText(estado == null || estado.isEmpty() ? " " : estado);
        boolean sinResultados = !t.isEmpty() && estado != null && estado.startsWith("0");
        txt.setBackground(sinResultados ? new Color(255, 225, 225) : UIManager.getColor("TextField.background"));
    }

    private static JButton boton(String texto, String tip, Runnable r) {
        var b = new JButton(texto);
        b.setMargin(new Insets(1, 5, 1, 5));
        b.setFocusable(false);
        b.setToolTipText(tip);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static Action accion(Runnable r) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        };
    }
}
