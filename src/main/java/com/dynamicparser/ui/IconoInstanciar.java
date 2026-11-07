package com.dynamicparser.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;

/**
 * Ícono dibujado para "Instanciar Objeto": un hexágono verde (⬡, el mismo
 * símbolo de los objetos del árbol) con un triángulo "play" blanco.
 * Se ve deshabilitado (gris) cuando el botón lo está.
 */
public class IconoInstanciar implements Icon {

    private static final Color VERDE       = new Color(34, 150, 74);
    private static final Color VERDE_BORDE = new Color(20, 105, 50);
    private static final Color GRIS        = new Color(170, 170, 170);
    private static final Color GRIS_BORDE  = new Color(130, 130, 130);

    private final int size;

    public IconoInstanciar(int size) {
        this.size = size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(x, y);
        boolean activo = c == null || c.isEnabled();

        // Hexágono (punta arriba)
        double r = size / 2.0 - 0.5, cx = size / 2.0, cy = size / 2.0;
        var hex = new Path2D.Double();
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(60 * i - 90);
            double px = cx + r * Math.cos(a), py = cy + r * Math.sin(a);
            if (i == 0) hex.moveTo(px, py); else hex.lineTo(px, py);
        }
        hex.closePath();
        g2.setColor(activo ? VERDE : GRIS);
        g2.fill(hex);
        g2.setColor(activo ? VERDE_BORDE : GRIS_BORDE);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(hex);

        // Triángulo "play"
        double t = size * 0.36;
        var play = new Path2D.Double();
        play.moveTo(cx - t * 0.45, cy - t * 0.6);
        play.lineTo(cx + t * 0.65, cy);
        play.lineTo(cx - t * 0.45, cy + t * 0.6);
        play.closePath();
        g2.setColor(Color.WHITE);
        g2.fill(play);

        g2.dispose();
    }

    @Override public int getIconWidth()  { return size; }
    @Override public int getIconHeight() { return size; }
}
