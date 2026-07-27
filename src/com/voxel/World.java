package com.voxel;

import java.util.HashMap;
import java.util.Map;

/**
 * Voxel world divided into vertical-column chunks of CHUNK x CHUNK. Height is
 * fixed. Two backends:
 *  - finite: a single flat array (saveable, used by multiplayer);
 *  - infinite: chunks generated on demand and streamed around the player.
 */
public class World {
    public static final int CHUNK = 14;   // chunk footprint in blocks
    public static final int SY = 64;      // world height (fixed)

    public static final byte AIR = 0, GRASS = 1, DIRT = 2, STONE = 3, WOOD = 4, LEAVES = 5, SAND = 6, BEDROCK = 7, COAL = 8, IRON = 9, WATER = 10, LAVA = 11,
                             PLANKS = 12, FURNACE = 13, GLASS = 14;
    // ids >= 16 are items: they live in the inventory but can't be placed
    public static final byte INGOT_IRON = 16;
    public static boolean isItem(byte b) { return b >= 16; }
    public static final int SEA = 19;     // water fills terrain up to this height

    public final boolean infinite;
    public final int cx, cz;   // size in chunks (finite only)
    public final int sx, sz;   // size in blocks (finite only)

    // finite backend
    private final byte[] blocks;
    // infinite backend
    private final Map<Long, byte[]> chunkMap;
    private final long seed;
    private final boolean bedrockLayer;
    private Noise noise;

    /** Generate a fresh finite world of `chunks` x `chunks`. */
    public World(long seed, int chunks, boolean bedrockLayer) {
        this.infinite = false; this.seed = seed; this.bedrockLayer = bedrockLayer;
        this.cx = chunks; this.cz = chunks;
        this.sx = chunks * CHUNK; this.sz = chunks * CHUNK;
        this.blocks = new byte[sx * SY * sz];
        this.chunkMap = null;
        generateFinite(seed, bedrockLayer);
    }

    /** Load an existing finite world from saved block data. */
    public World(int chunks, byte[] data) {
        this.infinite = false; this.seed = 0; this.bedrockLayer = true;
        this.cx = chunks; this.cz = chunks;
        this.sx = chunks * CHUNK; this.sz = chunks * CHUNK;
        this.blocks = data;
        this.chunkMap = null;
    }

    private World(long seed, boolean bedrockLayer) {
        this.infinite = true; this.seed = seed; this.bedrockLayer = bedrockLayer;
        this.cx = 0; this.cz = 0; this.sx = 0; this.sz = 0;
        this.blocks = null;
        this.chunkMap = new HashMap<>();
        this.noise = new Noise(seed);
    }

    /** Create an endless streaming world. */
    public static World createInfinite(long seed, boolean bedrockLayer) {
        return new World(seed, bedrockLayer);
    }

    public byte[] data() { return blocks; }
    public long seed() { return seed; }

    /** Loaded chunks (infinite world) — the ones generated near the player. */
    public Map<Long, byte[]> exportChunks() { return chunkMap; }
    public void importChunk(int cx, int cz, byte[] data) { chunkMap.put(key(cx, cz), data); }
    public static int chunkCX(long k) { return (int) (k >> 32); }
    public static int chunkCZ(long k) { return (int) (long) k; }
    /** Chunk-map key for the chunk containing block (x,z). */
    public static long chunkKeyFor(int x, int z) {
        return key(Math.floorDiv(x, CHUNK), Math.floorDiv(z, CHUNK));
    }
    /** Force-generate the chunk containing block (x,z) (infinite worlds). */
    public void ensureChunk(int x, int z) { if (infinite) get(x, 0, z); }

    // ---- access ----
    public boolean inBounds(int x, int y, int z) {
        if (y < 0 || y >= SY) return false;
        if (infinite) return true;
        return x >= 0 && x < sx && z >= 0 && z < sz;
    }

    public byte get(int x, int y, int z) {
        if (y < 0 || y >= SY) return AIR;
        if (infinite) {
            byte[] c = chunk(Math.floorDiv(x, CHUNK), Math.floorDiv(z, CHUNK));
            return c[localIdx(Math.floorMod(x, CHUNK), y, Math.floorMod(z, CHUNK))];
        }
        if (x < 0 || x >= sx || z < 0 || z >= sz) return AIR;
        return blocks[(y * sz + z) * sx + x];
    }

    public void set(int x, int y, int z, byte b) {
        if (y < 0 || y >= SY) return;
        if (infinite) {
            byte[] c = chunk(Math.floorDiv(x, CHUNK), Math.floorDiv(z, CHUNK));
            c[localIdx(Math.floorMod(x, CHUNK), y, Math.floorMod(z, CHUNK))] = b;
            return;
        }
        if (x < 0 || x >= sx || z < 0 || z >= sz) return;
        blocks[(y * sz + z) * sx + x] = b;
    }

    public boolean isSolid(int x, int y, int z) {
        byte b = get(x, y, z);
        return b != AIR && b != WATER && b != LAVA;   // liquids are passable
    }

    public static boolean isLiquid(byte b) { return b == WATER || b == LAVA; }

    /** True for real diggable rock/dirt — anything a cave carve is allowed to hollow out. */
    private static boolean isSolidGround(byte b) { return b != AIR && !isLiquid(b); }

    private static int localIdx(int lx, int y, int lz) { return (y * CHUNK + lz) * CHUNK + lx; }
    private static long key(int cx, int cz) { return (((long) cx) << 32) ^ (cz & 0xffffffffL); }

    /** Get or generate an infinite-world chunk. */
    private byte[] chunk(int cx, int cz) {
        byte[] c = chunkMap.get(key(cx, cz));
        if (c == null) { c = new byte[CHUNK * SY * CHUNK]; chunkMap.put(key(cx, cz), c); fillChunk(cx, cz, c); }
        return c;
    }

    // ---- generation ----
    private void generateFinite(long seed, boolean bedrock) {
        Noise n = new Noise(seed);
        java.util.Random rnd = new java.util.Random(seed);
        for (int x = 0; x < sx; x++)
            for (int z = 0; z < sz; z++)
                genColumn(n, x, z, bedrock, 0, 0, null);
        // caves first (so ore veins below never end up floating in open air)
        carveCaves(rnd, 0, 0, sx, sz, 0, 0, null);
        // ore veins
        int area = sx * sz;
        for (int i = 0; i < Math.max(8, area / 12); i++) placeVein(rnd, COAL, 4 + rnd.nextInt(6), 4, SY - 6);
        for (int i = 0; i < Math.max(3, area / 55); i++) placeVein(rnd, IRON, 3 + rnd.nextInt(4), 1, 16);
    }

    /** Fill one infinite-world chunk (terrain + clipped trees, caves and ores). */
    private void fillChunk(int cx, int cz, byte[] c) {
        int bx0 = cx * CHUNK, bz0 = cz * CHUNK;
        for (int lx = 0; lx < CHUNK; lx++)
            for (int lz = 0; lz < CHUNK; lz++)
                genColumn(noise, bx0 + lx, bz0 + lz, bedrockLayer, bx0, bz0, c);
        // deterministic caves + ores per chunk
        java.util.Random rnd = new java.util.Random(seed ^ (key(cx, cz) * 0x9E3779B97F4A7C15L));
        carveCaves(rnd, bx0, bz0, CHUNK, CHUNK, bx0, bz0, c);
        for (int i = 0; i < 3; i++) veinInChunk(c, rnd, COAL, 4 + rnd.nextInt(6), 4, SY - 6);
        if (rnd.nextDouble() < 0.8) veinInChunk(c, rnd, IRON, 3 + rnd.nextInt(4), 1, 16);
    }

    /**
     * Generate a single terrain column. If `c` is non-null we write into that
     * chunk array (infinite, clipped to chunk); otherwise into the flat array.
     */
    private void genColumn(Noise n, int x, int z, boolean bedrock, int bx0, int bz0, byte[] c) {
        double e = 0;
        e += n.noise(x * 0.015, z * 0.015) * 1.0;
        e += n.noise(x * 0.04, z * 0.04) * 0.4;
        e += n.noise(x * 0.09, z * 0.09) * 0.15;
        e /= 1.55;
        int h = (int) (24 + e * 16);
        if (h < 1) h = 1; if (h >= SY) h = SY - 1;
        for (int y = 0; y <= h; y++) {
            byte b;
            if (y == 0 && bedrock) b = BEDROCK;
            else if (y == h) b = (h < 20) ? SAND : GRASS;
            else if (y > h - 4) b = DIRT;
            else b = STONE;
            put(x, y, z, b, bx0, bz0, c);
        }
        // sea: flood everything below SEA level
        for (int y = h + 1; y <= SEA; y++) put(x, y, z, WATER, bx0, bz0, c);
        // underground lava pockets just above the bedrock
        if (n.noise(x * 0.08 + 99, z * 0.08 - 7) > 0.78)
            for (int y = 2; y <= 4 && y < h - 4; y++) put(x, y, z, LAVA, bx0, bz0, c);
        if (getCol(x, h, z, bx0, bz0, c) == GRASS && n.noise(x * 1.7 + 13, z * 1.7 + 7) > 0.8)
            plantTree(x, h + 1, z, bx0, bz0, c);
    }

    private void plantTree(int x, int y, int z, int bx0, int bz0, byte[] c) {
        int trunk = 4;
        for (int i = 0; i < trunk; i++) put(x, y + i, z, WOOD, bx0, bz0, c);
        int top = y + trunk;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -1; dy <= 1; dy++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    if (getCol(x + dx, top + dy, z + dz, bx0, bz0, c) == AIR)
                        put(x + dx, top + dy, z + dz, LEAVES, bx0, bz0, c);
                }
        put(x, top + 2, z, LEAVES, bx0, bz0, c);
    }

    /** Write helper used during generation (flat array or clipped chunk). */
    private void put(int x, int y, int z, byte b, int bx0, int bz0, byte[] c) {
        if (y < 0 || y >= SY) return;
        if (c == null) { if (x >= 0 && x < sx && z >= 0 && z < sz) blocks[(y * sz + z) * sx + x] = b; return; }
        int lx = x - bx0, lz = z - bz0;
        if (lx < 0 || lx >= CHUNK || lz < 0 || lz >= CHUNK) return;   // clip to chunk
        c[localIdx(lx, y, lz)] = b;
    }
    private byte getCol(int x, int y, int z, int bx0, int bz0, byte[] c) {
        if (y < 0 || y >= SY) return AIR;
        if (c == null) return (x >= 0 && x < sx && z >= 0 && z < sz) ? blocks[(y * sz + z) * sx + x] : AIR;
        int lx = x - bx0, lz = z - bz0;
        if (lx < 0 || lx >= CHUNK || lz < 0 || lz >= CHUNK) return AIR;
        return c[localIdx(lx, y, lz)];
    }

    private void placeVein(java.util.Random rnd, byte ore, int size, int yMin, int yMax) {
        int x = rnd.nextInt(sx), z = rnd.nextInt(sz), y = yMin + rnd.nextInt(Math.max(1, yMax - yMin));
        for (int i = 0; i < size; i++) {
            if (get(x, y, z) == STONE) set(x, y, z, ore);
            switch (rnd.nextInt(6)) { case 0: x++; break; case 1: x--; break; case 2: y++; break; case 3: y--; break; case 4: z++; break; case 5: z--; break; }
            x = Math.max(0, Math.min(sx - 1, x)); y = Math.max(yMin, Math.min(yMax, y)); z = Math.max(0, Math.min(sz - 1, z));
        }
    }
    private void veinInChunk(byte[] c, java.util.Random rnd, byte ore, int size, int yMin, int yMax) {
        int lx = rnd.nextInt(CHUNK), lz = rnd.nextInt(CHUNK), y = yMin + rnd.nextInt(Math.max(1, yMax - yMin));
        for (int i = 0; i < size; i++) {
            if (c[localIdx(lx, y, lz)] == STONE) c[localIdx(lx, y, lz)] = ore;
            switch (rnd.nextInt(6)) { case 0: lx++; break; case 1: lx--; break; case 2: y++; break; case 3: y--; break; case 4: lz++; break; case 5: lz--; break; }
            lx = Math.max(0, Math.min(CHUNK - 1, lx)); y = Math.max(yMin, Math.min(yMax, y)); lz = Math.max(0, Math.min(CHUNK - 1, lz));
        }
    }

    // ---- caves: winding wormlike tunnels, small and large, carved before ore veins ----
    private static final int CAVE_Y_MIN = 3, CAVE_Y_MAX = 42;

    /**
     * Scatter cave worms across a region: mostly small winding tunnels, plus
     * occasional bigger caverns. Counts scale with area so the same call
     * works for one chunk (infinite worlds) or a whole finite map. Worms
     * that wander outside the current chunk are clipped by put(), same as
     * tree leaves near chunk edges.
     */
    private static final int CAVE_SURFACE_BUFFER = 5;   // min. solid blocks a cave must leave below the surface

    private void carveCaves(java.util.Random rnd, int originX, int originZ, int spanX, int spanZ, int bx0, int bz0, byte[] c) {
        // snapshot the terrain surface height (before any carving) so caves can
        // keep a safe buffer below it — otherwise a cave passing close under a
        // hill would hollow out the ground a tree (or grass) is resting on
        int[][] surfaceY = new int[spanX][spanZ];
        boolean[][] hasTree = new boolean[spanX][spanZ];
        for (int lx = 0; lx < spanX; lx++)
            for (int lz = 0; lz < spanZ; lz++) {
                int y = SY - 1;
                byte b;
                boolean tree = false;
                // skip air and tree parts: we want the ground height, not the treetop
                do {
                    b = getCol(originX + lx, y, originZ + lz, bx0, bz0, c);
                    if (b == WOOD) tree = true;
                    y--;
                } while (y > 0 && (b == AIR || b == WOOD || b == LEAVES));
                surfaceY[lx][lz] = y + 1;
                hasTree[lx][lz] = tree;
            }

        int yRange = CAVE_Y_MAX - CAVE_Y_MIN;
        int small = poissonish(rnd, spanX * spanZ / 280.0);
        for (int i = 0; i < small; i++) {
            int x = originX + rnd.nextInt(Math.max(1, spanX));
            int z = originZ + rnd.nextInt(Math.max(1, spanZ));
            int y = CAVE_Y_MIN + rnd.nextInt(yRange);
            carveCaveWorm(rnd, x, y, z, 1.1 + rnd.nextDouble() * 1.1, 22 + rnd.nextInt(26), bx0, bz0, c, originX, originZ, surfaceY, hasTree);
        }
        int large = poissonish(rnd, spanX * spanZ / 1400.0);
        for (int i = 0; i < large; i++) {
            int x = originX + rnd.nextInt(Math.max(1, spanX));
            int z = originZ + rnd.nextInt(Math.max(1, spanZ));
            int y = CAVE_Y_MIN + rnd.nextInt(yRange);
            carveCaveWorm(rnd, x, y, z, 2.6 + rnd.nextDouble() * 2.4, 7 + rnd.nextInt(9), bx0, bz0, c, originX, originZ, surfaceY, hasTree);
        }
        // a few deliberate surface entrances, so caves are actually findable while walking
        // around (otherwise the surface buffer above hides every cave completely)
        int entrances = poissonish(rnd, spanX * spanZ / 1800.0);
        for (int i = 0; i < entrances; i++) {
            int x = originX + rnd.nextInt(Math.max(1, spanX));
            int z = originZ + rnd.nextInt(Math.max(1, spanZ));
            carveSurfaceEntrance(rnd, x, z, bx0, bz0, c, originX, originZ, surfaceY, hasTree);
        }
    }

    /** Fractional expected count -> an actual integer count (e.g. mean 0.3 -> 1 roughly 30% of the time). */
    private static int poissonish(java.util.Random rnd, double mean) {
        int whole = (int) mean;
        return whole + (rnd.nextDouble() < mean - whole ? 1 : 0);
    }

    /** A worm walks in 3D, carving a blob at each step; direction wanders and radius breathes a little. */
    private void carveCaveWorm(java.util.Random rnd, int x, int y, int z, double radius, int steps, int bx0, int bz0, byte[] c,
                                int originX, int originZ, int[][] surfaceY, boolean[][] hasTree) {
        double dx = rnd.nextDouble() * 2 - 1, dy = (rnd.nextDouble() * 2 - 1) * 0.3, dz = rnd.nextDouble() * 2 - 1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz); if (len < 1e-6) len = 1;
        dx /= len; dy /= len; dz /= len;
        double px = x, py = y, pz = z, r = radius;
        for (int i = 0; i < steps; i++) {
            carveCaveBlob(px, py, pz, r, bx0, bz0, c, originX, originZ, surfaceY, hasTree, CAVE_SURFACE_BUFFER);
            dx += (rnd.nextDouble() * 2 - 1) * 0.35;
            dy += (rnd.nextDouble() * 2 - 1) * 0.15;
            dz += (rnd.nextDouble() * 2 - 1) * 0.35;
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz); if (l < 1e-6) l = 1;
            dx /= l; dy /= l; dz /= l;
            px += dx * 1.4; py += dy * 1.4; pz += dz * 1.4;
            r += (rnd.nextDouble() - 0.5) * 0.6;
            if (r < 0.8) r = 0.8;
            if (r > radius * 1.6) r = radius * 1.6;
            if (py < CAVE_Y_MIN || py > CAVE_Y_MAX) break;     // wandered out of the cave band
        }
    }

    /**
     * A deliberate cave mouth: starts right at the surface (skipping the usual
     * buffer for its own column) and dives down into the ground, so caves are
     * actually findable by walking around a hillside instead of only by luck.
     */
    private void carveSurfaceEntrance(java.util.Random rnd, int x, int z, int bx0, int bz0, byte[] c,
                                       int originX, int originZ, int[][] surfaceY, boolean[][] hasTree) {
        int lx = Math.max(0, Math.min(surfaceY.length - 1, x - originX));
        int lz = Math.max(0, Math.min(surfaceY[0].length - 1, z - originZ));
        int groundY = surfaceY[lx][lz];
        byte ground = getCol(x, groundY, z, bx0, bz0, c);
        if (ground == BEDROCK || ground == AIR || isLiquid(ground) || groundY < CAVE_Y_MIN + 6) return;

        double dx = rnd.nextDouble() * 2 - 1, dz = rnd.nextDouble() * 2 - 1, dy = -0.8;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz); dx /= len; dy /= len; dz /= len;
        double px = x, py = groundY, pz = z, r = 1.4 + rnd.nextDouble() * 0.8;
        int steps = 8 + rnd.nextInt(8);
        for (int i = 0; i < steps; i++) {
            carveCaveBlob(px, py, pz, r, bx0, bz0, c, originX, originZ, surfaceY, hasTree, 0);   // no buffer: this IS the opening
            dx += (rnd.nextDouble() * 2 - 1) * 0.3;
            dy += (rnd.nextDouble() * 2 - 1) * 0.2;
            dz += (rnd.nextDouble() * 2 - 1) * 0.3;
            if (dy > -0.15) dy = -0.15;                        // keep diving inward, don't curl back up
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz); dx /= l; dy /= l; dz /= l;
            px += dx * 1.3; py += dy * 1.3; pz += dz * 1.3;
            r += (rnd.nextDouble() - 0.5) * 0.4;
            if (r < 1.0) r = 1.0;
            if (r > 2.6) r = 2.6;
            if (py < CAVE_Y_MIN) break;
        }
    }

    /** Carve a squashed sphere of air, radius r, centred at (cx,cy,cz). Never touches bedrock, liquids,
     *  or (beyond `buffer` blocks of slack) the ground above the pre-carve surface. */
    private void carveCaveBlob(double cx, double cy, double cz, double r, int bx0, int bz0, byte[] c,
                                int originX, int originZ, int[][] surfaceY, boolean[][] hasTree, int buffer) {
        int r0 = (int) Math.ceil(r);
        int ix = (int) Math.round(cx), iy = (int) Math.round(cy), iz = (int) Math.round(cz);
        for (int dx = -r0; dx <= r0; dx++)
            for (int dy = -r0; dy <= r0; dy++)
                for (int dz = -r0; dz <= r0; dz++) {
                    double dist = Math.sqrt(dx * dx + (dy * 1.3) * (dy * 1.3) + dz * dz);
                    if (dist > r) continue;
                    int wx = ix + dx, wy = iy + dy, wz = iz + dz;
                    if (wy < 1 || wy >= SY - 1) continue;      // keep the bedrock/top layers intact
                    int lx = Math.max(0, Math.min(surfaceY.length - 1, wx - originX));
                    int lz = Math.max(0, Math.min(surfaceY[0].length - 1, wz - originZ));
                    int effBuffer = hasTree[lx][lz] ? Math.max(buffer, CAVE_SURFACE_BUFFER) : buffer;   // never undermine a tree
                    if (wy > surfaceY[lx][lz] - effBuffer) continue;
                    byte cur = getCol(wx, wy, wz, bx0, bz0, c);
                    if (cur == BEDROCK || !isSolidGround(cur)) continue;   // protect bedrock, skip existing air/liquid
                    put(wx, wy, wz, AIR, bx0, bz0, c);
                }
    }

    /**
     * Find a dry spawn column near (cx,cz): spiral outward until the surface
     * is solid ground, not water/lava. Returns {x, standY, z}; falls back to
     * the start column if everything within the radius is wet.
     */
    public int[] findDrySpawn(int cx, int cz, int radius) {
        for (int r = 0; r <= radius; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // ring only
                    int x = cx + dx, z = cz + dz;
                    if (!infinite && (x < 1 || x >= sx - 1 || z < 1 || z >= sz - 1)) continue;
                    int y = SY - 1;
                    byte b = AIR;
                    while (y > 0) {
                        b = get(x, y, z);
                        if (b != AIR && b != LEAVES && b != WOOD) break;
                        y--;
                    }
                    if (b != WATER && b != LAVA && b != AIR) return new int[]{x, y + 1, z};
                }
        int y = SY - 1;
        while (y > 0 && !isSolid(cx, y, cz)) y--;
        return new int[]{cx, y + 1, cz};
    }

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
            case IRON:   return new float[]{0.65f, 0.55f, 0.45f};
            case WATER:  return new float[]{0.25f, 0.45f, 0.85f};
            case LAVA:   return new float[]{0.95f, 0.45f, 0.10f};
            case PLANKS: return new float[]{0.62f, 0.45f, 0.26f};
            case FURNACE: return new float[]{0.42f, 0.42f, 0.44f};
            case GLASS:  return new float[]{0.75f, 0.85f, 0.92f};
            default:     return new float[]{1f, 0f, 1f};
        }
    }
}
