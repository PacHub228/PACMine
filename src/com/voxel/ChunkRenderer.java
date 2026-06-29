package com.voxel;

import static org.lwjgl.opengl.GL11.*;

/**
 * Builds one OpenGL display list per chunk column. Only faces adjacent to air
 * are emitted, textured from the atlas. Each face direction gets a fixed
 * brightness (via vertex colour, modulated with the texture) so cubes read as
 * 3D without real lighting.
 */
public class ChunkRenderer {
    private final World world;
    private final TextureAtlas atlas;
    private final int[] lists;
    private final boolean[] dirty;

    private static final float TOP = 1.0f, BOTTOM = 0.5f, NS = 0.8f, EW = 0.65f;

    public ChunkRenderer(World world, TextureAtlas atlas) {
        this.world = world;
        this.atlas = atlas;
        lists = new int[world.cx * world.cz];
        dirty = new boolean[world.cx * world.cz];
        for (int i = 0; i < lists.length; i++) { lists[i] = glGenLists(1); dirty[i] = true; }
    }

    public void markDirty(int blockX, int blockZ) {
        int cx = blockX / World.CHUNK, cz = blockZ / World.CHUNK;
        mark(cx, cz);
        if (blockX % World.CHUNK == 0) mark(cx - 1, cz);
        if (blockX % World.CHUNK == World.CHUNK - 1) mark(cx + 1, cz);
        if (blockZ % World.CHUNK == 0) mark(cx, cz - 1);
        if (blockZ % World.CHUNK == World.CHUNK - 1) mark(cx, cz + 1);
    }

    private void mark(int cx, int cz) {
        if (cx < 0 || cz < 0 || cx >= world.cx || cz >= world.cz) return;
        dirty[cz * world.cx + cx] = true;
    }

    public void render() {
        atlas.bind();
        for (int cz = 0; cz < world.cz; cz++) {
            for (int cx = 0; cx < world.cx; cx++) {
                int i = cz * world.cx + cx;
                if (dirty[i]) { rebuild(cx, cz, lists[i]); dirty[i] = false; }
                glCallList(lists[i]);
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

    private void face(int x, int y, int z, byte b, float s, int dir) {
        glColor3f(s, s, s); // shading; modulates with the texture
        float[] uv = atlas.uv(atlas.tileFor(b, dir));
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        float x0 = x, y0 = y, z0 = z, x1 = x + 1, y1 = y + 1, z1 = z + 1;
        switch (dir) {
            case 0: // top (+y)
                t(u0, v1); glVertex3f(x0, y1, z0); t(u0, v0); glVertex3f(x0, y1, z1);
                t(u1, v0); glVertex3f(x1, y1, z1); t(u1, v1); glVertex3f(x1, y1, z0); break;
            case 1: // bottom (-y)
                t(u0, v0); glVertex3f(x0, y0, z0); t(u1, v0); glVertex3f(x1, y0, z0);
                t(u1, v1); glVertex3f(x1, y0, z1); t(u0, v1); glVertex3f(x0, y0, z1); break;
            case 2: // +z
                t(u0, v1); glVertex3f(x0, y0, z1); t(u1, v1); glVertex3f(x1, y0, z1);
                t(u1, v0); glVertex3f(x1, y1, z1); t(u0, v0); glVertex3f(x0, y1, z1); break;
            case 3: // -z
                t(u0, v1); glVertex3f(x1, y0, z0); t(u1, v1); glVertex3f(x0, y0, z0);
                t(u1, v0); glVertex3f(x0, y1, z0); t(u0, v0); glVertex3f(x1, y1, z0); break;
            case 4: // +x
                t(u0, v1); glVertex3f(x1, y0, z1); t(u1, v1); glVertex3f(x1, y0, z0);
                t(u1, v0); glVertex3f(x1, y1, z0); t(u0, v0); glVertex3f(x1, y1, z1); break;
            case 5: // -x
                t(u0, v1); glVertex3f(x0, y0, z0); t(u1, v1); glVertex3f(x0, y0, z1);
                t(u1, v0); glVertex3f(x0, y1, z1); t(u0, v0); glVertex3f(x0, y1, z0); break;
        }
    }

    private void t(float u, float v) { glTexCoord2f(u, v); }
}
