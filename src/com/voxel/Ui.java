package com.voxel;

import static org.lwjgl.opengl.GL11.*;

/**
 * Immediate-mode widgets for the menu screens: Minecraft-style grey buttons,
 * inset text fields, list rows, panels and labels with hard pixel shadows.
 *
 * Everything is drawn as untextured quads in screen space, so the caller is
 * expected to have set up an orthographic projection with (0,0) top-left.
 */
public final class Ui {
    private Ui() {}

    public static void color(float r, float g, float b, float a) { glColor4f(r, g, b, a); }

    public static void quad(float x0, float y0, float x1, float y1) {
        glBegin(GL_QUADS);
        glVertex2f(x0, y0); glVertex2f(x1, y0);
        glVertex2f(x1, y1); glVertex2f(x0, y1);
        glEnd();
    }

    /**
     * A Minecraft-style button: bevelled grey body, dark border, white label
     * with a hard shadow, blue tint on hover and a dimmed look when disabled.
     * px is the preferred text pixel size; it is shrunk automatically so long
     * labels never overflow the button.
     */
    public static void button(float x, float y, float w, float h, String label,
                              boolean hover, boolean enabled, float px) {
        float b = Math.max(1f, h * 0.05f);

        color(0, 0, 0, 0.45f);
        quad(x + 2, y + 2, x + w + 2, y + h + 2);                 // drop shadow

        if (!enabled)      color(0.26f, 0.26f, 0.27f, 1f);
        else if (hover)    color(0.35f, 0.43f, 0.62f, 1f);
        else               color(0.43f, 0.43f, 0.44f, 1f);
        quad(x, y, x + w, y + h);                                  // body

        color(1, 1, 1, 0.16f);                                     // top highlight
        quad(x, y, x + w, y + b * 1.8f);
        color(0, 0, 0, 0.25f);                                     // bottom shade
        quad(x, y + h - b * 1.8f, x + w, y + h);

        color(0.05f, 0.05f, 0.06f, 0.95f);                         // border
        quad(x, y, x + w, y + b); quad(x, y + h - b, x + w, y + h);
        quad(x, y, x + b, y + h); quad(x + w - b, y, x + w, y + h);

        // shrink to fit using the real font metrics (not the old 5x7 grid)
        float maxPx = (w - 6 * b) / Math.max(1f, Text.width(label, 1f));
        float s = Math.min(px, maxPx);
        float tw = Text.width(label, s), th = 7 * s;
        float tx = x + (w - tw) / 2, ty = y + (h - th) / 2;
        float off = Math.max(1, s * 0.35f);
        color(0, 0, 0, enabled ? 0.55f : 0.35f);
        Text.draw(label, tx + off, ty + off, s);
        if (enabled) color(1, 1, 1, 1); else color(0.60f, 0.60f, 0.62f, 1);
        Text.draw(label, tx, ty, s);
        color(1, 1, 1, 1);
    }

    /** Inset text box; focused boxes get a light border and a blinking caret. */
    public static void field(float x, float y, float w, float h, String value,
                             boolean focused, float px, boolean caretOn) {
        float b = Math.max(1f, h * 0.05f);
        color(0.04f, 0.04f, 0.05f, 0.92f);
        quad(x, y, x + w, y + h);
        if (focused) color(0.85f, 0.90f, 0.75f, 1f); else color(0.48f, 0.50f, 0.55f, 1f);
        quad(x, y, x + w, y + b); quad(x, y + h - b, x + w, y + h);
        quad(x, y, x + b, y + h); quad(x + w - b, y, x + w, y + h);

        float s = Math.min(px, (h - 5 * b) / 7f);
        float ty = y + (h - 7 * s) / 2;
        color(1, 1, 1, value.isEmpty() && !focused ? 0.35f : 1f);
        Text.draw(value, x + 3 * b, ty, s);
        if (focused && caretOn) {
            float cx = x + 3 * b + Text.width(value, s) + Math.max(1, s * 0.4f);
            color(0.95f, 0.95f, 0.85f, 0.9f);
            quad(cx, ty - b, cx + Math.max(1.5f, s * 0.5f), ty + 7 * s + b);
        }
        color(1, 1, 1, 1);
    }

    /** Label with a hard pixel shadow. */
    public static void label(String text, float x, float y, float px,
                             float r, float g, float b, float a) {
        float off = Math.max(1, px * 0.35f);
        color(0, 0, 0, a * 0.55f);
        Text.draw(text, x + off, y + off, px);
        color(r, g, b, a);
        Text.draw(text, x, y, px);
        color(1, 1, 1, 1);
    }

    public static void labelCentered(String text, float cx, float y, float px,
                                     float r, float g, float b, float a) {
        label(text, cx - Text.width(text, px) / 2, y, px, r, g, b, a);
    }

    /** Semi-transparent dark panel used behind every list. */
    public static void panel(float x, float y, float w, float h, float alpha) {
        color(0, 0, 0, alpha);
        quad(x, y, x + w, y + h);
        color(1, 1, 1, 0.10f);
        quad(x, y, x + w, y + 1); quad(x, y + h - 1, x + w, y + h);
        quad(x, y, x + 1, y + h); quad(x + w - 1, y, x + w, y + h);
    }

    /** One list entry background: selected rows are blue, hovered ones lighter. */
    public static void listRow(float x, float y, float w, float h, boolean selected, boolean hover) {
        if (selected)   color(0.28f, 0.36f, 0.55f, 0.90f);
        else if (hover) color(0.22f, 0.24f, 0.30f, 0.80f);
        else            color(0.09f, 0.10f, 0.12f, 0.55f);
        quad(x, y, x + w, y + h);
        color(1, 1, 1, 0.06f);
        quad(x, y + h - 1, x + w, y + h);
    }
}
