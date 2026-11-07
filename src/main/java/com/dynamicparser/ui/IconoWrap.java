package com.dynamicparser.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;

/**
 * Ícono dibujado "Wrap Lines" (ajustar líneas), al estilo de Postman/VS Code:
 *
 *   ───────────
 *   ─────────╮
 *   ───  ◄───╯
 *
 * Una línea larga que "da la vuelta" con una flecha hacia la izquierda.
 */
public class IconoWrap implements Icon {

    private static final int S = 16;

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(x, y);
        Color col = c == null || c.isEnabled() ? new Color(70, 70, 70) : new Color(170, 170, 170);
        g2.setColor(col);
        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // Línea superior completa
        g2.drawLine(2, 3, 14, 3);
        // Línea media que baja curvándose por la derecha y vuelve hacia la izquierda
        var p = new Path2D.Double();
        p.moveTo(2, 8);
        p.lineTo(11, 8);
        p.curveTo(14.5, 8, 14.5, 13, 11, 13);
        p.lineTo(8, 13);
        g2.draw(p);
        // Punta de flecha hacia la izquierda
        var punta = new Path2D.Double();
        punta.moveTo(8, 10.5);
        punta.lineTo(5.5, 13);
        punta.lineTo(8, 15.5);
        g2.draw(punta);
        // Línea inferior corta
        g2.drawLine(2, 13, 4, 13);

        g2.dispose();
    }

    @Override public int getIconWidth()  { return S; }
    @Override public int getIconHeight() { return S; }
}
