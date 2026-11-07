package com.logobjectjson.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;

/**
 * Drawn "Wrap Lines" icon, Postman/VS Code style:
 *
 *   ───────────
 *   ─────────╮
 *   ───  ◄───╯
 *
 * A long line that "wraps around" with an arrow pointing left.
 */
public class WrapIcon implements Icon {

    private static final int S = 16;

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(x, y);
        Color col = c == null || c.isEnabled() ? new Color(70, 70, 70) : new Color(170, 170, 170);
        g2.setColor(col);
        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // Full top line
        g2.drawLine(2, 3, 14, 3);
        // Middle line curving down on the right and coming back to the left
        var p = new Path2D.Double();
        p.moveTo(2, 8);
        p.lineTo(11, 8);
        p.curveTo(14.5, 8, 14.5, 13, 11, 13);
        p.lineTo(8, 13);
        g2.draw(p);
        // Arrow head pointing left
        var head = new Path2D.Double();
        head.moveTo(8, 10.5);
        head.lineTo(5.5, 13);
        head.lineTo(8, 15.5);
        g2.draw(head);
        // Short bottom line
        g2.drawLine(2, 13, 4, 13);

        g2.dispose();
    }

    @Override public int getIconWidth()  { return S; }
    @Override public int getIconHeight() { return S; }
}
