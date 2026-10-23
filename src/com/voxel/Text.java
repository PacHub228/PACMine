package com.voxel;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;

/**
 * Text renderer. Rasterises a system TrueType font (Segoe UI / Verdana /
 * DejaVu / Arial — whichever exists) into a texture atlas and draws strings as
 * textured quads, so Cyrillic gets real letter shapes instead of hand-drawn
 * 5x7 blobs. Two atlases are kept: one for UI-sized text and a high resolution
 * one for titles, each used at roughly its native size so nothing turns blurry.
 *
 * If AWT or the fonts are unavailable the renderer falls back to the original
 * bitmap font, so the game still works on a bare server install.
 */
public final class Text {
    private static final String[] FONTS = {
        "Segoe UI Semibold", "Segoe UI", "Tahoma", "Verdana", "DejaVu Sans", "Arial", "SansSerif"
    };
    private static final int SMALL_SIZE = 32, BIG_SIZE = 96;
    private static final int FIRST = 32, LAST = 0x2116;   // ASCII + Cyrillic + typographic extras
    private static final int COLS = 16;

    private static GlyphSet small, big;
    private static boolean failed = Boolean.getBoolean("pacmine.bitmapfont");

    private Text() {}

    /** Draw a string with its top-left at (x, y); px is the size of one "pixel" of the old font (cap height = 7*px). */
    public static void draw(String s, float x, float y, float px) {
        if (s == null || s.isEmpty()) return;
        GlyphSet set = setFor(7 * px);
        if (set == null) { Font5x7.drawBitmap(s, x, y, px); return; }

        // scale so that the cap height (not the whole line box) is 7*px,
        // which keeps every call site's sizes comparable with the old font
        float scale = (7 * px) / set.capH;
        boolean hadBlend = glIsEnabled(GL_BLEND);
        if (!hadBlend) { glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); }
        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, set.tex);

        float cursor = x;
        float w = set.cellW * scale, h = set.cellH * scale;
        float y0 = y - set.capTop * scale;
        glBegin(GL_QUADS);
        for (int i = 0; i < s.length(); i++) {
            int cp = codepoint(s.charAt(i));
            if (cp < 0 || cp >= set.has.length || !set.has[cp]) { cursor += 4 * px; continue; }
            glTexCoord2f(set.u0[cp], set.v0[cp]); glVertex2f(cursor, y0);
            glTexCoord2f(set.u1[cp], set.v0[cp]); glVertex2f(cursor + w, y0);
            glTexCoord2f(set.u1[cp], set.v1[cp]); glVertex2f(cursor + w, y0 + h);
            glTexCoord2f(set.u0[cp], set.v1[cp]); glVertex2f(cursor, y0 + h);
            cursor += set.adv[cp] * scale;
        }
        glEnd();

        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_TEXTURE_2D);
        if (!hadBlend) glDisable(GL_BLEND);
    }

    /** Pixel width of the string at that size (same scale as draw). */
    public static float width(String s, float px) {
        if (s == null || s.isEmpty()) return 0;
        GlyphSet set = setFor(7 * px);
        if (set == null) return Font5x7.width(s, px);
        float scale = (7 * px) / set.capH;
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            int cp = codepoint(s.charAt(i));
            w += (cp >= 0 && cp < set.adv.length && set.has[cp]) ? set.adv[cp] * scale : 4 * px;
        }
        return w;
    }

    private static int codepoint(char c) {
        char up = Character.toUpperCase(c);
        return up >= FIRST && up <= LAST ? up : 0;
    }

    private static GlyphSet setFor(float pixels) {
        if (failed) return null;
        if (pixels <= 44) {
            if (small == null) small = build(SMALL_SIZE);
            return small;
        }
        if (big == null) big = build(BIG_SIZE);
        return big != null ? big : small;
    }

    // ---------------- atlas ----------------

    private static final class GlyphSet {
        int tex;
        final float cellW, cellH;
        int capTop;      // distance from the cell top to the top of a capital letter
        int capH = 1;    // capital letter height in atlas pixels
        final float[] u0 = new float[LAST + 1], v0 = new float[LAST + 1];
        final float[] u1 = new float[LAST + 1], v1 = new float[LAST + 1];
        final float[] adv = new float[LAST + 1];
        final boolean[] has = new boolean[LAST + 1];

        GlyphSet(int tex, float cellW, float cellH) { this.tex = tex; this.cellW = cellW; this.cellH = cellH; }
    }

    private static Font pickFont() {
        for (String name : FONTS) {
            Font f = new Font(name, Font.PLAIN, 12);
            if (f.getFamily().equalsIgnoreCase(name) || f.canDisplay('А')) return f;
        }
        return null;
    }

    private static GlyphSet build(int size) {
        try {
            Font base = pickFont();
            if (base == null) { failed = true; return null; }
            Font font = base.deriveFont(Font.PLAIN, (float) size);

            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D pg = probe.createGraphics();
            pg.setFont(font);
            FontMetrics fm = pg.getFontMetrics();
            pg.dispose();

            List<Integer> chars = new ArrayList<>();
            int maxAdv = 1;
            for (int c = FIRST; c <= LAST; c++) {
                if (!printable(c)) continue;
                chars.add(c);
                maxAdv = Math.max(maxAdv, fm.charWidth(c));
            }
            int cellW = maxAdv + 4;
            int cellH = fm.getHeight() + 2;
            int rows = (chars.size() + COLS - 1) / COLS;

            BufferedImage atlas = new BufferedImage(COLS * cellW, rows * cellH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = atlas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            g.setFont(font);
            g.setColor(Color.WHITE);

            GlyphSet set = new GlyphSet(0, cellW, cellH);
            for (int i = 0; i < chars.size(); i++) {
                int c = chars.get(i);
                int cx = (i % COLS) * cellW;
                int cy = (i / COLS) * cellH;
                g.drawString(new String(Character.toChars(c)), cx + 2, cy + fm.getAscent());
                set.has[c] = true;
                set.adv[c] = fm.charWidth(c);
                set.u0[c] = cx / (float) atlas.getWidth();
                set.v0[c] = cy / (float) atlas.getHeight();
                set.u1[c] = (cx + cellW) / (float) atlas.getWidth();
                set.v1[c] = (cy + cellH) / (float) atlas.getHeight();
            }
            g.dispose();

            // measure a capital letter: its height and offset from the cell top
            int probeChar = set.has[0x410] ? 0x410 : set.has['H'] ? 'H' : -1;
            if (probeChar >= 0) {
                int idx = chars.indexOf(probeChar);
                int cx = (idx % COLS) * cellW, cy = (idx / COLS) * cellH;
                int top = -1, bottom = -1;
                for (int yy = 0; yy < cellH; yy++) {
                    boolean any = false;
                    for (int xx = 0; xx < cellW; xx++)
                        if (((atlas.getRGB(cx + xx, cy + yy) >>> 24) & 0xff) > 12) { any = true; break; }
                    if (any) { if (top < 0) top = yy; bottom = yy; }
                }
                if (bottom > top) { set.capTop = Math.max(0, top); set.capH = bottom - top + 1; }
            }

            set.tex = upload(atlas);
            return set;
        } catch (Throwable t) {
            System.err.println("text: falling back to the bitmap font (" + t + ")");
            failed = true;
            return null;
        }
    }

    private static boolean printable(int c) {
        if (c < FIRST) return false;
        if (c <= 126) return true;
        if (c >= 0x400 && c <= 0x45F) return true;      // Cyrillic block
        return c == 0xB0 || c == 0x2014 || c == 0x2026 || c == 0x2116;   // ° — … №
    }

    private static int upload(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
        int[] px = new int[w * h];
        img.getRGB(0, 0, w, h, px, 0, w);
        for (int p : px) {
            buf.put((byte) ((p >> 16) & 0xff));
            buf.put((byte) ((p >> 8) & 0xff));
            buf.put((byte) (p & 0xff));
            buf.put((byte) ((p >>> 24) & 0xff));
        }
        buf.flip();
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }
}
