package com.voxel;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

/** Minimal 5x7 bitmap font for drawing menu text as filled quads (no textures). */
public class Font5x7 {
    private static final Map<Character, int[]> G = new HashMap<>();

    private static void put(char c, int... rows) { G.put(c, rows); }
    static {
        put(' ', 0,0,0,0,0,0,0);
        put('V', 0b10001,0b10001,0b10001,0b10001,0b10001,0b01010,0b00100);
        put('O', 0b01110,0b10001,0b10001,0b10001,0b10001,0b10001,0b01110);
        put('X', 0b10001,0b10001,0b01010,0b00100,0b01010,0b10001,0b10001);
        put('E', 0b11111,0b10000,0b10000,0b11110,0b10000,0b10000,0b11111);
        put('L', 0b10000,0b10000,0b10000,0b10000,0b10000,0b10000,0b11111);
        put('C', 0b01110,0b10001,0b10000,0b10000,0b10000,0b10001,0b01110);
        put('R', 0b11110,0b10001,0b10001,0b11110,0b10100,0b10010,0b10001);
        put('A', 0b01110,0b10001,0b10001,0b11111,0b10001,0b10001,0b10001);
        put('F', 0b11111,0b10000,0b10000,0b11110,0b10000,0b10000,0b10000);
        put('T', 0b11111,0b00100,0b00100,0b00100,0b00100,0b00100,0b00100);
        put('P', 0b11110,0b10001,0b10001,0b11110,0b10000,0b10000,0b10000);
        put('Y', 0b10001,0b10001,0b01010,0b00100,0b00100,0b00100,0b00100);
        put('Q', 0b01110,0b10001,0b10001,0b10001,0b10101,0b10010,0b01101);
        put('U', 0b10001,0b10001,0b10001,0b10001,0b10001,0b10001,0b01110);
        put('I', 0b11111,0b00100,0b00100,0b00100,0b00100,0b00100,0b11111);
        put('S', 0b01111,0b10000,0b10000,0b01110,0b00001,0b00001,0b11110);
        put('K', 0b10001,0b10010,0b10100,0b11000,0b10100,0b10010,0b10001);
        put('M', 0b10001,0b11011,0b10101,0b10101,0b10001,0b10001,0b10001);
        put('N', 0b10001,0b11001,0b10101,0b10011,0b10001,0b10001,0b10001);
    }

    /** Pixel width of a string at the given pixel size. */
    public static float width(String s, float px) { return s.length() * 6 * px - px; }

    /** Draw text with top-left at (x,y); each font pixel is px wide. Assumes texturing off. */
    public static void draw(String s, float x, float y, float px) {
        for (int i = 0; i < s.length(); i++) {
            int[] g = G.get(Character.toUpperCase(s.charAt(i)));
            if (g != null) {
                for (int row = 0; row < 7; row++)
                    for (int col = 0; col < 5; col++)
                        if ((g[row] & (1 << (4 - col))) != 0) {
                            float qx = x + col * px, qy = y + row * px;
                            glBegin(GL_QUADS);
                            glVertex2f(qx, qy); glVertex2f(qx + px, qy);
                            glVertex2f(qx + px, qy + px); glVertex2f(qx, qy + px);
                            glEnd();
                        }
            }
            x += 6 * px;
        }
    }
}
