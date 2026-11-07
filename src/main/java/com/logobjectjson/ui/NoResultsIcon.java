package com.logobjectjson.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;

/** Drawn "No results" icon: a magnifier crossed out (gray; red when a search found nothing). */
public class NoResultsIcon implements Icon {

    private static final Color NEUTRAL = new Color(130, 130, 130);
    private static final Color ERROR = new Color(200, 50, 40);
    private final boolean error;

    public NoResultsIcon(boolean error) {
        this.error = error;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(x, y);
        g2.setColor(NEUTRAL);
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Ellipse2D.Double(1.5, 1.5, 9, 9));
        g2.draw(new Line2D.Double(9.2, 9.2, 13.5, 13.5));
        g2.setColor(error ? ERROR : NEUTRAL);
        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Double(2.5, 9.5, 9.5, 2.5));
        g2.dispose();
    }

    @Override public int getIconWidth()  { return 15; }
    @Override public int getIconHeight() { return 15; }
}
