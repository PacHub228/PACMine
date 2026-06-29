package com.voxel;

/**
 * Voxel world: fixed-size, divided into vertical-column chunks of CHUNK x CHUNK.
 * Terrain is generated from layered value noise. Each chunk keeps its own mesh
 * (display list) so block edits only rebuild one chunk.
 */
public class World {
    public static final int SX = 128, SY = 64, SZ = 128; // world size in blocks
    public static final int CHUNK = 16;                  // chunk footprint
    public static final int CX = SX / CHUNK, CZ = SZ / CHUNK;

    // Block ids
    public static final byte AIR = 0, GRASS = 1, DIRT = 2, STONE = 3, WOOD = 4, LEAVES = 5, SAND = 6, BEDROCK = 7;

    private final byte[] blocks = new byte[SX * SY * SZ];

    public World(long seed) {
        generate(seed);
    }

    private int idx(int x, int y, int z) { return (y * SZ + z) * SX + x; }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < SX && y >= 0 && y < SY && z >= 0 && z < SZ;
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
    private void generate(long seed) {
        Noise n = new Noise(seed);
        for (int x = 0; x < SX; x++) {
            for (int z = 0; z < SZ; z++) {
                double e = 0;
                e += n.noise(x * 0.015, z * 0.015) * 1.0;
                e += n.noise(x * 0.04, z * 0.04) * 0.4;
                e += n.noise(x * 0.09, z * 0.09) * 0.15;
                e /= 1.55;                       // normalize to ~[-1,1]
                int h = (int) (24 + e * 16);     // surface height
                if (h < 1) h = 1; if (h >= SY) h = SY - 1;

                for (int y = 0; y <= h; y++) {
                    byte b;
                    if (y == 0)      b = BEDROCK;
                    else if (y == h) b = (h < 20) ? SAND : GRASS;
                    else if (y > h - 4) b = DIRT;
                    else             b = STONE;
                    set(x, y, z, b);
                }
                // a little water-table look: leave sand near low areas (handled above)

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

    // ---- colors per block (r,g,b) used by the renderer ----
    public static float[] color(byte b) {
        switch (b) {
            case GRASS:  return new float[]{0.35f, 0.66f, 0.27f};
            case DIRT:   return new float[]{0.55f, 0.40f, 0.25f};
            case STONE:  return new float[]{0.50f, 0.50f, 0.52f};
            case WOOD:   return new float[]{0.45f, 0.32f, 0.18f};
            case LEAVES: return new float[]{0.20f, 0.52f, 0.18f};
            case SAND:   return new float[]{0.85f, 0.80f, 0.55f};
            case BEDROCK: return new float[]{0.18f, 0.18f, 0.19f};
            default:     return new float[]{1f, 0f, 1f};
        }
    }
}
