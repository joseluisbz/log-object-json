package com.logobjectjson.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Drawn "Validate" icon: a circle with a check mark. It also shows the result
 * of the last validation (shape AND color, not color alone):
 * neutral ✔ (not validated yet), neutral … (validating), green ✔ (valid), red ✖ (invalid).
 */
public class ValidateIcon implements Icon {

    public enum State { NONE, PENDING, VALID, INVALID }

    private static final Color NEUTRAL = new Color(70, 90, 120);
    private static final Color VALID = new Color(30, 140, 70);
    private static final Color INVALID = new Color(200, 50, 40);
    private static final Color GRAY = new Color(165, 165, 165);

    private final int size;
    private State state = State.NONE;

    public ValidateIcon(int size) {
        this.size = size;
    }

    public void setState(State state) {
        this.state = state;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(x, y);
        boolean enabled = c == null || c.isEnabled();
        Color color = !enabled ? GRAY : switch (state) {
            case NONE, PENDING -> NEUTRAL;
            case VALID -> VALID;
            case INVALID -> INVALID;
        };
        float s = size;
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawOval(1, 1, size - 3, size - 3);
        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (state == State.PENDING && enabled) {
            for (int i = 0; i < 3; i++) {
                int d = Math.max(2, Math.round(s * 0.12f));
                g2.fillOval(Math.round(s * (0.26f + i * 0.18f)), Math.round(s * 0.46f), d, d);
            }
        } else if (state == State.INVALID && enabled) {
            g2.drawLine(Math.round(s * 0.34f), Math.round(s * 0.34f), Math.round(s * 0.62f), Math.round(s * 0.62f));
            g2.drawLine(Math.round(s * 0.62f), Math.round(s * 0.34f), Math.round(s * 0.34f), Math.round(s * 0.62f));
        } else {
            g2.drawPolyline(new int[]{Math.round(s * 0.28f), Math.round(s * 0.44f), Math.round(s * 0.70f)},
                            new int[]{Math.round(s * 0.50f), Math.round(s * 0.66f), Math.round(s * 0.34f)}, 3);
        }
        g2.dispose();
    }

    @Override public int getIconWidth()  { return size; }
    @Override public int getIconHeight() { return size; }
}
