package com.logobjectjson.ui;

import javax.swing.*;
import java.awt.*;

/** Drawn "Copy" icon: two overlapping sheets. */
public class CopyIcon implements Icon {

    private static final Color EDGE = new Color(70, 90, 120);
    private static final Color SHEET = new Color(235, 241, 250);
    private static final Color GRAY = new Color(165, 165, 165);

    private final int size;

    public CopyIcon(int size) {
        this.size = size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(x, y);
        boolean enabled = c == null || c.isEnabled();
        int s = size, w = s * 10 / 16, h = s * 12 / 16;
        g2.setStroke(new BasicStroke(1.2f));
        // Back sheet (top-left) and front sheet (bottom-right)
        for (int[] o : new int[][]{{1, 1}, {s - w - 1, s - h - 1}}) {
            g2.setColor(SHEET);
            g2.fillRoundRect(o[0], o[1], w, h, 3, 3);
            g2.setColor(enabled ? EDGE : GRAY);
            g2.drawRoundRect(o[0], o[1], w, h, 3, 3);
        }
        g2.dispose();
    }

    @Override public int getIconWidth()  { return size; }
    @Override public int getIconHeight() { return size; }
}
