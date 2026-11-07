package com.logobjectjson.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Drawn "Open file" icon: an open yellow folder. */
public class FolderIcon implements Icon {

    private static final Color BACK  = new Color(230, 180, 60);
    private static final Color FRONT = new Color(250, 205, 90);
    private static final Color EDGE  = new Color(170, 120, 20);
    private static final Color GRAY   = new Color(175, 175, 175);

    private final int size;

    public FolderIcon(int size) {
        this.size = size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(x, y);
        boolean enabled = c == null || c.isEnabled();
        double s = size;

        // Back part with tab
        var back = new Path2D.Double();
        back.moveTo(s * 0.06, s * 0.22);
        back.lineTo(s * 0.40, s * 0.22);
        back.lineTo(s * 0.48, s * 0.32);
        back.lineTo(s * 0.92, s * 0.32);
        back.lineTo(s * 0.92, s * 0.86);
        back.lineTo(s * 0.06, s * 0.86);
        back.closePath();
        g2.setColor(enabled ? BACK : GRAY);
        g2.fill(back);
        g2.setColor(enabled ? EDGE : GRAY.darker());
        g2.setStroke(new BasicStroke(1f));
        g2.draw(back);

        // Slanted front (open folder)
        var front = new Path2D.Double();
        front.moveTo(s * 0.18, s * 0.44);
        front.lineTo(s * 0.98, s * 0.44);
        front.lineTo(s * 0.86, s * 0.86);
        front.lineTo(s * 0.06, s * 0.86);
        front.closePath();
        g2.setColor(enabled ? FRONT : GRAY.brighter());
        g2.fill(front);
        g2.setColor(enabled ? EDGE : GRAY.darker());
        g2.draw(front);

        g2.dispose();
    }

    @Override public int getIconWidth()  { return size; }
    @Override public int getIconHeight() { return size; }
}
