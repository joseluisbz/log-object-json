package com.logobjectjson.ui;

import com.logobjectjson.model.ArrayRef;
import com.logobjectjson.model.DynamicObject;
import com.logobjectjson.model.JsonObjectMap;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Drawn (16×16) icons for tree nodes, by the nature of the value:
 *
 *   ⬡ blue      object with a known class (DynamicObject)
 *   <,> purple  Java Map ({k=v}) — same light box as the others
 *   {} green    JSON object ({"k": v})
 *   [] orange   sequence: List / Set / array
 *   [] ⚠        array without contents ([Lx;@hash)
 *   •  gray     simple value (leaf)
 */
public final class TreeIcons {

    private static final Color BLUE   = new Color(45, 105, 200);
    private static final Color PURPLE = new Color(135, 70, 175);
    private static final Color GREEN  = new Color(30, 140, 90);
    private static final Color ORANGE = new Color(210, 120, 20);
    private static final Color GRAY   = new Color(140, 140, 140);
    private static final Color RED    = new Color(200, 60, 40);

    public static final Icon OBJECT    = new Drawing((g, s) -> hexagon(g, s, BLUE));
    public static final Icon MAP       = new Drawing(24, (g, s) -> glyph(g, 24, s, PURPLE, "<,>"));
    public static final Icon JSON      = new Drawing((g, s) -> brackets(g, s, GREEN, "{}"));
    public static final Icon LIST      = new Drawing((g, s) -> brackets(g, s, ORANGE, "[]"));
    public static final Icon ARRAY_REF = new Drawing((g, s) -> { brackets(g, s, GRAY, "[]"); alert(g, s); });
    public static final Icon LEAF      = new Drawing((g, s) -> {
        g.setColor(GRAY);
        g.fillOval(s / 2 - 3, s / 2 - 3, 6, 6);
    });

    private TreeIcons() { }

    /** Icon for the node's value. */
    public static Icon forValue(Object value) {
        if (value instanceof DynamicObject) return OBJECT;
        if (value instanceof JsonObjectMap) return JSON;
        if (value instanceof Map<?, ?>)     return MAP;
        if (value instanceof List<?>)       return LIST;
        if (value instanceof ArrayRef)      return ARRAY_REF;
        return LEAF;
    }

    // ─────────────────────────────────────────────────────────────────────

    private static void hexagon(Graphics2D g, int s, Color c) {
        double r = s / 2.0 - 1.5, cx = s / 2.0, cy = s / 2.0;
        var hex = new Path2D.Double();
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(60 * i - 90);
            double x = cx + r * Math.cos(a), y = cy + r * Math.sin(a);
            if (i == 0) hex.moveTo(x, y); else hex.lineTo(x, y);
        }
        hex.closePath();
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
        g.fill(hex);
        g.setColor(c);
        g.setStroke(new BasicStroke(1.6f));
        g.draw(hex);
    }

    private static void brackets(Graphics2D g, int s, Color c, String text) {
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
        g.fillRoundRect(0, 1, s - 1, s - 2, 5, 5);
        g.setColor(c);
        g.drawRoundRect(0, 1, s - 1, s - 2, 5, 5);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        var fm = g.getFontMetrics();
        int w = fm.stringWidth(text);
        g.drawString(text, (s - w) / 2, (s + fm.getAscent() - fm.getDescent()) / 2);
    }

    /** Bold text in the given color, centered, on the same light box as the other icons. */
    private static void glyph(Graphics2D g, int width, int height, Color c, String text) {
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
        g.fillRoundRect(0, 1, width - 1, height - 2, 5, 5);
        g.setColor(c);
        g.drawRoundRect(0, 1, width - 1, height - 2, 5, 5);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        var fm = g.getFontMetrics();
        int w = fm.stringWidth(text);
        g.drawString(text, (width - w) / 2, (height + fm.getAscent() - fm.getDescent()) / 2);
    }

    private static void alert(Graphics2D g, int s) {
        g.setColor(RED);
        g.fillOval(s - 6, s - 6, 6, 6);
    }

    /** Icon that delegates painting to a lambda (height 16; configurable width). */
    private record Drawing(int width, BiConsumer<Graphics2D, Integer> painter) implements Icon {
        private static final int S = 16;
        Drawing(BiConsumer<Graphics2D, Integer> painter) {
            this(S, painter);
        }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.translate(x, y);
            painter.accept(g2, S);
            g2.dispose();
        }
        @Override public int getIconWidth()  { return width; }
        @Override public int getIconHeight() { return S; }
    }
}
