package com.logobjectjson.ui.fold;

import java.awt.*;

/**
 * Fold icons drawn pixel by pixel (in the style of old *.ico files).
 * Each map is 11×11 with 2 px strokes: '#' = painted pixel.
 *
 *   ⌄ DOWN ARROWHEAD     (U+2304)  opens,  expanded  → collapse
 *   ⌃ UP ARROWHEAD       (U+2303)  closes, expanded  → collapse
 *   » RIGHT DOUBLE ANGLE (U+00BB)  opens,  collapsed → expand
 *   « LEFT DOUBLE ANGLE  (U+00AB)  closes, collapsed → expand
 */
public final class FoldIcons {

    /** Icon side in pixels (2 px strokes so they stand out). */
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

    /** Paints the icon with its top-left corner at (x, y). */
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
