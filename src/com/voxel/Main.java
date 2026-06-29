package com.voxel;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/** Entry point: window, render loop, input, and block raycasting. */
public class Main {
    private long window;
    private int width = 1280, height = 720;

    private World world;
    private ChunkRenderer renderer;
    private TextureAtlas atlas;
    private Player player;

    private double lastX, lastY;
    private double mouseX, mouseY;
    private boolean firstMouse = true;
    private boolean inMenu = true;
    private boolean breakHeld, placeHeld;
    private byte currentBlock = World.STONE;

    // sword reward: chop 3 trees (4 logs each) to earn it
    private static final int LOGS_PER_TREE = 4, TREES_NEEDED = 3;
    private int logsBroken = 0;
    private boolean hasSword = false;
    private int swordTex = 0;

    // zombies
    private final java.util.List<Zombie> zombies = new java.util.ArrayList<>();
    private int zHeadFront, zHead, zBody;

    public void run() {
        init();
        loop();
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();
        // Prefer X11 (XWayland) — native Wayland EGL context creation is flaky here.
        if (glfwPlatformSupported(GLFW_PLATFORM_X11))
            glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");

        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(width, height, "PACMine", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create window");

        glfwSetFramebufferSizeCallback(window, (w, fw, fh) -> {
            width = fw; height = fh; glViewport(0, 0, fw, fh);
        });
        glfwSetKeyCallback(window, this::onKey);
        glfwSetMouseButtonCallback(window, this::onMouse);
        glfwSetCursorPosCallback(window, this::onCursor);

        // center window
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pw = stack.mallocInt(1), ph = stack.mallocInt(1);
            glfwGetWindowSize(window, pw, ph);
            GLFWVidMode vm = glfwGetVideoMode(glfwGetPrimaryMonitor());
            glfwSetWindowPos(window, (vm.width() - pw.get(0)) / 2, (vm.height() - ph.get(0)) / 2);
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);
        // re-grab the cursor whenever the window regains focus (but not in the menu)
        glfwSetWindowFocusCallback(window, (w, focused) -> { if (focused && !inMenu) grabCursor(); });
        GL.createCapabilities();

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glEnable(GL_TEXTURE_2D);
        glClearColor(0.55f, 0.75f, 1.0f, 1f); // sky

        atlas = new TextureAtlas("assets");
        swordTex = TextureAtlas.loadStandalone("assets/sword.png");
        updateTitle();
        world = new World(System.nanoTime());
        renderer = new ChunkRenderer(world, atlas);
        int sx = World.SX / 2, sz = World.SZ / 2;
        int sy = World.SY - 1;
        // descend to the first ground block, skipping tree leaves/logs
        while (sy > 0) {
            byte b = world.get(sx, sy, sz);
            if (b != World.AIR && b != World.LEAVES && b != World.WOOD) break;
            sy--;
        }
        player = new Player(world, sx + 0.5, sy + 1, sz + 0.5);

        zHeadFront = TextureAtlas.loadStandalone("assets/zombie_head_pered.png");
        zHead      = TextureAtlas.loadStandalone("assets/zombie_head.png");
        zBody      = TextureAtlas.loadStandalone("assets/zombie.png");
        spawnZombies(8, sx, sz);
    }

    private void spawnZombies(int n, int cx, int cz) {
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < n; i++) {
            int zx = cx + r.nextInt(40) - 20;
            int zz = cz + r.nextInt(40) - 20;
            if (!world.inBounds(zx, 0, zz)) continue;
            zombies.add(new Zombie(world, zx + 0.5, World.SY, zz + 0.5));
        }
    }

    private void onKey(long w, int key, int sc, int action, int mods) {
        if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
            if (inMenu) glfwSetWindowShouldClose(w, true);
            else { inMenu = true; glfwSetInputMode(w, GLFW_CURSOR, GLFW_CURSOR_NORMAL); }
            return;
        }
        if (action == GLFW_PRESS) {
            switch (key) {
                case GLFW_KEY_1: currentBlock = World.GRASS; break;
                case GLFW_KEY_2: currentBlock = World.DIRT; break;
                case GLFW_KEY_3: currentBlock = World.STONE; break;
                case GLFW_KEY_4: currentBlock = World.WOOD; break;
                case GLFW_KEY_5: currentBlock = World.LEAVES; break;
                case GLFW_KEY_6: currentBlock = World.SAND; break;
            }
        }
    }

    private void onMouse(long w, int button, int action, int mods) {
        boolean down = action == GLFW_PRESS;
        if (inMenu) {
            if (down && button == GLFW_MOUSE_BUTTON_LEFT) menuClick();
            return;
        }
        // if the cursor ever slipped out of capture, a click re-grabs it
        if (down && glfwGetInputMode(w, GLFW_CURSOR) != GLFW_CURSOR_DISABLED) {
            grabCursor();
            return;
        }
        if (button == GLFW_MOUSE_BUTTON_LEFT)  breakHeld = down;
        if (button == GLFW_MOUSE_BUTTON_RIGHT) placeHeld = down;
        if (down) {
            boolean place = button == GLFW_MOUSE_BUTTON_RIGHT;
            // left click with a sword: try to hit a zombie first
            if (!place && hasSword && attackZombie()) return;
            editBlock(place);
        }
    }

    /** Kill the nearest zombie in front of the player within sword reach. */
    private boolean attackZombie() {
        double ex = player.x, ey = player.y + Player.EYE, ez = player.z;
        double yr = Math.toRadians(player.yaw), pr = Math.toRadians(player.pitch);
        double dx = -Math.sin(yr) * Math.cos(pr), dy = Math.sin(pr), dz = -Math.cos(yr) * Math.cos(pr);
        double reach = 4.5;
        Zombie best = null; double bestT = reach;
        for (Zombie z : zombies) {
            double zcy = z.y + Zombie.HEIGHT / 2;
            double cx = z.x - ex, cy = zcy - ey, cz = z.z - ez;
            double t = cx * dx + cy * dy + cz * dz;        // projection along view
            if (t < 0 || t > reach) continue;              // must be in front, within reach
            double px = ex + dx * t, py = ey + dy * t, pz = ez + dz * t;
            double miss = Math.sqrt((px - z.x) * (px - z.x)
                    + (py - zcy) * (py - zcy) + (pz - z.z) * (pz - z.z));
            if (miss < 1.3 && t < bestT) { bestT = t; best = z; } // roughly aimed at
        }
        if (best != null) {
            zombies.remove(best);
            System.out.println("Zombie killed! remaining=" + zombies.size());
            return true;
        }
        return false;
    }

    private void grabCursor() {
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        if (glfwRawMouseMotionSupported())
            glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        firstMouse = true;
    }

    private void onCursor(long w, double xpos, double ypos) {
        mouseX = xpos; mouseY = ypos;
        if (inMenu) return;
        if (firstMouse) { lastX = xpos; lastY = ypos; firstMouse = false; return; }
        double dx = xpos - lastX, dy = ypos - lastY;
        lastX = xpos; lastY = ypos;
        player.addYawPitch((float) (-dx * 0.12), (float) (-dy * 0.12));
    }

    /** DDA raycast from the eye; break the hit block or place against its face. */
    private void editBlock(boolean place) {
        double ex = player.x, ey = player.y + Player.EYE, ez = player.z;
        double yr = Math.toRadians(player.yaw), pr = Math.toRadians(player.pitch);
        double dx = -Math.sin(yr) * Math.cos(pr);
        double dy = Math.sin(pr);
        double dz = -Math.cos(yr) * Math.cos(pr);

        int px = -1, py = -1, pz = -1; // previous (air) cell, for placing
        double t = 0, step = 0.05, max = 6.0;
        while (t < max) {
            int bx = (int) Math.floor(ex + dx * t);
            int by = (int) Math.floor(ey + dy * t);
            int bz = (int) Math.floor(ez + dz * t);
            if (world.isSolid(bx, by, bz)) {
                if (place) {
                    if (px >= 0 && !world.isSolid(px, py, pz)) {
                        world.set(px, py, pz, currentBlock);
                        renderer.markDirty(px, pz);
                    }
                } else if (world.get(bx, by, bz) != World.BEDROCK) {
                    byte broken = world.get(bx, by, bz);
                    world.set(bx, by, bz, World.AIR);
                    renderer.markDirty(bx, bz);
                    if (broken == World.WOOD && !hasSword) {
                        logsBroken++;
                        if (logsBroken >= LOGS_PER_TREE * TREES_NEEDED) hasSword = true;
                    }
                }
                return;
            }
            px = bx; py = by; pz = bz;
            t += step;
        }
    }

    private void loop() {
        double last = glfwGetTime();
        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            double dt = Math.min(now - last, 0.05);
            last = now;

            if (inMenu) {
                renderMenu();
                glfwSwapBuffers(window);
                glfwPollEvents();
                continue;
            }

            handleMovement(dt);
            for (Zombie z : zombies) z.update(player, dt);

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            setupCamera();
            renderer.render();
            renderZombies();
            drawHud();

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    private void handleMovement(double dt) {
        double f = 0, s = 0;
        if (key(GLFW_KEY_W)) f += 1; if (key(GLFW_KEY_S)) f -= 1;
        if (key(GLFW_KEY_D)) s += 1; if (key(GLFW_KEY_A)) s -= 1;
        if (key(GLFW_KEY_SPACE)) player.jump();
        player.update(f, s, dt);
    }

    private boolean key(int k) { return glfwGetKey(window, k) == GLFW_PRESS; }

    private void setupCamera() {
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        double fov = 70, aspect = (double) width / height, near = 0.1, far = 300;
        double top = Math.tan(Math.toRadians(fov / 2)) * near;
        glFrustum(-top * aspect, top * aspect, -top, top, near, far);

        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
        glRotatef(-player.pitch, 1, 0, 0);
        glRotatef(-player.yaw, 0, 1, 0);
        glTranslatef((float) -player.x, (float) -(player.y + Player.EYE), (float) -player.z);
    }

    private void drawHud() {
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity();
        glOrtho(0, width, height, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity();
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);   // HUD quads are wound the other way; don't cull them

        // crosshair
        glDisable(GL_TEXTURE_2D);
        glColor3f(1, 1, 1);
        glLineWidth(2);
        float cx = width / 2f, cy = height / 2f, s = 10;
        glBegin(GL_LINES);
        glVertex2f(cx - s, cy); glVertex2f(cx + s, cy);
        glVertex2f(cx, cy - s); glVertex2f(cx, cy + s);
        glEnd();
        glEnable(GL_TEXTURE_2D);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // HUD panel in the TOP-LEFT corner
        float pad = 16, sz = 80;
        float bx = pad, by = pad;             // panel origin
        float panelW = sz + 16 + TREES_NEEDED * 34 + 16;
        float panelH = sz + 16;
        glDisable(GL_TEXTURE_2D);
        glColor4f(0, 0, 0, 0.55f);            // opaque-ish backdrop
        quad(bx, by, bx + panelW, by + panelH);

        // sword icon (dim until earned)
        float sx0 = bx + 8, sy0 = by + 8, sx1 = sx0 + sz, sy1 = sy0 + sz;
        if (swordTex != 0) {
            glEnable(GL_TEXTURE_2D);
            glBindTexture(GL_TEXTURE_2D, swordTex);
            glColor4f(1, 1, 1, hasSword ? 1f : 0.25f);
            glBegin(GL_QUADS);
            glTexCoord2f(0, 0); glVertex2f(sx0, sy0);
            glTexCoord2f(1, 0); glVertex2f(sx1, sy0);
            glTexCoord2f(1, 1); glVertex2f(sx1, sy1);
            glTexCoord2f(0, 1); glVertex2f(sx0, sy1);
            glEnd();
            glDisable(GL_TEXTURE_2D);
        }

        // progress pips: one big square per tree needed
        int trees = logsBroken / LOGS_PER_TREE;
        float ps = 28, gap = 6;
        float py0 = by + (panelH - ps) / 2;
        for (int i = 0; i < TREES_NEEDED; i++) {
            float px = sx1 + 12 + i * (ps + gap);
            if (i < trees) glColor4f(0.3f, 0.9f, 0.3f, 1f);    // done = green
            else           glColor4f(0.25f, 0.25f, 0.25f, 1f); // todo = grey
            quad(px, py0, px + ps, py0 + ps);
        }
        // first-person held sword in the bottom-right, tilted like it's in hand
        if (hasSword && swordTex != 0) {
            float hs = Math.min(width, height) * 0.42f; // sword size
            float cxp = width - hs * 0.45f, cyp = height - hs * 0.30f; // pivot near corner
            glPushMatrix();
            glTranslatef(cxp, cyp, 0);
            glRotatef(-35, 0, 0, 1);   // tilt
            glEnable(GL_TEXTURE_2D);
            glBindTexture(GL_TEXTURE_2D, swordTex);
            glColor4f(1, 1, 1, 1);
            float h = hs / 2;
            glBegin(GL_QUADS);
            glTexCoord2f(0, 0); glVertex2f(-h, -h);
            glTexCoord2f(1, 0); glVertex2f( h, -h);
            glTexCoord2f(1, 1); glVertex2f( h,  h);
            glTexCoord2f(0, 1); glVertex2f(-h,  h);
            glEnd();
            glDisable(GL_TEXTURE_2D);
            glPopMatrix();
        }
        glDisable(GL_BLEND);

        glEnable(GL_TEXTURE_2D);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glPopMatrix();
        glMatrixMode(GL_PROJECTION); glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
    }

    private void renderZombies() {
        glDisable(GL_CULL_FACE); // model faces aren't carefully wound
        for (Zombie z : zombies) {
            glPushMatrix();
            glTranslatef((float) z.x, (float) z.y, (float) z.z);
            glRotatef(z.yaw, 0, 1, 0);
            glColor3f(1, 1, 1);

            double w = Zombie.WIDTH / 2, d = Zombie.DEPTH / 2, h = Zombie.HEAD / 2;
            // body box
            double[] body = {-w, 0, -d, w, Zombie.BODY_H, d};
            bindBox(zBody, body, -1);
            // head: 5 faces normal, front (+Z) uses the face texture
            double y0 = Zombie.BODY_H - 0.02, y1 = Zombie.BODY_H + Zombie.HEAD;
            double[] head = {-h, y0, -h, h, y1, h};
            bindBox(zHead, head, 0);      // all faces except +Z
            bindFront(zHeadFront, head);  // +Z face only

            glPopMatrix();
        }
        glColor3f(1, 1, 1);
        glEnable(GL_CULL_FACE);
    }

    /** Draw a textured box. If skipDir >= 0, that face is omitted. */
    private void bindBox(int tex, double[] b, int skipDir) {
        glBindTexture(GL_TEXTURE_2D, tex);
        glBegin(GL_QUADS);
        for (int dir = 0; dir < 6; dir++) if (dir != skipDir) boxFace(dir, b);
        glEnd();
    }

    private void bindFront(int tex, double[] b) {
        glBindTexture(GL_TEXTURE_2D, tex);
        glBegin(GL_QUADS);
        boxFace(0, b);
        glEnd();
    }

    // dir: 0 +Z(front),1 -Z,2 +X,3 -X,4 +Y,5 -Y. Texture mapped upright.
    private void boxFace(int dir, double[] b) {
        float x0=(float)b[0],y0=(float)b[1],z0=(float)b[2],x1=(float)b[3],y1=(float)b[4],z1=(float)b[5];
        switch (dir) {
            case 0: // +Z front
                tv(0,1,x0,y0,z1); tv(1,1,x1,y0,z1); tv(1,0,x1,y1,z1); tv(0,0,x0,y1,z1); break;
            case 1: // -Z back
                tv(0,1,x1,y0,z0); tv(1,1,x0,y0,z0); tv(1,0,x0,y1,z0); tv(0,0,x1,y1,z0); break;
            case 2: // +X right
                tv(0,1,x1,y0,z1); tv(1,1,x1,y0,z0); tv(1,0,x1,y1,z0); tv(0,0,x1,y1,z1); break;
            case 3: // -X left
                tv(0,1,x0,y0,z0); tv(1,1,x0,y0,z1); tv(1,0,x0,y1,z1); tv(0,0,x0,y1,z0); break;
            case 4: // +Y top
                tv(0,0,x0,y1,z0); tv(0,1,x0,y1,z1); tv(1,1,x1,y1,z1); tv(1,0,x1,y1,z0); break;
            case 5: // -Y bottom
                tv(0,0,x0,y0,z0); tv(1,0,x1,y0,z0); tv(1,1,x1,y0,z1); tv(0,1,x0,y0,z1); break;
        }
    }

    private void tv(float u, float v, float x, float y, float z) {
        glTexCoord2f(u, v); glVertex3f(x, y, z);
    }

    // button rectangles {x0,y0,x1,y1} computed from current size
    private float[] playRect() {
        float bw = 280, bh = 64, cx = width / 2f, y = height * 0.45f;
        return new float[]{cx - bw/2, y, cx + bw/2, y + bh};
    }
    private float[] quitRect() {
        float bw = 280, bh = 64, cx = width / 2f, y = height * 0.45f + 90;
        return new float[]{cx - bw/2, y, cx + bw/2, y + bh};
    }
    private boolean inRect(float[] r, double px, double py) {
        return px >= r[0] && px <= r[2] && py >= r[1] && py <= r[3];
    }

    private void menuClick() {
        if (inRect(playRect(), mouseX, mouseY)) {
            inMenu = false;
            grabCursor();
        } else if (inRect(quitRect(), mouseX, mouseY)) {
            glfwSetWindowShouldClose(window, true);
        }
    }

    private void renderMenu() {
        glClearColor(0.10f, 0.12f, 0.18f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glClearColor(0.55f, 0.75f, 1.0f, 1f); // restore sky for the game

        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity();
        glOrtho(0, width, height, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity();
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_TEXTURE_2D);

        // title
        String title = "PACMINE";
        float ts = 9;
        glColor3f(0.85f, 0.95f, 0.6f);
        Font5x7.draw(title, width/2f - Font5x7.width(title, ts)/2, height * 0.18f, ts);

        drawButton(playRect(), "PLAY", 0.25f, 0.6f, 0.3f);
        drawButton(quitRect(), "QUIT", 0.6f, 0.25f, 0.25f);

        glEnable(GL_TEXTURE_2D);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glPopMatrix();
        glMatrixMode(GL_PROJECTION); glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
    }

    private void drawButton(float[] r, String label, float cr, float cg, float cb) {
        boolean hover = inRect(r, mouseX, mouseY);
        float m = hover ? 1.3f : 1f;
        glColor3f(cr * m, cg * m, cb * m);
        quad(r[0], r[1], r[2], r[3]);
        // label centered
        float ls = 5;
        glColor3f(1, 1, 1);
        float lw = Font5x7.width(label, ls), lh = 7 * ls;
        Font5x7.draw(label, (r[0] + r[2]) / 2 - lw / 2, (r[1] + r[3]) / 2 - lh / 2, ls);
    }

    private void quad(float x0, float y0, float x1, float y1) {
        glBegin(GL_QUADS);
        glVertex2f(x0, y0); glVertex2f(x1, y0);
        glVertex2f(x1, y1); glVertex2f(x0, y1);
        glEnd();
    }

    private void updateTitle() {
        glfwSetWindowTitle(window, "PACMine");
    }

    public static void main(String[] args) { new Main().run(); }
}
