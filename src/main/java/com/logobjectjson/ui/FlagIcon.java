package com.logobjectjson.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;

/**
 * Drawn language flags (no image files): en = United Kingdom, es, fr, pt, de, it.
 * Any other language code is shown as its code in a gray box.
 */
public class FlagIcon implements Icon {

    private static final int W = 20, H = 14;
    private static final Color UK_BLUE = new Color(1, 33, 105), UK_RED = new Color(200, 16, 46);
    private final String lang;

    public FlagIcon(String lang) {
        this.lang = lang;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(x, y);
        g2.clipRect(0, 0, W, H);
        switch (lang) {
            case "en" -> unitedKingdom(g2);
            case "es" -> horizontal(g2, new int[]{1, 2, 1}, new Color(170, 21, 27), new Color(241, 191, 0), new Color(170, 21, 27));
            case "fr" -> vertical(g2, new Color(0, 35, 149), Color.WHITE, new Color(237, 41, 57));
            case "it" -> vertical(g2, new Color(0, 146, 70), Color.WHITE, new Color(206, 43, 55));
            case "de" -> horizontal(g2, new int[]{1, 1, 1}, Color.BLACK, new Color(221, 0, 0), new Color(255, 206, 0));
            case "pt" -> portugal(g2);
            default -> {
                g2.setColor(new Color(225, 225, 225));
                g2.fillRect(0, 0, W, H);
                g2.setColor(Color.DARK_GRAY);
                g2.setFont(new Font(Font.DIALOG, Font.BOLD, 9));
                var fm = g2.getFontMetrics();
                g2.drawString(lang.toUpperCase(), (W - fm.stringWidth(lang.toUpperCase())) / 2, (H + fm.getAscent()) / 2 - 1);
            }
        }
        g2.setClip(null);
        g2.setColor(new Color(0, 0, 0, 70));
        g2.drawRect(0, 0, W - 1, H - 1);
        g2.dispose();
    }

    /** Horizontal stripes with relative heights. */
    private static void horizontal(Graphics2D g, int[] weights, Color... colors) {
        int total = 0;
        for (int w : weights) total += w;
        float y = 0;
        for (int i = 0; i < colors.length; i++) {
            float h = H * weights[i] / (float) total;
            g.setColor(colors[i]);
            g.fillRect(0, Math.round(y), W, Math.round(y + h) - Math.round(y));
            y += h;
        }
    }

    private static void vertical(Graphics2D g, Color... colors) {
        for (int i = 0; i < colors.length; i++) {
            g.setColor(colors[i]);
            g.fillRect(i * W / colors.length, 0, W / colors.length + 1, H);
        }
    }

    private static void portugal(Graphics2D g) {
        g.setColor(new Color(0, 102, 0));
        g.fillRect(0, 0, W * 2 / 5, H);
        g.setColor(new Color(255, 0, 0));
        g.fillRect(W * 2 / 5, 0, W - W * 2 / 5, H);
        g.setColor(new Color(255, 204, 0));
        int d = 7;
        g.fillOval(W * 2 / 5 - d / 2, (H - d) / 2, d, d);
    }

    /** Simplified Union Jack: blue field, white and red diagonals, white and red cross. */
    private static void unitedKingdom(Graphics2D g) {
        g.setColor(UK_BLUE);
        g.fillRect(0, 0, W, H);
        var diag = new Path2D.Double();
        diag.moveTo(0, 0);
        diag.lineTo(W, H);
        diag.moveTo(W, 0);
        diag.lineTo(0, H);
        g.setStroke(new BasicStroke(3.2f));
        g.setColor(Color.WHITE);
        g.draw(diag);
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(UK_RED);
        g.draw(diag);
        g.setColor(Color.WHITE);
        g.fillRect(W / 2 - 3, 0, 6, H);
        g.fillRect(0, H / 2 - 3, W, 6);
        g.setColor(UK_RED);
        g.fillRect(W / 2 - 2, 0, 4, H);
        g.fillRect(0, H / 2 - 2, W, 4);
    }

    @Override public int getIconWidth()  { return W; }
    @Override public int getIconHeight() { return H; }
}
