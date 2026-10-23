package com.voxel;

import static org.lwjgl.opengl.GL11.*;

/**
 * Builds one OpenGL display list per chunk column. Only faces adjacent to air
 * are emitted, textured from the atlas.
 *
 * Two quality features are baked into the mesh:
 *  - per-face direction shading (top brighter than sides) so cubes read as 3D;
 *  - per-vertex ambient occlusion, the classic voxel trick: a vertex tucked
 *    into a corner is darkened by its solid neighbours, which is what makes
 *    cliffs, caves and house corners look genuinely three-dimensional.
 *
 * Chunks outside the view frustum are skipped before any GL call, both for
 * drawing and for expensive rebuilding.
 */
public class ChunkRenderer {
    private final World world;
    private final TextureAtlas atlas;

    // finite backend
    private int[] lists;
    private boolean[] dirty;

    // infinite (streaming) backend
    private final java.util.Map<Long, Integer> listByChunk = new java.util.HashMap<>();
    private final java.util.Set<Long> dirtyChunks = new java.util.HashSet<>();
    private static final int RENDER_DIST = 6;   // chunks in each direction (~84 blocks)

    // frustum culling
    private final Frustum frustum = new Frustum();
    private final java.nio.FloatBuffer projBuf = org.lwjgl.BufferUtils.createFloatBuffer(16);
    private final java.nio.FloatBuffer mvBuf = org.lwjgl.BufferUtils.createFloatBuffer(16);
    private final float[] proj = new float[16], mv = new float[16];
    private int drawn, culled;   // per-frame counters for the debug overlay

    private static final float TOP = 1.0f, BOTTOM = 0.5f, NS = 0.8f, EW = 0.65f;
    private static final float[] AO = { 0.50f, 0.68f, 0.85f, 1.0f };

    public ChunkRenderer(World world, TextureAtlas atlas) {
        this.world = world;
        this.atlas = atlas;
        if (!world.infinite) {
            lists = new int[world.cx * world.cz];
            dirty = new boolean[world.cx * world.cz];
            for (int i = 0; i < lists.length; i++) { lists[i] = glGenLists(1); dirty[i] = true; }
        }
    }

    private static long key(int cx, int cz) { return (((long) cx) << 32) ^ (cz & 0xffffffffL); }

    public void markDirty(int blockX, int blockZ) {
        int cx = Math.floorDiv(blockX, World.CHUNK), cz = Math.floorDiv(blockZ, World.CHUNK);
        mark(cx, cz);
        if (Math.floorMod(blockX, World.CHUNK) == 0) mark(cx - 1, cz);
        if (Math.floorMod(blockX, World.CHUNK) == World.CHUNK - 1) mark(cx + 1, cz);
        if (Math.floorMod(blockZ, World.CHUNK) == 0) mark(cx, cz - 1);
        if (Math.floorMod(blockZ, World.CHUNK) == World.CHUNK - 1) mark(cx, cz + 1);
    }

    private void mark(int cx, int cz) {
        if (world.infinite) { dirtyChunks.add(key(cx, cz)); return; }
        if (cx < 0 || cz < 0 || cx >= world.cx || cz >= world.cz) return;
        dirty[cz * world.cx + cx] = true;
    }

    /** Chunks drawn / culled in the last frame (debug overlay). */
    public int chunksDrawn() { return drawn; }
    public int chunksCulled() { return culled; }

    public void render(double px, double pz) {
        // the matrices are already set up by the caller
        projBuf.clear(); glGetFloatv(GL_PROJECTION_MATRIX, projBuf); projBuf.get(proj);
        mvBuf.clear(); glGetFloatv(GL_MODELVIEW_MATRIX, mvBuf); mvBuf.get(mv);
        frustum.set(proj, mv);

        drawn = 0; culled = 0;
        atlas.bind();
        if (world.infinite) { streamRender(px, pz); return; }
        for (int cz = 0; cz < world.cz; cz++) {
            for (int cx = 0; cx < world.cx; cx++) {
                int i = cz * world.cx + cx;
                double bx0 = cx * World.CHUNK, bz0 = cz * World.CHUNK;
                if (!frustum.boxVisible(bx0, 0, bz0, bx0 + World.CHUNK, World.SY, bz0 + World.CHUNK)) {
                    culled++;                 // stays dirty, rebuilt when it comes back into view
                    continue;
                }
                if (dirty[i]) { rebuild(cx, cz, lists[i]); dirty[i] = false; }
                glCallList(lists[i]);
                drawn++;
            }
        }
    }

    /** Build/draw chunks around the player and free those out of range. */
    private void streamRender(double px, double pz) {
        int pcx = Math.floorDiv((int) Math.floor(px), World.CHUNK);
        int pcz = Math.floorDiv((int) Math.floor(pz), World.CHUNK);
        int newBudget = 3;   // cap freshly generated chunks per frame to avoid hitches

        for (int cz = pcz - RENDER_DIST; cz <= pcz + RENDER_DIST; cz++) {
            for (int cx = pcx - RENDER_DIST; cx <= pcx + RENDER_DIST; cx++) {
                double bx0 = cx * (double) World.CHUNK, bz0 = cz * (double) World.CHUNK;
                boolean visible = frustum.boxVisible(bx0, 0, bz0, bx0 + World.CHUNK, World.SY, bz0 + World.CHUNK);
                long k = key(cx, cz);
                Integer list = listByChunk.get(k);
                if (list == null) {
                    if (!visible) { culled++; continue; }
                    if (newBudget <= 0) continue;          // build later frames
                    list = glGenLists(1);
                    listByChunk.put(k, list);
                    rebuild(cx, cz, list);
                    newBudget--;
                } else if (visible && dirtyChunks.remove(k)) {
                    rebuild(cx, cz, list);
                } else if (!visible) {
                    culled++;
                    continue;
                }
                glCallList(list);
                drawn++;
            }
        }
        // unload far chunks
        var it = listByChunk.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            int cx = (int) (en.getKey() >> 32), cz = (int) (long) en.getKey();
            if (Math.abs(cx - pcx) > RENDER_DIST + 2 || Math.abs(cz - pcz) > RENDER_DIST + 2) {
                glDeleteLists(en.getValue(), 1);
                it.remove();
            }
        }
    }

    private void rebuild(int cx, int cz, int list) {
        glNewList(list, GL_COMPILE);
        glBegin(GL_QUADS);
        int bx0 = cx * World.CHUNK, bz0 = cz * World.CHUNK;
        for (int x = bx0; x < bx0 + World.CHUNK; x++)
            for (int z = bz0; z < bz0 + World.CHUNK; z++)
                for (int y = 0; y < World.SY; y++) {
                    byte b = world.get(x, y, z);
                    if (b == World.AIR) continue;
                    if (World.isLiquid(b)) {
                        // liquids: only surfaces against air (no inner water-water faces)
                        if (world.get(x, y + 1, z) == World.AIR) face(x, y, z, b, TOP, 0);
                        if (world.get(x, y - 1, z) == World.AIR) face(x, y, z, b, BOTTOM, 1);
                        if (world.get(x, y, z + 1) == World.AIR) face(x, y, z, b, NS, 2);
                        if (world.get(x, y, z - 1) == World.AIR) face(x, y, z, b, NS, 3);
                        if (world.get(x + 1, y, z) == World.AIR) face(x, y, z, b, EW, 4);
                        if (world.get(x - 1, y, z) == World.AIR) face(x, y, z, b, EW, 5);
                        continue;
                    }
                    if (!world.isSolid(x, y + 1, z)) face(x, y, z, b, TOP, 0);
                    if (!world.isSolid(x, y - 1, z)) face(x, y, z, b, BOTTOM, 1);
                    if (!world.isSolid(x, y, z + 1)) face(x, y, z, b, NS, 2);
                    if (!world.isSolid(x, y, z - 1)) face(x, y, z, b, NS, 3);
                    if (!world.isSolid(x + 1, y, z)) face(x, y, z, b, EW, 4);
                    if (!world.isSolid(x - 1, y, z)) face(x, y, z, b, EW, 5);
                }
        glEnd();
        glEndList();
    }

    /** Emit one face; winding and UVs match the original renderer exactly. */
    private void face(int x, int y, int z, byte b, float shade, int dir) {
        float[] uv = atlas.uv(atlas.tileFor(b, dir));
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        float x0 = x, y0 = y, z0 = z, x1 = x + 1, y1 = y + 1, z1 = z + 1;
        switch (dir) {
            case 0: // top (+y)
                vert(x, y, z, dir, x0, y1, z0, u0, v1, shade); vert(x, y, z, dir, x0, y1, z1, u0, v0, shade);
                vert(x, y, z, dir, x1, y1, z1, u1, v0, shade); vert(x, y, z, dir, x1, y1, z0, u1, v1, shade); break;
            case 1: // bottom (-y)
                vert(x, y, z, dir, x0, y0, z0, u0, v0, shade); vert(x, y, z, dir, x1, y0, z0, u1, v0, shade);
                vert(x, y, z, dir, x1, y0, z1, u1, v1, shade); vert(x, y, z, dir, x0, y0, z1, u0, v1, shade); break;
            case 2: // +z
                vert(x, y, z, dir, x0, y0, z1, u0, v1, shade); vert(x, y, z, dir, x1, y0, z1, u1, v1, shade);
                vert(x, y, z, dir, x1, y1, z1, u1, v0, shade); vert(x, y, z, dir, x0, y1, z1, u0, v0, shade); break;
            case 3: // -z
                vert(x, y, z, dir, x1, y0, z0, u0, v1, shade); vert(x, y, z, dir, x0, y0, z0, u1, v1, shade);
                vert(x, y, z, dir, x0, y1, z0, u1, v0, shade); vert(x, y, z, dir, x1, y1, z0, u0, v0, shade); break;
            case 4: // +x
                vert(x, y, z, dir, x1, y0, z1, u0, v1, shade); vert(x, y, z, dir, x1, y0, z0, u1, v1, shade);
                vert(x, y, z, dir, x1, y1, z0, u1, v0, shade); vert(x, y, z, dir, x1, y1, z1, u0, v0, shade); break;
            default: // -x
                vert(x, y, z, dir, x0, y0, z0, u0, v1, shade); vert(x, y, z, dir, x0, y0, z1, u1, v1, shade);
                vert(x, y, z, dir, x0, y1, z1, u1, v0, shade); vert(x, y, z, dir, x0, y1, z0, u0, v0, shade); break;
        }
    }

    private void vert(int bx, int by, int bz, int dir, float vx, float vy, float vz,
                      float u, float v, float shade) {
        float c = shade * occlusion(bx, by, bz, dir, vx, vy, vz);
        glColor3f(c, c, c);
        glTexCoord2f(u, v);
        glVertex3f(vx, vy, vz);
    }

    /**
     * Ambient occlusion for one face vertex: look at the two blocks sharing an
     * edge with this corner and the block diagonally behind it, on the other
     * side of the face. A corner with two solid sides is fully shadowed; the
     * diagonal alone only darkens a little.
     */
    private float occlusion(int bx, int by, int bz, int dir, float vx, float vy, float vz) {
        int nx = 0, ny = 0, nz = 0;
        switch (dir) {
            case 0: ny = 1; break;
            case 1: ny = -1; break;
            case 2: nz = 1; break;
            case 3: nz = -1; break;
            case 4: nx = 1; break;
            default: nx = -1; break;
        }
        // tangent axes of the face: (X,Z) for top/bottom, (X,Y) for z faces, (Z,Y) for x faces
        int ax1, ay1, az1, ax2, ay2, az2;
        switch (dir) {
            case 0: case 1: ax1 = 1; ay1 = 0; az1 = 0; ax2 = 0; ay2 = 0; az2 = 1; break;
            case 2: case 3: ax1 = 1; ay1 = 0; az1 = 0; ax2 = 0; ay2 = 1; az2 = 0; break;
            default:        ax1 = 0; ay1 = 0; az1 = 1; ax2 = 0; ay2 = 1; az2 = 0; break;
        }
        int s1 = ax1 != 0 ? (vx > bx ? 1 : -1) : ay1 != 0 ? (vy > by ? 1 : -1) : (vz > bz ? 1 : -1);
        int s2 = ax2 != 0 ? (vx > bx ? 1 : -1) : ay2 != 0 ? (vy > by ? 1 : -1) : (vz > bz ? 1 : -1);

        int ox = bx + nx, oy = by + ny, oz = bz + nz;
        boolean side1 = world.isSolid(ox + ax1 * s1, oy + ay1 * s1, oz + az1 * s1);
        boolean side2 = world.isSolid(ox + ax2 * s2, oy + ay2 * s2, oz + az2 * s2);
        boolean corner = world.isSolid(ox + ax1 * s1 + ax2 * s2,
                                       oy + ay1 * s1 + ay2 * s2,
                                       oz + az1 * s1 + az2 * s2);
        int level = (side1 && side2) ? 0 : 3 - ((side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0));
        return AO[level];
    }
}
