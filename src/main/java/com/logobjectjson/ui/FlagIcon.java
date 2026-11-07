package com.logobjectjson.ui;

import com.logobjectjson.Config;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Language flag: the real flag image (all flags share the same source size
 * and quality: 64×48 PNG, public-domain flags from flagcdn.com / Wikimedia),
 * painted at the icon's size with high-quality interpolation, so HiDPI
 * screens use the full resolution. The language → country mapping lives in
 * application.yml ({@code i18n.flags.<lang>}). A language without a flag is
 * shown as its code in a gray box.
 */
public class FlagIcon implements Icon {

    private static final int W = 24, H = 18;
    private static final Map<String, BufferedImage> CACHE = new ConcurrentHashMap<>();
    private static final BufferedImage NONE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private final String lang;
    private final BufferedImage image;

    public FlagIcon(String lang) {
        this.lang = lang;
        var img = CACHE.computeIfAbsent(lang, FlagIcon::load);
        this.image = img == NONE ? null : img;
    }

    private static BufferedImage load(String lang) {
        String country;
        try {
            country = Config.string("i18n.flags." + lang);
        } catch (IllegalStateException ex) {
            return NONE;
        }
        String resource = "/" + Config.string("i18n.flagResource").replace("{country}", country);
        try (InputStream in = FlagIcon.class.getResourceAsStream(resource)) {
            if (in == null) return NONE;
            var img = ImageIO.read(in);
            return img != null ? img : NONE;
        } catch (IOException ex) {
            return NONE;
        }
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.translate(x, y);
        if (image != null) {
            // The image already has its own shape and transparent margins: no clip, no border
            g2.drawImage(image, 0, 0, W, H, null);
            g2.dispose();
            return;
        }
        var frame = new RoundRectangle2D.Double(0, 0, W, H, 3, 3);
        g2.clip(frame);
        {
            g2.setColor(new Color(225, 225, 225));
            g2.fillRect(0, 0, W, H);
            g2.setColor(Color.DARK_GRAY);
            g2.setFont(new Font(Font.DIALOG, Font.BOLD, 9));
            var fm = g2.getFontMetrics();
            String t = lang.toUpperCase();
            g2.drawString(t, (W - fm.stringWidth(t)) / 2f, (H + fm.getAscent()) / 2f - 1);
        }
        g2.setClip(null);
        g2.setColor(new Color(0, 0, 0, 60));
        g2.draw(new RoundRectangle2D.Double(0.5, 0.5, W - 1, H - 1, 3, 3));
        g2.dispose();
    }

    @Override public int getIconWidth()  { return W; }
    @Override public int getIconHeight() { return H; }
}
