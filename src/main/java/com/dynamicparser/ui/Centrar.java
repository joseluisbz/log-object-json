package com.dynamicparser.ui;

import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import java.awt.*;

/** Desplaza un editor para que un offset quede centrado (vertical y horizontalmente). */
public final class Centrar {

    private Centrar() { }

    public static void en(JTextComponent editor, int offset) {
        try {
            var r = editor.modelToView2D(offset);
            if (r == null) return;
            var vis = editor.getVisibleRect();
            int x = (int) r.getX() - vis.width / 2;
            int y = (int) r.getY() - (vis.height - (int) r.getHeight()) / 2;
            x = Math.max(0, Math.min(x, editor.getWidth() - vis.width));
            y = Math.max(0, Math.min(y, editor.getHeight() - vis.height));
            editor.scrollRectToVisible(new Rectangle(x, y, vis.width, vis.height));
        } catch (BadLocationException ignored) { }
    }
}
