package com.dynamicparser.ui;

import javax.swing.*;
import java.awt.*;

/** Ícono dibujado "Guardar": disquete azul. */
public class IconoGuardar implements Icon {

    private final int size;

    public IconoGuardar(int size) {
        this.size = size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(x, y);
        boolean activo = c == null || c.isEnabled();
        Color cuerpo = activo ? new Color(55, 105, 190) : new Color(160, 160, 160);
        int s = size;

        // Cuerpo con esquina superior derecha recortada
        var p = new Polygon(new int[]{1, s - 4, s - 1, s - 1, 1}, new int[]{1, 1, 4, s - 1, s - 1}, 5);
        g2.setColor(cuerpo);
        g2.fill(p);
        // Etiqueta blanca (abajo) y obturador (arriba)
        g2.setColor(Color.WHITE);
        g2.fillRect(4, s / 2 + 1, s - 8, s / 2 - 3);
        g2.setColor(new Color(220, 225, 235));
        g2.fillRect(4, 2, s - 9, s / 3);
        g2.setColor(cuerpo);
        g2.fillRect(s - 8, 3, 2, s / 3 - 2);

        g2.dispose();
    }

    @Override public int getIconWidth()  { return size; }
    @Override public int getIconHeight() { return size; }
}
