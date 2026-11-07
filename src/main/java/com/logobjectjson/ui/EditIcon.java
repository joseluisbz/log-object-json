package com.logobjectjson.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;

/** Drawn "Edit" icon: a slanted pencil (stroke only, as in IDE toolbars). */
public class EditIcon implements Icon {

    private static final Color STROKE = new Color(70, 90, 120);
    private static final Color TIP = new Color(210, 120, 20);
    private static final Color GRAY = new Color(165, 165, 165);

    private final int size;

    public EditIcon(int size) {
        this.size = size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(x, y);
        boolean enabled = c == null || c.isEnabled();
        double s = size;
        g2.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // Pencil body: from the top-right corner down to the bottom-left
        var body = new Path2D.Double();
        body.moveTo(s * 0.72, s * 0.10);
        body.lineTo(s * 0.92, s * 0.30);
        body.lineTo(s * 0.34, s * 0.88);
        body.lineTo(s * 0.10, s * 0.92);
        body.lineTo(s * 0.14, s * 0.68);
        body.closePath();
        g2.setColor(enabled ? STROKE : GRAY);
        g2.draw(body);
        // Eraser band and tip
        g2.drawLine((int) (s * 0.60), (int) (s * 0.22), (int) (s * 0.80), (int) (s * 0.42));
        var tip = new Path2D.Double();
        tip.moveTo(s * 0.10, s * 0.92);
        tip.lineTo(s * 0.14, s * 0.76);
        tip.lineTo(s * 0.26, s * 0.88);
        tip.closePath();
        g2.setColor(enabled ? TIP : GRAY);
        g2.fill(tip);
        g2.dispose();
    }

    @Override public int getIconWidth()  { return size; }
    @Override public int getIconHeight() { return size; }
}
