package com.logobjectjson.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Drawn language flags (no image files): en = United Kingdom, es, fr, pt, de, it.
 * Drawn with floating-point shapes (crisp at any scale, including HiDPI screens);
 * Spain and Portugal include a simplified coat of arms.
 * Any other language code is shown as its code in a gray box.
 */
public class FlagIcon implements Icon {

    private static final double W = 24, H = 16;
    private static final Color UK_BLUE = new Color(1, 33, 105), UK_RED = new Color(200, 16, 46);
    private static final Color ES_RED = new Color(170, 21, 27), ES_YELLOW = new Color(241, 191, 0);
    private static final Color PT_GREEN = new Color(0, 102, 0), PT_RED = new Color(218, 41, 28);
    private static final Color PT_GOLD = new Color(255, 204, 0), PT_BLUE = new Color(0, 51, 153);
    private final String lang;

    public FlagIcon(String lang) {
        this.lang = lang;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.translate(x, y);
        var frame = new RoundRectangle2D.Double(0, 0, W, H, 3, 3);
        g2.clip(frame);
        switch (lang) {
            case "en" -> unitedKingdom(g2);
            case "es" -> spain(g2);
            case "fr" -> vertical(g2, new Color(0, 35, 149), Color.WHITE, new Color(237, 41, 57));
            case "it" -> vertical(g2, new Color(0, 146, 70), Color.WHITE, new Color(206, 43, 55));
            case "de" -> horizontal(g2, new double[]{1, 1, 1}, Color.BLACK, new Color(221, 0, 0), new Color(255, 206, 0));
            case "pt" -> portugal(g2);
            default -> code(g2);
        }
        g2.setClip(null);
        g2.setColor(new Color(0, 0, 0, 80));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Double(0.5, 0.5, W - 1, H - 1, 3, 3));
        g2.dispose();
    }

    // ─────────────────────────────────────────────────────────────────────

    private static void horizontal(Graphics2D g, double[] weights, Color... colors) {
        double total = 0;
        for (double w : weights) total += w;
        double y = 0;
        for (int i = 0; i < colors.length; i++) {
            double h = H * weights[i] / total;
            g.setColor(colors[i]);
            g.fill(new Rectangle2D.Double(0, y, W, h + 0.5));
            y += h;
        }
    }

    private static void vertical(Graphics2D g, Color... colors) {
        double w = W / colors.length;
        for (int i = 0; i < colors.length; i++) {
            g.setColor(colors[i]);
            g.fill(new Rectangle2D.Double(i * w, 0, w + 0.5, H));
        }
    }

    /** Red-yellow(double)-red, with a simplified coat of arms near the hoist. */
    private static void spain(Graphics2D g) {
        horizontal(g, new double[]{1, 2, 1}, ES_RED, ES_YELLOW, ES_RED);
        double cx = 7.5, top = 5.0, sw = 4.4, sh = 5.6;
        // Pillars of Hercules
        g.setColor(new Color(150, 150, 150));
        g.fill(new Rectangle2D.Double(cx - sw / 2 - 1.6, top + 0.6, 0.9, sh - 0.8));
        g.fill(new Rectangle2D.Double(cx + sw / 2 + 0.7, top + 0.6, 0.9, sh - 0.8));
        // Crown
        g.setColor(new Color(200, 150, 0));
        g.fill(new Rectangle2D.Double(cx - sw / 2 + 0.3, top - 1.3, sw - 0.6, 1.1));
        // Shield: rounded bottom, quartered red / white
        var shield = new Path2D.Double();
        shield.moveTo(cx - sw / 2, top);
        shield.lineTo(cx + sw / 2, top);
        shield.lineTo(cx + sw / 2, top + sh * 0.6);
        shield.quadTo(cx + sw / 2, top + sh, cx, top + sh);
        shield.quadTo(cx - sw / 2, top + sh, cx - sw / 2, top + sh * 0.6);
        shield.closePath();
        g.setColor(new Color(235, 235, 235));
        g.fill(shield);
        var clip = g.getClip();
        g.clip(shield);
        g.setColor(ES_RED);
        g.fill(new Rectangle2D.Double(cx - sw / 2, top, sw / 2, sh / 2));
        g.fill(new Rectangle2D.Double(cx, top + sh / 2, sw / 2, sh / 2));
        g.setClip(clip);
        g.setColor(new Color(120, 70, 0));
        g.setStroke(new BasicStroke(0.5f));
        g.draw(shield);
    }

    /** Green (2/5) and red (3/5), with the armillary sphere and the shield on the boundary. */
    private static void portugal(Graphics2D g) {
        double split = W * 2 / 5;
        g.setColor(PT_GREEN);
        g.fill(new Rectangle2D.Double(0, 0, split, H));
        g.setColor(PT_RED);
        g.fill(new Rectangle2D.Double(split, 0, W - split, H));
        double cy = H / 2, r = 4.3;
        // Armillary sphere: gold ring with crossing bands
        g.setColor(PT_GOLD);
        g.setStroke(new BasicStroke(1.1f));
        g.draw(new Ellipse2D.Double(split - r, cy - r, 2 * r, 2 * r));
        g.setStroke(new BasicStroke(0.7f));
        g.draw(new Line2D.Double(split - r, cy, split + r, cy));
        g.draw(new Ellipse2D.Double(split - r, cy - r * 0.45, 2 * r, r * 0.9));
        // Shield: white with red border, small blue quinas
        double sw = 3.6, sh = 4.2, sx = split - sw / 2, sy = cy - sh / 2;
        var shield = new Path2D.Double();
        shield.moveTo(sx, sy);
        shield.lineTo(sx + sw, sy);
        shield.lineTo(sx + sw, sy + sh * 0.6);
        shield.quadTo(sx + sw, sy + sh, sx + sw / 2, sy + sh);
        shield.quadTo(sx, sy + sh, sx, sy + sh * 0.6);
        shield.closePath();
        g.setColor(PT_RED);
        g.fill(shield);
        var inner = new Rectangle2D.Double(sx + 0.7, sy + 0.7, sw - 1.4, sh - 1.6);
        g.setColor(Color.WHITE);
        g.fill(inner);
        g.setColor(PT_BLUE);
        double d = 0.8;
        double[][] quinas = {{0.5, 0.25}, {0.2, 0.55}, {0.8, 0.55}, {0.5, 0.55}, {0.5, 0.85}};
        for (double[] q : quinas) {
            g.fill(new Rectangle2D.Double(inner.x + inner.width * q[0] - d / 2, inner.y + inner.height * q[1] - d / 2, d, d));
        }
    }

    /** Simplified Union Jack: blue field, white and red diagonals, white and red cross. */
    private static void unitedKingdom(Graphics2D g) {
        g.setColor(UK_BLUE);
        g.fill(new Rectangle2D.Double(0, 0, W, H));
        var diag = new Path2D.Double();
        diag.moveTo(0, 0);
        diag.lineTo(W, H);
        diag.moveTo(W, 0);
        diag.lineTo(0, H);
        g.setStroke(new BasicStroke(3.4f));
        g.setColor(Color.WHITE);
        g.draw(diag);
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(UK_RED);
        g.draw(diag);
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Double(W / 2 - 3.2, 0, 6.4, H));
        g.fill(new Rectangle2D.Double(0, H / 2 - 3.2, W, 6.4));
        g.setColor(UK_RED);
        g.fill(new Rectangle2D.Double(W / 2 - 2, 0, 4, H));
        g.fill(new Rectangle2D.Double(0, H / 2 - 2, W, 4));
    }

    private void code(Graphics2D g) {
        g.setColor(new Color(225, 225, 225));
        g.fill(new Rectangle2D.Double(0, 0, W, H));
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font(Font.DIALOG, Font.BOLD, 9));
        var fm = g.getFontMetrics();
        String t = lang.toUpperCase();
        g.drawString(t, (float) (W - fm.stringWidth(t)) / 2, (float) (H + fm.getAscent()) / 2 - 1);
    }

    @Override public int getIconWidth()  { return (int) W; }
    @Override public int getIconHeight() { return (int) H; }
}
