package com.dynamicparser.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Ícono dibujado "Abrir archivo": carpeta amarilla abierta. */
public class IconoCarpeta implements Icon {

    private static final Color FONDO  = new Color(230, 180, 60);
    private static final Color FRENTE = new Color(250, 205, 90);
    private static final Color BORDE  = new Color(170, 120, 20);
    private static final Color GRIS   = new Color(175, 175, 175);

    private final int size;

    public IconoCarpeta(int size) {
        this.size = size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(x, y);
        boolean activo = c == null || c.isEnabled();
        double s = size;

        // Parte trasera con pestaña
        var atras = new Path2D.Double();
        atras.moveTo(s * 0.06, s * 0.22);
        atras.lineTo(s * 0.40, s * 0.22);
        atras.lineTo(s * 0.48, s * 0.32);
        atras.lineTo(s * 0.92, s * 0.32);
        atras.lineTo(s * 0.92, s * 0.86);
        atras.lineTo(s * 0.06, s * 0.86);
        atras.closePath();
        g2.setColor(activo ? FONDO : GRIS);
        g2.fill(atras);
        g2.setColor(activo ? BORDE : GRIS.darker());
        g2.setStroke(new BasicStroke(1f));
        g2.draw(atras);

        // Frente inclinado (carpeta abierta)
        var frente = new Path2D.Double();
        frente.moveTo(s * 0.18, s * 0.44);
        frente.lineTo(s * 0.98, s * 0.44);
        frente.lineTo(s * 0.86, s * 0.86);
        frente.lineTo(s * 0.06, s * 0.86);
        frente.closePath();
        g2.setColor(activo ? FRENTE : GRIS.brighter());
        g2.fill(frente);
        g2.setColor(activo ? BORDE : GRIS.darker());
        g2.draw(frente);

        g2.dispose();
    }

    @Override public int getIconWidth()  { return size; }
    @Override public int getIconHeight() { return size; }
}
