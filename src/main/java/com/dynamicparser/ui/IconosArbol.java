package com.dynamicparser.ui;

import com.dynamicparser.model.ArrayRef;
import com.dynamicparser.model.DynamicObject;
import com.dynamicparser.model.JsonObjectMap;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Map;

/**
 * Íconos dibujados (16×16) para los nodos del árbol, según la naturaleza del valor:
 *
 *   ⬡ azul      objeto con clase conocida (DynamicObject)
 *   <,> morado  Map de Java  ({k=v}) — mismo recuadro claro que los demás
 *   {} verde    objeto JSON  ({"k": v})
 *   [] naranja  secuencia: List / Set / arreglo
 *   [] ⚠        arreglo sin contenido ([Lx;@hash)
 *   •  gris     valor simple (hoja)
 */
public final class IconosArbol {

    private static final Color AZUL    = new Color(45, 105, 200);
    private static final Color MORADO  = new Color(135, 70, 175);
    private static final Color VERDE   = new Color(30, 140, 90);
    private static final Color NARANJA = new Color(210, 120, 20);
    private static final Color GRIS    = new Color(140, 140, 140);
    private static final Color ROJO    = new Color(200, 60, 40);

    public static final Icon OBJETO  = new Dibujo((g, s) -> hexagono(g, s, AZUL));
    public static final Icon MAPA    = new Dibujo(24, (g, s) -> glifo(g, 24, s, MORADO, "<,>"));
    public static final Icon JSON    = new Dibujo((g, s) -> llaves(g, s, VERDE, "{}"));
    public static final Icon LISTA   = new Dibujo((g, s) -> llaves(g, s, NARANJA, "[]"));
    public static final Icon ARREGLO = new Dibujo((g, s) -> { llaves(g, s, GRIS, "[]"); alerta(g, s); });
    public static final Icon HOJA    = new Dibujo((g, s) -> {
        g.setColor(GRIS);
        g.fillOval(s / 2 - 3, s / 2 - 3, 6, 6);
    });

    private IconosArbol() { }

    /** Ícono según el valor del nodo (null = usar el del Look&Feel). */
    public static Icon para(Object valor) {
        if (valor instanceof DynamicObject) return OBJETO;
        if (valor instanceof JsonObjectMap) return JSON;
        if (valor instanceof Map<?, ?>)     return MAPA;
        if (valor instanceof List<?>)       return LISTA;
        if (valor instanceof ArrayRef)      return ARREGLO;
        return HOJA;
    }

    // ─────────────────────────────────────────────────────────────────────

    private static void hexagono(Graphics2D g, int s, Color c) {
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

    private static void llaves(Graphics2D g, int s, Color c, String texto) {
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
        g.fillRoundRect(0, 1, s - 1, s - 2, 5, 5);
        g.setColor(c);
        g.drawRoundRect(0, 1, s - 1, s - 2, 5, 5);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        var fm = g.getFontMetrics();
        int w = fm.stringWidth(texto);
        g.drawString(texto, (s - w) / 2, (s + fm.getAscent() - fm.getDescent()) / 2);
    }

    /** Texto en negrita del color dado, centrado, sobre el mismo recuadro claro que los demás íconos. */
    private static void glifo(Graphics2D g, int ancho, int alto, Color c, String texto) {
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
        g.fillRoundRect(0, 1, ancho - 1, alto - 2, 5, 5);
        g.setColor(c);
        g.drawRoundRect(0, 1, ancho - 1, alto - 2, 5, 5);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        var fm = g.getFontMetrics();
        int w = fm.stringWidth(texto);
        g.drawString(texto, (ancho - w) / 2, (alto + fm.getAscent() - fm.getDescent()) / 2);
    }

    private static void alerta(Graphics2D g, int s) {
        g.setColor(ROJO);
        g.fillOval(s - 6, s - 6, 6, 6);
    }

    /** Ícono que delega el dibujo en una lambda (alto 16; ancho configurable). */
    private record Dibujo(int ancho, java.util.function.BiConsumer<Graphics2D, Integer> pintor) implements Icon {
        private static final int S = 16;
        Dibujo(java.util.function.BiConsumer<Graphics2D, Integer> pintor) {
            this(S, pintor);
        }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.translate(x, y);
            pintor.accept(g2, S);
            g2.dispose();
        }
        @Override public int getIconWidth()  { return ancho; }
        @Override public int getIconHeight() { return S; }
    }
}
