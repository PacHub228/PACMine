package com.voxel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * Packs all block tiles into one OpenGL texture. Tiles are loaded from
 * assets/<name>.png (16x16). Missing files fall back to a flat colour so the
 * world still renders while textures are being drawn.
 */
public class TextureAtlas {
    public static final int TILE = 16;

    // tile order in the atlas
    public static final int GRASS_TOP = 0, GRASS_SIDE = 1, DIRT = 2, STONE = 3,
                            WOOD_TOP = 4, WOOD_SIDE = 5, LEAVES = 6, SAND = 7, BEDROCK = 8;
    private static final String[] FILES = {
        "grass_top", "grass_side", "dirt", "stone", "wood_top", "wood_side", "leaves", "sand", "bedrock"
    };
    // fallback colours when a PNG is absent (rough match to block colours)
    private static final byte[] FB = block().clone();

    private final int cols, rows, atlasW, atlasH;
    private int texId;

    public TextureAtlas(String assetsDir) {
        int n = FILES.length;
        cols = 4; rows = (n + cols - 1) / cols;
        atlasW = cols * TILE; atlasH = rows * TILE;
        int[] pixels = new int[atlasW * atlasH]; // ARGB

        for (int i = 0; i < n; i++) {
            int[] tile = loadTile(assetsDir, FILES[i], i);
            int tx = (i % cols) * TILE, ty = (i / cols) * TILE;
            for (int y = 0; y < TILE; y++)
                for (int x = 0; x < TILE; x++)
                    pixels[(ty + y) * atlasW + (tx + x)] = tile[y * TILE + x];
        }
        upload(pixels);
    }

    private int[] loadTile(String dir, String name, int idx) {
        // accept a couple of common name variants
        String[] candidates = {name + ".png", name.replace("dirt", "dir") + ".png"};
        for (String c : candidates) {
            File f = new File(dir, c);
            if (!f.exists()) continue;
            try {
                BufferedImage img = ImageIO.read(f);
                int[] out = new int[TILE * TILE];
                for (int y = 0; y < TILE; y++)
                    for (int x = 0; x < TILE; x++) {
                        int sx = x * img.getWidth() / TILE, sy = y * img.getHeight() / TILE;
                        out[y * TILE + x] = img.getRGB(sx, sy);
                    }
                System.out.println("texture: " + c);
                return out;
            } catch (Exception e) {
                System.err.println("failed to read " + c + ": " + e.getMessage());
            }
        }
        // fallback solid colour
        int argb = 0xff000000 | ((FB[idx*3]&0xff)<<16) | ((FB[idx*3+1]&0xff)<<8) | (FB[idx*3+2]&0xff);
        int[] out = new int[TILE * TILE];
        java.util.Arrays.fill(out, argb);
        return out;
    }

    private void upload(int[] argb) {
        ByteBuffer buf = memAlloc(atlasW * atlasH * 4);
        for (int p : argb) {
            buf.put((byte) ((p >> 16) & 0xff)); // R
            buf.put((byte) ((p >> 8) & 0xff));  // G
            buf.put((byte) (p & 0xff));         // B
            buf.put((byte) ((p >> 24) & 0xff)); // A
        }
        buf.flip();
        texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, atlasW, atlasH, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        memFree(buf);
    }

    public void bind() { glBindTexture(GL_TEXTURE_2D, texId); }

    /** Loads a single PNG into its own GL texture (e.g. an HUD icon). Returns 0 if missing. */
    public static int loadStandalone(String path) {
        File f = new File(path);
        if (!f.exists()) return 0;
        try {
            BufferedImage img = ImageIO.read(f);
            int w = img.getWidth(), h = img.getHeight();
            ByteBuffer buf = memAlloc(w * h * 4);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int p = img.getRGB(x, y);
                    buf.put((byte) ((p >> 16) & 0xff));
                    buf.put((byte) ((p >> 8) & 0xff));
                    buf.put((byte) (p & 0xff));
                    buf.put((byte) ((p >> 24) & 0xff));
                }
            buf.flip();
            int id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, id);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            memFree(buf);
            return id;
        } catch (Exception e) {
            System.err.println("failed to load " + path + ": " + e.getMessage());
            return 0;
        }
    }

    /** {u0, v0, u1, v1} for a tile, with a half-texel inset to avoid bleeding. */
    public float[] uv(int tile) {
        int tx = (tile % cols) * TILE, ty = (tile / cols) * TILE;
        float inset = 0.01f;
        float u0 = (tx + inset) / atlasW, v0 = (ty + inset) / atlasH;
        float u1 = (tx + TILE - inset) / atlasW, v1 = (ty + TILE - inset) / atlasH;
        return new float[]{u0, v0, u1, v1};
    }

    /** Which tile a block face uses. dir: 0 top,1 bottom,2..5 sides. */
    public int tileFor(byte block, int dir) {
        switch (block) {
            case World.GRASS:  return dir == 0 ? GRASS_TOP : dir == 1 ? DIRT : GRASS_SIDE;
            case World.DIRT:   return DIRT;
            case World.STONE:  return STONE;
            case World.WOOD:   return (dir == 0 || dir == 1) ? WOOD_TOP : WOOD_SIDE;
            case World.LEAVES: return LEAVES;
            case World.SAND:   return SAND;
            case World.BEDROCK: return BEDROCK;
            default:           return STONE;
        }
    }

    // fallback colour table, same order as FILES
    private static byte[] block() {
        return new byte[]{
            (byte)90,(byte)168,(byte)69,    // grass_top
            (byte)120,(byte)95,(byte)55,    // grass_side
            (byte)140,(byte)102,(byte)64,   // dirt
            (byte)128,(byte)128,(byte)133,  // stone
            (byte)120,(byte)90,(byte)55,    // wood_top
            (byte)115,(byte)82,(byte)46,    // wood_side
            (byte)51,(byte)133,(byte)46,    // leaves
            (byte)217,(byte)204,(byte)140,  // sand
            (byte)45,(byte)45,(byte)48      // bedrock
        };
    }
}
