package com.voxel;

/**
 * Voxel world divided into vertical-column chunks of CHUNK x CHUNK. The
 * horizontal size is configurable (in chunks); height is fixed. Terrain is
 * generated from layered value noise, and each chunk keeps its own mesh so
 * block edits only rebuild one chunk.
 */
public class World {
    public static final int CHUNK = 14;   // chunk footprint in blocks
    public static final int SY = 64;      // world height (fixed)

    // Block ids
    public static final byte AIR = 0, GRASS = 1, DIRT = 2, STONE = 3, WOOD = 4, LEAVES = 5, SAND = 6, BEDROCK = 7, COAL = 8;

    public final int cx, cz;   // size in chunks
    public final int sx, sz;   // size in blocks
    private final byte[] blocks;

    /** Generate a fresh world of `chunks` x `chunks`. */
    public World(long seed, int chunks, boolean bedrockLayer) {
        this.cx = chunks; this.cz = chunks;
        this.sx = chunks * CHUNK; this.sz = chunks * CHUNK;
        this.blocks = new byte[sx * SY * sz];
        generate(seed, bedrockLayer);
    }

    /** Load an existing world from saved block data. */
    public World(int chunks, byte[] data) {
        this.cx = chunks; this.cz = chunks;
        this.sx = chunks * CHUNK; this.sz = chunks * CHUNK;
        this.blocks = data;
    }

    public byte[] data() { return blocks; }

    private int idx(int x, int y, int z) { return (y * sz + z) * sx + x; }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < sx && y >= 0 && y < SY && z >= 0 && z < sz;
    }

    public byte get(int x, int y, int z) {
        if (!inBounds(x, y, z)) return AIR;
        return blocks[idx(x, y, z)];
    }

    public void set(int x, int y, int z, byte b) {
        if (!inBounds(x, y, z)) return;
        blocks[idx(x, y, z)] = b;
    }

    public boolean isSolid(int x, int y, int z) {
        return get(x, y, z) != AIR;
    }

    // ---- terrain generation ----
    private void generate(long seed, boolean bedrockLayer) {
        Noise n = new Noise(seed);
        java.util.Random rnd = new java.util.Random(seed);
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                double e = 0;
                e += n.noise(x * 0.015, z * 0.015) * 1.0;
                e += n.noise(x * 0.04, z * 0.04) * 0.4;
                e += n.noise(x * 0.09, z * 0.09) * 0.15;
                e /= 1.55;                       // normalize to ~[-1,1]
                int h = (int) (24 + e * 16);     // surface height
                if (h < 1) h = 1; if (h >= SY) h = SY - 1;

                for (int y = 0; y <= h; y++) {
                    byte b;
                    if (y == 0 && bedrockLayer) b = BEDROCK;
                    else if (y == h) b = (h < 20) ? SAND : GRASS;
                    else if (y > h - 4) b = DIRT;
                    else             b = (rnd.nextDouble() < 0.03) ? COAL : STONE; // coal ore in stone
                    set(x, y, z, b);
                }

                // occasional tree on grass
                if (get(x, h, z) == GRASS && n.noise(x * 1.7 + 13, z * 1.7 + 7) > 0.8) {
                    plantTree(x, h + 1, z);
                }
            }
        }
    }

    private void plantTree(int x, int y, int z) {
        int trunk = 4;
        for (int i = 0; i < trunk; i++) set(x, y + i, z, WOOD);
        int top = y + trunk;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -1; dy <= 1; dy++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    if (get(x + dx, top + dy, z + dz) == AIR)
                        set(x + dx, top + dy, z + dz, LEAVES);
                }
        set(x, top + 2, z, LEAVES);
    }

    // ---- colors per block (r,g,b), kept for reference ----
    public static float[] color(byte b) {
        switch (b) {
            case GRASS:  return new float[]{0.35f, 0.66f, 0.27f};
            case DIRT:   return new float[]{0.55f, 0.40f, 0.25f};
            case STONE:  return new float[]{0.50f, 0.50f, 0.52f};
            case WOOD:   return new float[]{0.45f, 0.32f, 0.18f};
            case LEAVES: return new float[]{0.20f, 0.52f, 0.18f};
            case SAND:   return new float[]{0.85f, 0.80f, 0.55f};
            case BEDROCK: return new float[]{0.18f, 0.18f, 0.19f};
            case COAL:   return new float[]{0.30f, 0.30f, 0.32f};
            default:     return new float[]{1f, 0f, 1f};
        }
    }
}
