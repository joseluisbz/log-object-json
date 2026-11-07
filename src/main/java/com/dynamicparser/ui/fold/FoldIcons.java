package com.dynamicparser.ui.fold;

import java.awt.*;

/**
 * Íconos de plegado dibujados píxel a píxel (al estilo de los antiguos *.ico).
 * Cada mapa es de 11×11 con trazos de 2 px: '#' = píxel pintado.
 *
 *   ⌄ DOWN ARROWHEAD  (U+2304)  abre,   expandido → colapsar
 *   ⌃ UP ARROWHEAD    (U+2303)  cierra, expandido → colapsar
 *   » RIGHT DOUBLE ANGLE (U+00BB) abre,   colapsado → expandir
 *   « LEFT DOUBLE ANGLE  (U+00AB) cierra, colapsado → expandir
 */
public final class FoldIcons {

    /** Lado del ícono en píxeles (trazos de 2 px para que se noten). */
    public static final int SIZE = 11;

    public enum Kind { DOWN, UP, RIGHT, LEFT }

    private static final String[] DOWN = {
            "...........",
            "...........",
            "##.......##",
            "###.....###",
            ".###...###.",
            "..###.###..",
            "...#####...",
            "....###....",
            ".....#.....",
            "...........",
            "...........",
    };
    private static final String[] UP = {
            "...........",
            "...........",
            ".....#.....",
            "....###....",
            "...#####...",
            "..###.###..",
            ".###...###.",
            "###.....###",
            "##.......##",
            "...........",
            "...........",
    };
    private static final String[] RIGHT = {
            "...........",
            "##...##....",
            "###..###...",
            ".###..###..",
            "..###..###.",
            "...##...##.",
            "..###..###.",
            ".###..###..",
            "###..###...",
            "##...##....",
            "...........",
    };
    private static final String[] LEFT = {
            "...........",
            "....##...##",
            "...###..###",
            "..###..###.",
            ".###..###..",
            ".##...##...",
            ".###..###..",
            "..###..###.",
            "...###..###",
            "....##...##",
            "...........",
    };

    private FoldIcons() { }

    /** Pinta el ícono con su esquina superior izquierda en (x, y). */
    public static void paint(Graphics g, Kind kind, int x, int y, Color color) {
        String[] map = switch (kind) {
            case DOWN -> DOWN;
            case UP -> UP;
            case RIGHT -> RIGHT;
            case LEFT -> LEFT;
        };
        g.setColor(color);
        for (int row = 0; row < SIZE; row++) {
            String r = map[row];
            for (int col = 0; col < SIZE; col++) {
                if (r.charAt(col) == '#') g.fillRect(x + col, y + row, 1, 1);
            }
        }
    }
}
