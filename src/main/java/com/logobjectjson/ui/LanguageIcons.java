package com.logobjectjson.ui;

import com.logobjectjson.Config;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Language logos (Devicon, MIT license; resources/languages/*.png, 64×64) for the
 * "languages of the recognized syntax" badge. The language → icon mapping lives
 * in application.yml ({@code languageIcons.<Language>}).
 */
public final class LanguageIcons {

    private static final int SIZE = 16;
    private static final int GAP = 3;
    private static final Map<String, Optional<BufferedImage>> CACHE = new ConcurrentHashMap<>();

    private LanguageIcons() { }

    /** Icon of one language, or empty if it has none. */
    public static Optional<BufferedImage> image(String language) {
        return CACHE.computeIfAbsent(language, LanguageIcons::load);
    }

    private static Optional<BufferedImage> load(String language) {
        String id;
        try {
            id = Config.string("languageIcons." + language);
        } catch (IllegalStateException ex) {
            return Optional.empty();
        }
        String resource = "/" + Config.string("languageIconResource").replace("{id}", id);
        try (InputStream in = LanguageIcons.class.getResourceAsStream(resource)) {
            return in == null ? Optional.empty() : Optional.ofNullable(ImageIO.read(in));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    /** One icon with the logos side by side, or null if any language lacks a logo (then text is used). */
    public static Icon row(List<String> languages) {
        var images = new ArrayList<BufferedImage>();
        for (var l : languages) {
            var img = image(l);
            if (img.isEmpty()) return null;
            images.add(img.get());
        }
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                for (int i = 0; i < images.size(); i++) {
                    g2.drawImage(images.get(i), x + i * (SIZE + GAP), y, SIZE, SIZE, null);
                }
                g2.dispose();
            }
            @Override public int getIconWidth()  { return images.size() * (SIZE + GAP) - GAP; }
            @Override public int getIconHeight() { return SIZE; }
        };
    }
}
