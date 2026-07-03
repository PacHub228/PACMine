package com.voxel;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
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

    // UI state
    private enum Screen { MAIN, WORLDS, WORLD_MENU, CREATE, CREDITS, MP, JOIN }
    private String selectedWorld = null;

    // multiplayer
    private boolean multiplayer = false, isHost = false, connecting = false;
    private NetServer server;
    private NetClient client;
    private int myId = 0;
    private final java.util.concurrent.ConcurrentLinkedQueue<NetEvent> netQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Map<Integer, double[]> remotePlayers = new java.util.HashMap<>(); // id -> {x,y,z,yaw,pitch}
    private final java.util.Map<Integer, String> remoteNames = new java.util.HashMap<>();
    private final java.util.Map<Integer, Boolean> remotePrem = new java.util.HashMap<>();
    private final StringBuilder ipInput = new StringBuilder("localhost");
    private double moveSendTimer = 0;

    // profile (free vs premium edition)
    private String profileName = "Player";
    private boolean profilePremium = false;
    private String profileToken = "";
    private boolean inMenu = true;
    private Screen screen = Screen.MAIN;
    private boolean paused = false;        // in-game pause overlay
    private java.util.List<String> worldList = new java.util.ArrayList<>();
    private int newChunks = 8;             // world size (chunks) for creation
    private String worldName = null;       // currently loaded world

    // settings (used when creating a world)
    private boolean hostileMobs = true;
    private boolean creativeMode = false;   // false = survival
    private boolean superMode = false;       // wave-survival arena
    private boolean protection = true;       // bedrock + edge barrier
    private int wave = 0; private double waveTimer = 0;
    private boolean breakHeld, placeHeld;
    // hotbar inventory
    private final byte[] hotbar = { World.GRASS, World.DIRT, World.STONE, World.WOOD, World.LEAVES, World.SAND, World.COAL, World.IRON };
    private int selectedSlot = 2; // stone
    private byte currentBlock() { return hotbar[selectedSlot]; }

    // inventory: count of each block id; opened with E (9x9 grid)
    private final int[] inv = new int[16];
    private boolean inventoryOpen = false;

    // mining (hold to break, time depends on block)
    private int mineX = Integer.MIN_VALUE, mineY, mineZ;
    private double mineProg = 0;

    // day/night cycle + debug overlay
    private static final double DAY_LENGTH = 480; // seconds for a full day+night
    private double timeOfDay = 0.25;              // 0..1, start at noon
    private boolean showDebug = false;
    private double fps, fpsAccum; private int fpsFrames;
    private double spawnTimer = 0, burnTimer = 0;
    private static final int MAX_ZOMBIES = 14;

    // sword reward: chop 3 trees (4 logs each) to earn it
    private static final int LOGS_PER_TREE = 4, TREES_NEEDED = 3;
    private int logsBroken = 0;
    private boolean hasSword = false;
    private int swordTex = 0;

    // zombies
    private final java.util.List<Zombie> zombies = new java.util.ArrayList<>();
    // dropped block items waiting to be picked up
    private final java.util.List<ItemDrop> drops = new java.util.ArrayList<>();
    private int zHeadFront, zHead, zBody;
    private int pHeadFront, pHead, pBody, pBodyFront;   // remote player textures

    // hearts / health
    private int heartFull, heartHalf;
    private int spawnX, spawnY, spawnZ;

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
        glfwSetScrollCallback(window, (w, dx, dy) -> {
            if (inMenu || paused) return;
            selectedSlot = (selectedSlot - (int) Math.signum(dy) + hotbar.length) % hotbar.length;
        });
        glfwSetCharCallback(window, (w, cp) -> {
            if (inMenu && screen == Screen.JOIN && cp < 128) {
                char ch = (char) cp;
                if (Character.isLetterOrDigit(ch) || ch == '.' || ch == ':' || ch == '-')
                    ipInput.append(ch);
            }
        });

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
        glfwSetWindowFocusCallback(window, (w, focused) -> { if (focused && !inMenu && !paused) grabCursor(); });
        GL.createCapabilities();

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glEnable(GL_TEXTURE_2D);
        glClearColor(0.55f, 0.75f, 1.0f, 1f); // sky

        atlas = new TextureAtlas("assets");
        swordTex   = TextureAtlas.loadStandalone("assets/sword.png");
        zHeadFront = TextureAtlas.loadStandalone("assets/zombie_head_pered.png");
        zHead      = TextureAtlas.loadStandalone("assets/zombie_head.png");
        zBody      = TextureAtlas.loadStandalone("assets/zombie.png");
        heartFull  = TextureAtlas.loadStandalone("assets/heart_all.png");
        heartHalf  = TextureAtlas.loadStandalone("assets/heart_noneall.png");
        pHeadFront = TextureAtlas.loadStandalone("assets/player_head_pered.png");
        pHead      = TextureAtlas.loadStandalone("assets/player_head.png");
        pBody      = TextureAtlas.loadStandalone("assets/player.png");
        pBodyFront = TextureAtlas.loadStandalone("assets/player_covta_pered.png");
        loadProfile();
        updateTitle();
    }

    /** Load the profile (name + login token) and verify the license with the backend. */
    private void loadProfile() {
        java.util.Properties p = new java.util.Properties();
        java.io.File f = new java.io.File(System.getProperty("user.home"), ".pacmine/profile.properties");
        try (java.io.InputStream in = new java.io.FileInputStream(f)) { p.load(in); } catch (Exception ignored) {}
        String name = p.getProperty("name", "Player").trim();
        profileToken = p.getProperty("token", "").trim();
        String[] v = AuthClient.verify(profileToken);   // license check
        if (v != null) { profileName = v[0]; profilePremium = Boolean.parseBoolean(v[1]); }
        else { profileName = name.isEmpty() ? "Player" : name; profilePremium = false; }
    }

    /** Create a brand-new world of the chosen size and start playing it. chunks==1 => infinite. */
    private void newWorld(int chunks) {
        boolean inf = chunks <= 1;
        world = inf ? World.createInfinite(System.nanoTime(), protection)
                    : new World(System.nanoTime(), chunks, protection);
        renderer = new ChunkRenderer(world, atlas);
        spawnX = inf ? 0 : world.sx / 2; spawnZ = inf ? 0 : world.sz / 2;
        int sy = World.SY - 1;
        while (sy > 0) {
            byte b = world.get(spawnX, sy, spawnZ);
            if (b != World.AIR && b != World.LEAVES && b != World.WOOD) break;
            sy--;
        }
        spawnY = sy + 1;
        player = new Player(world, spawnX + 0.5, spawnY, spawnZ + 0.5);
        player.creative = creativeMode;
        player.borderWalls = protection && !inf;   // no borders in an endless world
        hasSword = superMode;
        logsBroken = 0;
        zombies.clear(); drops.clear();
        java.util.Arrays.fill(inv, 0);
        timeOfDay = 0.25;
        wave = 0; waveTimer = 0;
        worldName = SaveGame.nextName();
        try { saveWorld(); } catch (IOException e) { System.err.println("save failed: " + e.getMessage()); }
        enterGame();
    }

    /** Load a saved world by name and start playing it. */
    private void loadWorld(String name) {
        try {
            SaveGame s = SaveGame.load(name);
            hostileMobs = s.hostileMobs; creativeMode = s.creative; protection = s.protection;
            superMode = s.superMode;
            if (s.infinite) {
                world = World.createInfinite(s.seed, s.protection);
                for (java.util.Map.Entry<Long, byte[]> e : s.chunkMap.entrySet())
                    world.importChunk(World.chunkCX(e.getKey()), World.chunkCZ(e.getKey()), e.getValue());
            } else {
                world = new World(s.chunks, s.blocks);
            }
            renderer = new ChunkRenderer(world, atlas);
            player = new Player(world, s.px, s.py, s.pz);
            player.yaw = s.yaw; player.pitch = s.pitch; player.health = s.health;
            player.creative = creativeMode; player.borderWalls = protection && !world.infinite;
            hasSword = s.hasSword; logsBroken = s.logsBroken;
            timeOfDay = s.timeOfDay; wave = s.wave; waveTimer = s.waveTimer;
            selectedSlot = (s.selectedSlot >= 0 && s.selectedSlot < hotbar.length) ? s.selectedSlot : 2;
            java.util.Arrays.fill(inv, 0);
            System.arraycopy(s.inv, 0, inv, 0, Math.min(s.inv.length, inv.length));
            spawnX = world.infinite ? 0 : world.sx / 2; spawnY = (int) s.py; spawnZ = world.infinite ? 0 : world.sz / 2;
            zombies.clear();
            for (double[] a : s.zombies) {
                Zombie z = new Zombie(world, a[0], a[1], a[2]);
                z.yaw = (float) a[3]; z.combat = a[4] != 0;
                zombies.add(z);
            }
            drops.clear();
            for (double[] a : s.drops) {
                ItemDrop d = new ItemDrop(world, (byte) a[0], a[1], a[2], a[3]);
                d.vx = 0; d.vy = 0; d.vz = 0;   // restored drops lie still
                drops.add(d);
            }
            worldName = name;
            enterGame();
        } catch (IOException e) {
            System.err.println("load failed: " + e.getMessage());
        }
    }

    private void saveWorld() throws IOException {
        if (worldName == null) return;
        SaveGame s = new SaveGame();
        s.hostileMobs = hostileMobs; s.creative = creativeMode; s.protection = protection;
        s.superMode = superMode;
        s.px = player.x; s.py = player.y; s.pz = player.z;
        s.yaw = player.yaw; s.pitch = player.pitch; s.health = player.health;
        s.hasSword = hasSword; s.logsBroken = logsBroken;
        s.timeOfDay = timeOfDay; s.wave = wave; s.waveTimer = waveTimer;
        s.selectedSlot = selectedSlot; s.inv = inv.clone();
        for (Zombie z : zombies) s.zombies.add(new double[]{z.x, z.y, z.z, z.yaw, z.combat ? 1 : 0});
        for (ItemDrop d : drops) s.drops.add(new double[]{d.type, d.x, d.y, d.z});
        if (world.infinite) {
            s.infinite = true; s.seed = world.seed(); s.chunkMap = world.exportChunks();
        } else {
            s.chunks = world.cx; s.blocks = world.data();
        }
        s.save(worldName);
    }

    private void enterGame() { inMenu = false; paused = false; grabCursor(); }

    // ---------- multiplayer ----------
    private void hostGame() {
        int hostChunks = Math.max(6, newChunks);   // multiplayer needs a real finite world (no infinite)
        world = new World(System.nanoTime(), hostChunks, protection);
        renderer = new ChunkRenderer(world, atlas);
        spawnX = world.sx / 2; spawnZ = world.sz / 2;
        int sy = World.SY - 1;
        while (sy > 0) {
            byte b = world.get(spawnX, sy, spawnZ);
            if (b != World.AIR && b != World.LEAVES && b != World.WOOD) break;
            sy--;
        }
        spawnY = sy + 1;
        player = new Player(world, spawnX + 0.5, spawnY, spawnZ + 0.5);
        player.creative = creativeMode; player.borderWalls = protection;
        hasSword = false; logsBroken = 0;
        zombies.clear(); drops.clear();  // mobs disabled in multiplayer v1
        remotePlayers.clear(); netQueue.clear();
        multiplayer = true; isHost = true; myId = 0; worldName = null;
        try {
            server = new NetServer(world, protection, netQueue);
            server.setHostInfo(profileName, profilePremium);
            server.start();
            enterGame();
        } catch (IOException e) {
            System.err.println("host failed: " + e.getMessage());
            multiplayer = false;
        }
    }

    private void joinGame(String ip) {
        try {
            remotePlayers.clear(); netQueue.clear();
            client = new NetClient(ip, NetServer.PORT, netQueue);
            client.sendName(profileName, profileToken);
            multiplayer = true; isHost = false; connecting = true;
            // world + player are built when the WORLD event arrives
        } catch (IOException e) {
            System.err.println("join failed: " + e.getMessage());
            multiplayer = false; connecting = false;
        }
    }

    private void processNetEvents() {
        NetEvent e;
        while ((e = netQueue.poll()) != null) {
            switch (e.type) {
                case NetEvent.WORLD:
                    world = new World(e.chunks, e.blocks);
                    renderer = new ChunkRenderer(world, atlas);
                    protection = e.protection;
                    spawnX = world.sx / 2; spawnZ = world.sz / 2;
                    int sy = World.SY - 1;
                    while (sy > 0 && !world.isSolid(spawnX, sy, spawnZ)) sy--;
                    spawnY = sy + 1;
                    player = new Player(world, spawnX + 0.5, spawnY, spawnZ + 0.5);
                    player.borderWalls = protection;
                    myId = client != null ? client.myId : 0;
                    drops.clear();
                    connecting = false;
                    enterGame();
                    break;
                case NetEvent.BLOCK:
                    if (world != null) { world.set(e.x, e.y, e.z, e.b); renderer.markDirty(e.x, e.z); }
                    break;
                case NetEvent.MOVE:
                    remotePlayers.put(e.id, new double[]{e.px, e.py, e.pz, e.yaw, e.pitch});
                    break;
                case NetEvent.NAME:
                    remoteNames.put(e.id, e.name); remotePrem.put(e.id, e.premium);
                    break;
                case NetEvent.LEAVE:
                    remotePlayers.remove(e.id); remoteNames.remove(e.id); remotePrem.remove(e.id);
                    break;
            }
        }
    }

    private void netBlock(int x, int y, int z, byte b) {
        if (!multiplayer) return;
        if (isHost && server != null) server.hostBlock(x, y, z, b);
        else if (client != null) client.sendBlock(x, y, z, b);
    }

    private void leaveMultiplayer() {
        if (server != null) { server.stop(); server = null; }
        if (client != null) { client.close(); client = null; }
        multiplayer = false; isHost = false; connecting = false;
        remotePlayers.clear(); netQueue.clear();
    }

    /** Respawn the player in the current world after death (world is kept). */
    private void resetPlayer() {
        // recompute a safe surface height at the spawn column
        int sy = World.SY - 1;
        while (sy > 0) {
            byte b = world.get(spawnX, sy, spawnZ);
            if (b != World.AIR && b != World.LEAVES && b != World.WOOD) break;
            sy--;
        }
        spawnY = sy + 1;
        player.x = spawnX + 0.5; player.y = spawnY; player.z = spawnZ + 0.5;
        player.vy = 0; player.health = Player.MAX_HEARTS;
        zombies.clear();
    }

    /** Spawn zombies at night, burn them off during the day (single-player). */
    private void manageZombies(double dt) {
        if (!hostileMobs) { zombies.clear(); return; }
        if (isNight()) {
            spawnTimer += dt;
            if (spawnTimer > 2.5 && zombies.size() < MAX_ZOMBIES) { spawnTimer = 0; spawnZombieNear(); }
        } else {
            spawnTimer = 0;
            burnTimer += dt;                       // daylight: zombies burn up
            if (burnTimer > 1.2 && !zombies.isEmpty()) { burnTimer = 0; zombies.remove(zombies.size() - 1); }
        }
    }

    private void spawnZombieNear() { spawnZombieNear(false); }
    private void spawnZombieNear(boolean combat) {
        double ang = Math.random() * Math.PI * 2, dist = 16 + Math.random() * 14;
        int zx = (int) (player.x + Math.cos(ang) * dist);
        int zz = (int) (player.z + Math.sin(ang) * dist);
        if (!world.inBounds(zx, 0, zz)) return;
        int sy = World.SY - 1;
        while (sy > 0 && !world.isSolid(zx, sy, zz)) sy--;
        Zombie z = new Zombie(world, zx + 0.5, sy + 1, zz + 0.5);
        z.combat = combat;
        zombies.add(z);
    }

    /** A zombie mined a world block: clear it and re-mesh (bedrock is protected in Zombie). */
    private void zombieBreak(int x, int y, int z) {
        byte b = world.get(x, y, z);
        if (b == World.AIR) return;
        world.set(x, y, z, World.AIR);
        renderer.markDirty(x, z);
        drops.add(new ItemDrop(world, b, x + 0.5, y + 0.3, z + 0.5));
    }

    /** Super mode: timed waves of fast, strafing, armed zombies. */
    private void manageWaves(double dt) {
        if (!zombies.isEmpty()) return;          // wait until the wave is cleared
        waveTimer += dt;
        double delay = (wave == 0) ? 5.0 : 3.0;  // first wave after 5s
        if (waveTimer >= delay) {
            waveTimer = 0;
            wave++;
            int n = 2 + wave * 2;                 // growing waves
            for (int i = 0; i < n; i++) spawnZombieNear(true);
        }
    }

    private void onKey(long w, int key, int sc, int action, int mods) {
        if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
            if (inMenu) {
                if (screen == Screen.MAIN) glfwSetWindowShouldClose(w, true);
                else if (screen == Screen.CREATE || screen == Screen.WORLD_MENU) screen = Screen.WORLDS;
                else if (screen == Screen.JOIN) screen = Screen.MP;
                else screen = Screen.MAIN;
            } else {
                // toggle the in-game pause menu
                paused = !paused;
                glfwSetInputMode(w, GLFW_CURSOR, paused ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                if (!paused) firstMouse = true;
            }
            return;
        }
        if (inMenu && screen == Screen.JOIN && action == GLFW_PRESS) {
            if (key == GLFW_KEY_BACKSPACE && ipInput.length() > 0) ipInput.deleteCharAt(ipInput.length() - 1);
            else if (key == GLFW_KEY_ENTER) joinGame(ipInput.toString().trim());
            return;
        }
        if (!inMenu && key == GLFW_KEY_F3 && action == GLFW_PRESS) { showDebug = !showDebug; return; }
        if (!inMenu && key == GLFW_KEY_E && action == GLFW_PRESS) {
            inventoryOpen = !inventoryOpen;
            glfwSetInputMode(w, GLFW_CURSOR, inventoryOpen ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
            if (!inventoryOpen) firstMouse = true;
            return;
        }
        if (!inMenu && action == GLFW_PRESS && key >= GLFW_KEY_1 && key <= GLFW_KEY_9) {
            int slot = key - GLFW_KEY_1;
            if (slot < hotbar.length) selectedSlot = slot;
        }
    }

    private void onMouse(long w, int button, int action, int mods) {
        boolean down = action == GLFW_PRESS;
        if (inMenu) {
            if (down && button == GLFW_MOUSE_BUTTON_LEFT) menuClick();
            return;
        }
        if (paused) {
            if (down && button == GLFW_MOUSE_BUTTON_LEFT) pauseClick();
            return;
        }
        if (inventoryOpen) {
            if (down && button == GLFW_MOUSE_BUTTON_LEFT) inventoryClick();
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
            if (button == GLFW_MOUSE_BUTTON_RIGHT) { placeBlock(); return; }
            // left click: hit a zombie if armed, else (creative) break instantly
            if (hasSword && attackZombie()) return;
            if (player.creative) {
                int[] r = raycast();
                if (r != null) breakBlock(r[0], r[1], r[2]);
            }
            // survival breaking is handled by hold-to-mine in updateMining()
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
        if (inMenu || paused || inventoryOpen) return;
        if (firstMouse) { lastX = xpos; lastY = ypos; firstMouse = false; return; }
        double dx = xpos - lastX, dy = ypos - lastY;
        lastX = xpos; lastY = ypos;
        player.addYawPitch((float) (-dx * 0.12), (float) (-dy * 0.12));
    }

    /** Seconds needed to mine a block. */
    private static double mineTime(byte b) {
        switch (b) {
            case World.LEAVES: return 0;
            case World.SAND: case World.DIRT: case World.GRASS: return 1;
            case World.WOOD: return 2;
            case World.STONE: return 3;
            case World.COAL: return 4;
            case World.IRON: return 5;
            default: return 2;
        }
    }

    /** Daylight factor 0 (night) .. 1 (full day). */
    private double light() {
        double s = Math.sin(timeOfDay * 2 * Math.PI);
        return Math.max(0, Math.min(1, s * 1.5 + 0.5));
    }
    private boolean isNight() { return light() < 0.4; }

    /** Seconds until day/night next flips. */
    private double secondsUntilFlip() {
        boolean cur = isNight();
        for (int i = 1; i <= 1000; i++) {
            double t = (timeOfDay + i * 0.001) % 1.0;
            double s = Math.sin(t * 2 * Math.PI);
            boolean night = (Math.max(0, Math.min(1, s * 1.5 + 0.5))) < 0.4;
            if (night != cur) return i * 0.001 * DAY_LENGTH;
        }
        return 0;
    }
    /** Time of day as HH:MM (noon at timeOfDay=0.25). */
    private String clock() {
        double h = (timeOfDay * 24 + 6) % 24;
        int hh = (int) h, mm = (int) ((h - hh) * 60);
        return String.format("%02d:%02d", hh, mm);
    }

    /** Raycast from the eye: returns {hx,hy,hz, px,py,pz} (hit cell + previous air cell), or null. */
    private int[] raycast() {
        double ex = player.x, ey = player.y + Player.EYE, ez = player.z;
        double yr = Math.toRadians(player.yaw), pr = Math.toRadians(player.pitch);
        double dx = -Math.sin(yr) * Math.cos(pr), dy = Math.sin(pr), dz = -Math.cos(yr) * Math.cos(pr);
        int px = Integer.MIN_VALUE, py = 0, pz = 0;
        for (double t = 0; t < 6.0; t += 0.05) {
            int bx = (int) Math.floor(ex + dx * t), by = (int) Math.floor(ey + dy * t), bz = (int) Math.floor(ez + dz * t);
            if (world.isSolid(bx, by, bz)) return new int[]{bx, by, bz, px, py, pz};
            px = bx; py = by; pz = bz;
        }
        return null;
    }

    /** Place the selected hotbar block against the targeted face (consumes inventory in survival). */
    private void placeBlock() {
        int[] r = raycast();
        if (r == null || r[3] == Integer.MIN_VALUE) return;
        int px = r[3], py = r[4], pz = r[5];
        if (world.isSolid(px, py, pz)) return;
        if (intersectsPlayer(px, py, pz)) return;   // don't place a block inside yourself
        byte b = currentBlock();
        if (!player.creative) {
            if (inv[b] <= 0) return;     // nothing to place
            inv[b]--;
        }
        world.set(px, py, pz, b);
        renderer.markDirty(px, pz);
        netBlock(px, py, pz, b);
    }

    /** True if block cell (bx,by,bz) overlaps the player's AABB. */
    private boolean intersectsPlayer(int bx, int by, int bz) {
        double w = 0.3, h = 1.8;
        return bx + 1 > player.x - w && bx < player.x + w
            && bz + 1 > player.z - w && bz < player.z + w
            && by + 1 > player.y && by < player.y + h;
    }

    /** Break the block at the given cell: drop into inventory + sync. */
    private void breakBlock(int bx, int by, int bz) {
        byte broken = world.get(bx, by, bz);
        if (broken == World.AIR || broken == World.BEDROCK) return;
        world.set(bx, by, bz, World.AIR);
        renderer.markDirty(bx, bz);
        netBlock(bx, by, bz, World.AIR);
        drops.add(new ItemDrop(world, broken, bx + 0.5, by + 0.3, bz + 0.5));  // pop out as an item
        if (broken == World.WOOD && !hasSword) {
            logsBroken++;
            if (logsBroken >= LOGS_PER_TREE * TREES_NEEDED) hasSword = true;
        }
    }

    /** Continuous mining while LMB is held (survival): progress depends on block type. */
    private void updateMining(double dt) {
        if (!breakHeld || player.creative || inventoryOpen) { mineX = Integer.MIN_VALUE; mineProg = 0; return; }
        int[] r = raycast();
        if (r == null || world.get(r[0], r[1], r[2]) == World.BEDROCK) { mineX = Integer.MIN_VALUE; mineProg = 0; return; }
        if (r[0] != mineX || r[1] != mineY || r[2] != mineZ) { mineX = r[0]; mineY = r[1]; mineZ = r[2]; mineProg = 0; }
        mineProg += dt;
        if (mineProg >= mineTime(world.get(mineX, mineY, mineZ))) {
            breakBlock(mineX, mineY, mineZ);
            mineX = Integer.MIN_VALUE; mineProg = 0;
        }
    }

    private void loop() {
        double last = glfwGetTime();
        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            double dt = Math.min(now - last, 0.05);
            last = now;

            processNetEvents();   // apply incoming world/block/move updates

            if (inMenu) {
                renderMenu();
                glfwSwapBuffers(window);
                glfwPollEvents();
                continue;
            }

            if (!paused && !inventoryOpen) {
                handleMovement(dt);
                if (!multiplayer) {
                    if (superMode) manageWaves(dt); else manageZombies(dt);
                    for (Zombie z : zombies) z.update(player, dt, this::zombieBreak);
                }
                broadcastMove(dt);
                updateMining(dt);
                updateDrops(dt);
                timeOfDay = (timeOfDay + dt / DAY_LENGTH) % 1.0;

                // death, or falling into the void when protection is off
                if (player.isDead() || (!player.creative && player.y < -5)) {
                    resetPlayer();
                }
            }

            // fps counter
            fpsAccum += dt; fpsFrames++;
            if (fpsAccum >= 0.5) { fps = fpsFrames / fpsAccum; fpsAccum = 0; fpsFrames = 0; }

            // sky colour from time of day
            float lt = (float) light();
            glClearColor(0.03f + (0.55f - 0.03f) * lt, 0.04f + (0.75f - 0.04f) * lt, 0.10f + (1.0f - 0.10f) * lt, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            setupCamera();
            renderer.render(player.x, player.z);
            renderDrops();
            if (!multiplayer) renderZombies();
            renderRemotePlayers();
            drawHud();
            if (paused) drawPauseMenu();
            if (inventoryOpen) drawInventory();

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    private void handleMovement(double dt) {
        double f = 0, s = 0, v = 0;
        if (key(GLFW_KEY_W)) f += 1; if (key(GLFW_KEY_S)) f -= 1;
        if (key(GLFW_KEY_D)) s += 1; if (key(GLFW_KEY_A)) s -= 1;
        if (player.creative) {
            if (key(GLFW_KEY_SPACE)) v += 1;
            if (key(GLFW_KEY_LEFT_SHIFT)) v -= 1;
        } else if (key(GLFW_KEY_SPACE)) {
            player.jump();
        }
        // sprint: hold Left Shift while moving forward (survival; in creative Shift descends)
        player.sprinting = !player.creative && f > 0 && key(GLFW_KEY_LEFT_SHIFT);
        player.update(f, s, v, dt);
    }

    private boolean key(int k) { return glfwGetKey(window, k) == GLFW_PRESS; }

    /** Send our position to the network ~20 times per second. */
    private void broadcastMove(double dt) {
        if (!multiplayer) return;
        moveSendTimer += dt;
        if (moveSendTimer < 0.05) return;
        moveSendTimer = 0;
        if (isHost && server != null) server.hostMoved(player.x, player.y, player.z, player.yaw, player.pitch);
        else if (client != null) client.sendMove(player.x, player.y, player.z, player.yaw, player.pitch);
    }

    /** Draw every other connected player as a simple two-box figure. */
    private void renderRemotePlayers() {
        if (!multiplayer || remotePlayers.isEmpty()) return;
        glDisable(GL_CULL_FACE);
        glColor3f(1, 1, 1);
        for (java.util.Map.Entry<Integer, double[]> en : remotePlayers.entrySet()) {
            if (en.getKey() == myId) continue;
            double[] p = en.getValue();
            glPushMatrix();
            glTranslatef((float) p[0], (float) p[1], (float) p[2]);
            glRotatef((float) p[3] + 180, 0, 1, 0); // front (+Z) faces the player's look direction
            // body: 5 faces + clothing front
            double[] body = {-0.3, 0, -0.2, 0.3, 1.2, 0.2};
            bindBox(pBody, body, 0);
            bindFront(pBodyFront, body);
            // head: 5 faces + front face texture
            double[] head = {-0.25, 1.18, -0.25, 0.25, 1.7, 0.25};
            bindBox(pHead, head, 0);
            bindFront(pHeadFront, head);
            glPopMatrix();
        }
        glEnable(GL_CULL_FACE);
    }

    private void setupCamera() {
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        double fov = player.sprinting ? 78 : 70, aspect = (double) width / height, near = 0.1, far = 300;
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

        // night darkening over the world (HUD drawn after stays bright)
        float nightAlpha = (float) ((1 - light()) * 0.6);
        if (nightAlpha > 0.01f) {
            glDisable(GL_TEXTURE_2D);
            glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glColor4f(0, 0, 0.05f, nightAlpha); quad(0, 0, width, height);
            glDisable(GL_BLEND);
        }

        // crosshair
        glDisable(GL_TEXTURE_2D);
        glColor3f(1, 1, 1);
        glLineWidth(2);
        float cx = width / 2f, cy = height / 2f, s = 10;
        glBegin(GL_LINES);
        glVertex2f(cx - s, cy); glVertex2f(cx + s, cy);
        glVertex2f(cx, cy - s); glVertex2f(cx, cy + s);
        glEnd();

        // mining progress bar
        if (mineX != Integer.MIN_VALUE && mineProg > 0) {
            double need = mineTime(world.get(mineX, mineY, mineZ));
            float frac = need <= 0 ? 1f : (float) Math.min(1, mineProg / need);
            float bw = 80, bh = 8, bx = cx - bw / 2, by = cy + 22;
            glColor3f(0.2f, 0.2f, 0.2f); quad(bx, by, bx + bw, by + bh);
            glColor3f(0.4f, 0.85f, 0.4f); quad(bx, by, bx + bw * frac, by + bh);
        }
        glEnable(GL_TEXTURE_2D);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        drawHearts();
        drawHotbar();
        drawNameTags();
        if (showDebug) drawDebug();

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

    /** Physics + pickup for dropped items (radius ~1.3, small delay so they pop first). */
    private void updateDrops(double dt) {
        for (java.util.Iterator<ItemDrop> it = drops.iterator(); it.hasNext(); ) {
            ItemDrop d = it.next();
            d.update(dt);
            if (d.expired()) { it.remove(); continue; }
            double dx = d.x - player.x, dy = d.y - player.y, dz = d.z - player.z;
            if (d.age > 0.4 && dx * dx + dz * dz < 1.7 && dy > -1.0 && dy < 2.0) {
                if (d.type >= 0 && d.type < inv.length) inv[d.type]++;
                it.remove();
            }
        }
    }

    /** Draw drops as small spinning, bobbing textured cubes. */
    private void renderDrops() {
        if (drops.isEmpty()) return;
        glDisable(GL_CULL_FACE);
        atlas.bind();
        glColor3f(1, 1, 1);
        for (ItemDrop d : drops) {
            glPushMatrix();
            float bob = (float) (Math.sin(d.age * 2.5) * 0.05);
            glTranslatef((float) d.x, (float) (d.y + 0.12 + bob), (float) d.z);
            glRotatef((float) (d.age * 60 % 360), 0, 1, 0);
            float s = 0.16f;
            glBegin(GL_QUADS);
            for (int dir = 0; dir < 6; dir++) {
                float[] uv = atlas.uv(atlas.tileFor(d.type, dir == 4 ? 0 : dir == 5 ? 1 : 2));
                dropFace(dir, s, uv);
            }
            glEnd();
            glPopMatrix();
        }
        glEnable(GL_CULL_FACE);
    }

    /** One face of a drop cube centred at the origin. dir as in boxFace. */
    private void dropFace(int dir, float s, float[] uv) {
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        switch (dir) {
            case 0: tv2(u0,v1,-s,-s, s); tv2(u1,v1, s,-s, s); tv2(u1,v0, s, s, s); tv2(u0,v0,-s, s, s); break;
            case 1: tv2(u0,v1, s,-s,-s); tv2(u1,v1,-s,-s,-s); tv2(u1,v0,-s, s,-s); tv2(u0,v0, s, s,-s); break;
            case 2: tv2(u0,v1, s,-s, s); tv2(u1,v1, s,-s,-s); tv2(u1,v0, s, s,-s); tv2(u0,v0, s, s, s); break;
            case 3: tv2(u0,v1,-s,-s,-s); tv2(u1,v1,-s,-s, s); tv2(u1,v0,-s, s, s); tv2(u0,v0,-s, s,-s); break;
            case 4: tv2(u0,v0,-s, s,-s); tv2(u0,v1,-s, s, s); tv2(u1,v1, s, s, s); tv2(u1,v0, s, s,-s); break;
            case 5: tv2(u0,v0,-s,-s,-s); tv2(u1,v0, s,-s,-s); tv2(u1,v1, s,-s, s); tv2(u0,v1,-s,-s, s); break;
        }
    }
    private void tv2(float u, float v, float x, float y, float z) { glTexCoord2f(u, v); glVertex3f(x, y, z); }

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

    // a centred button stack: index 0 is the top button
    private float[] menuRect(int i) {
        float bw = 320, bh = 52, cx = width / 2f, y = height * 0.26f + i * 64;
        return new float[]{cx - bw/2, y, cx + bw/2, y + bh};
    }
    private float[] playRect()        { return menuRect(0); }
    private float[] multiplayerRect()  { return menuRect(1); }
    private float[] creditsRect()     { return menuRect(2); }
    private float[] quitRect()        { return menuRect(3); }
    private float[] backRect()        { return menuRect(6); }

    private boolean inRect(float[] r, double px, double py) {
        return px >= r[0] && px <= r[2] && py >= r[1] && py <= r[3];
    }

    private void menuClick() {
        switch (screen) {
            case MAIN:
                if (inRect(playRect(), mouseX, mouseY)) { worldList = SaveGame.list(); screen = Screen.WORLDS; }
                else if (inRect(multiplayerRect(), mouseX, mouseY)) screen = Screen.MP;
                else if (inRect(creditsRect(), mouseX, mouseY)) screen = Screen.CREDITS;
                else if (inRect(quitRect(), mouseX, mouseY)) glfwSetWindowShouldClose(window, true);
                break;
            case MP:
                if (inRect(menuRect(0), mouseX, mouseY)) hostGame();
                else if (inRect(menuRect(1), mouseX, mouseY)) screen = Screen.JOIN;
                else if (inRect(backRect(), mouseX, mouseY)) screen = Screen.MAIN;
                break;
            case JOIN:
                if (inRect(menuRect(2), mouseX, mouseY)) joinGame(ipInput.toString().trim());
                else if (inRect(backRect(), mouseX, mouseY)) screen = Screen.MP;
                break;
            case WORLDS:
                for (int i = 0; i < worldList.size(); i++)
                    if (inRect(menuRect(i), mouseX, mouseY)) { selectedWorld = worldList.get(i); screen = Screen.WORLD_MENU; return; }
                if (inRect(menuRect(worldList.size()), mouseX, mouseY)) screen = Screen.CREATE;
                else if (inRect(backRect(), mouseX, mouseY)) screen = Screen.MAIN;
                break;
            case WORLD_MENU:
                if (inRect(menuRect(0), mouseX, mouseY)) loadWorld(selectedWorld);
                else if (inRect(menuRect(1), mouseX, mouseY)) {
                    SaveGame.delete(selectedWorld);
                    worldList = SaveGame.list();
                    screen = Screen.WORLDS;
                } else if (inRect(backRect(), mouseX, mouseY)) screen = Screen.WORLDS;
                break;
            case CREATE:
                if (inRect(sizeMinusRect(), mouseX, mouseY)) newChunks = Math.max(1, newChunks - 1);
                else if (inRect(sizePlusRect(), mouseX, mouseY)) newChunks = Math.min(24, newChunks + 1);
                else if (inRect(menuRect(1), mouseX, mouseY)) hostileMobs = !hostileMobs;
                else if (inRect(menuRect(2), mouseX, mouseY)) cycleMode();
                else if (inRect(menuRect(3), mouseX, mouseY)) protection = !protection;
                else if (inRect(menuRect(4), mouseX, mouseY)) newWorld(newChunks);
                else if (inRect(backRect(), mouseX, mouseY)) screen = Screen.WORLDS;
                break;
            case CREDITS:
                if (inRect(backRect(), mouseX, mouseY)) screen = Screen.MAIN;
                break;
        }
    }

    private float[] sizeMinusRect() { float[] r = menuRect(0); float mid=(r[0]+r[2])/2; return new float[]{r[0], r[1], mid, r[3]}; }
    private float[] sizePlusRect()  { float[] r = menuRect(0); float mid=(r[0]+r[2])/2; return new float[]{mid, r[1], r[2], r[3]}; }

    private void pauseClick() {
        if (inRect(menuRect(0), mouseX, mouseY)) {                 // continue
            paused = false; grabCursor();
        } else if (inRect(menuRect(1), mouseX, mouseY)) {          // save world (single-player only)
            if (!multiplayer)
                try { saveWorld(); } catch (IOException e) { System.err.println("save failed: " + e.getMessage()); }
        } else if (inRect(menuRect(2), mouseX, mouseY)) {          // (save and) quit to menu
            if (multiplayer) leaveMultiplayer();
            else try { saveWorld(); } catch (IOException e) { System.err.println("save failed: " + e.getMessage()); }
            paused = false; inMenu = true; screen = Screen.MAIN;
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
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

        String title;
        switch (screen) {
            case WORLDS: title = "SELECT WORLD"; break;
            case WORLD_MENU: title = selectedWorld; break;
            case CREATE: title = "NEW WORLD"; break;
            case CREDITS: title = "CREDITS"; break;
            case MP: title = "MULTIPLAYER"; break;
            case JOIN: title = "JOIN GAME"; break;
            default: title = "PACMINE";
        }
        float ts = 9;
        glColor3f(0.85f, 0.95f, 0.6f);
        Font5x7.draw(title, width/2f - Font5x7.width(title, ts)/2, height * 0.12f, ts);

        switch (screen) {
            case MAIN:
                drawButton(playRect(),        "PLAY", 0.25f, 0.6f, 0.3f);
                drawButton(multiplayerRect(), "MULTIPLAYER", 0.3f, 0.45f, 0.6f);
                drawButton(creditsRect(),     "CREDITS", 0.45f, 0.4f, 0.55f);
                drawButton(quitRect(),        "QUIT", 0.6f, 0.25f, 0.25f);
                break;
            case MP:
                drawButton(menuRect(0), "HOST GAME", 0.25f, 0.6f, 0.3f);
                drawButton(menuRect(1), "JOIN GAME", 0.3f, 0.45f, 0.6f);
                drawButton(backRect(),  "BACK", 0.5f, 0.4f, 0.25f);
                break;
            case JOIN:
                glColor3f(0.9f, 0.9f, 0.95f);
                String prompt = connecting ? "CONNECTING..." : "ENTER HOST IP";
                Font5x7.draw(prompt, width/2f - Font5x7.width(prompt, 4)/2, height * 0.26f, 4);
                drawButton(menuRect(1), ipInput.toString().toUpperCase(), 0.2f, 0.2f, 0.25f);
                drawButton(menuRect(2), "CONNECT", 0.25f, 0.6f, 0.3f);
                drawButton(backRect(),  "BACK", 0.5f, 0.4f, 0.25f);
                break;
            case WORLDS:
                for (int i = 0; i < worldList.size(); i++)
                    drawButton(menuRect(i), worldList.get(i), 0.3f, 0.45f, 0.6f);
                drawButton(menuRect(worldList.size()), "+ NEW WORLD", 0.25f, 0.55f, 0.3f);
                drawButton(backRect(), "BACK", 0.5f, 0.4f, 0.25f);
                break;
            case WORLD_MENU:
                drawButton(menuRect(0), "PLAY", 0.25f, 0.6f, 0.3f);
                drawButton(menuRect(1), "DELETE", 0.65f, 0.25f, 0.25f);
                drawButton(backRect(), "BACK", 0.5f, 0.4f, 0.25f);
                break;
            case CREATE:
                drawButton(menuRect(0), "- SIZE " + (newChunks <= 1 ? "INFINITE" : String.valueOf(newChunks)) + " +", 0.3f, 0.4f, 0.55f);
                drawButton(menuRect(1), "MOBS  " + onOff(hostileMobs), 0.3f, 0.4f, 0.55f);
                drawButton(menuRect(2), "MODE  " + modeName(), 0.3f, 0.4f, 0.55f);
                drawButton(menuRect(3), "PROTECT  " + onOff(protection), 0.3f, 0.4f, 0.55f);
                drawButton(menuRect(4), "CREATE", 0.25f, 0.6f, 0.3f);
                drawButton(backRect(), "BACK", 0.5f, 0.4f, 0.25f);
                break;
            case CREDITS:
                String[] lines = {
                    "A VOXEL SANDBOX GAME", "", "CREATED BY", "PACHUB/PACPERLAR",
                    "BUILT USING CLAUDE", "", "THANKS FOR PLAYING"
                };
                float cs = 4;
                glColor3f(0.9f, 0.9f, 0.95f);
                float y = height * 0.30f;
                for (String ln : lines) {
                    if (!ln.isEmpty()) Font5x7.draw(ln, width/2f - Font5x7.width(ln, cs)/2, y, cs);
                    y += 9 * cs;
                }
                drawButton(backRect(), "BACK", 0.5f, 0.4f, 0.25f);
                break;
        }

        glEnable(GL_TEXTURE_2D);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glPopMatrix();
        glMatrixMode(GL_PROJECTION); glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
    }

    private String onOff(boolean b) { return b ? "ON" : "OFF"; }
    private String modeName() { return superMode ? "SUPER" : creativeMode ? "CREATIVE" : "SURVIVAL"; }
    private void cycleMode() {
        if (!creativeMode && !superMode) { creativeMode = true; superMode = false; }
        else if (creativeMode) { creativeMode = false; superMode = true; }
        else { creativeMode = false; superMode = false; }
    }

    private void drawPauseMenu() {
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity();
        glOrtho(0, width, height, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity();
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // dim the world
        glColor4f(0, 0, 0, 0.5f);
        quad(0, 0, width, height);

        glColor3f(0.85f, 0.95f, 0.6f);
        String title = "PAUSED";
        float ts = 9;
        Font5x7.draw(title, width/2f - Font5x7.width(title, ts)/2, height * 0.2f, ts);

        drawButton(menuRect(0), "CONTINUE", 0.25f, 0.55f, 0.3f);
        drawButton(menuRect(1), "SAVE WORLD", 0.3f, 0.45f, 0.6f);
        drawButton(menuRect(2), "SAVE AND QUIT", 0.5f, 0.4f, 0.25f);

        glDisable(GL_BLEND);
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
        // label centered, shrunk to fit the button width
        float bw = r[2] - r[0];
        float ls = Math.min(5f, (bw - 24) / (label.length() * 6));
        glColor3f(1, 1, 1);
        float lw = Font5x7.width(label, ls), lh = 7 * ls;
        Font5x7.draw(label, (r[0] + r[2]) / 2 - lw / 2, (r[1] + r[3]) / 2 - lh / 2, ls);
    }

    /** Row of hearts at the bottom-centre showing the player's health. */
    private void drawHearts() {
        int n = (int) Player.MAX_HEARTS;
        float hs = 34, gap = 4;
        float totalW = n * (hs + gap) - gap;
        float x = width / 2f - totalW / 2;
        float y = height - hs - 24 - 72; // sit above the hotbar
        for (int i = 0; i < n; i++) {
            double val = player.health - i;
            float hx = x + i * (hs + gap);
            // empty container (dark)
            glDisable(GL_TEXTURE_2D);
            glColor4f(0, 0, 0, 0.45f);
            quad(hx - 2, y - 2, hx + hs + 2, y + hs + 2);
            // full or half heart sprite
            int tex = val >= 1 ? heartFull : val >= 0.5 ? heartHalf : 0;
            if (tex != 0) {
                glEnable(GL_TEXTURE_2D);
                glBindTexture(GL_TEXTURE_2D, tex);
                glColor4f(1, 1, 1, 1);
                glBegin(GL_QUADS);
                glTexCoord2f(0, 0); glVertex2f(hx, y);
                glTexCoord2f(1, 0); glVertex2f(hx + hs, y);
                glTexCoord2f(1, 1); glVertex2f(hx + hs, y + hs);
                glTexCoord2f(0, 1); glVertex2f(hx, y + hs);
                glEnd();
                glDisable(GL_TEXTURE_2D);
            }
        }
    }

    /** F3 debug overlay: world/player info, time of day, next day/night flip. */
    private void drawDebug() {
        boolean night = isNight();
        String flip = (night ? "День через: " : "Ночь через: ") + (int) secondsUntilFlip() + "s";
        String[] lines = {
            "PACMINE  " + (int) fps + " fps",
            String.format("XYZ: %.1f %.1f %.1f", player.x, player.y, player.z),
            String.format("Facing: yaw %.0f pitch %.0f", player.yaw, player.pitch),
            world.infinite ? "World: infinite (streaming)" : ("World: " + world.cx + "x" + world.cz + " chunks (" + world.sx + "x" + world.sz + ")"),
            "Time: " + clock() + "  " + (night ? "(ночь)" : "(день)"),
            flip,
            superMode ? ("SUPER  wave " + wave + ", zombies " + zombies.size())
                      : (multiplayer ? ("Multiplayer: " + (isHost ? "host" : "client") + ", players " + (remotePlayers.size() + 1)) : "Singleplayer"),
        };
        float sz = 2f, lh = 9 * sz, x = 8, y = 130; // below the sword/progress panel
        glDisable(GL_TEXTURE_2D);
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glColor4f(0, 0, 0, 0.5f);
        quad(x - 4, y - 4, x + 320, y + lines.length * lh + 4);
        glColor3f(0.8f, 1f, 0.7f);
        for (String ln : lines) { Font5x7.draw(ln, x, y, sz); y += lh; }
        glDisable(GL_BLEND);
    }

    /** Draw nicknames (and a blue premium tick) above remote players. */
    private void drawNameTags() {
        if (!multiplayer || remotePlayers.isEmpty()) return;
        for (java.util.Map.Entry<Integer, double[]> en : remotePlayers.entrySet()) {
            int id = en.getKey();
            if (id == myId) continue;
            String nm = remoteNames.get(id);
            if (nm == null || nm.isEmpty()) nm = "Player";
            double[] p = en.getValue();
            float[] sc = worldToScreen(p[0], p[1] + 1.95, p[2]); // above the head
            if (sc == null) continue;
            float ps = 2.5f;
            float w = Font5x7.width(nm, ps);
            boolean prem = Boolean.TRUE.equals(remotePrem.get(id));
            float checkW = prem ? 9 * ps : 0;
            float x = sc[0] - (w + checkW) / 2, y = sc[1];
            // backdrop
            glDisable(GL_TEXTURE_2D);
            glColor4f(0, 0, 0, 0.5f);
            quad(x - 4, y - 3, x + w + checkW + 4, y + 7 * ps + 3);
            // name
            glColor3f(1, 1, 1);
            Font5x7.draw(nm, x, y, ps);
            // blue premium tick
            if (prem) drawCheck(x + w + 4 * ps, y, 7 * ps);
        }
    }

    /** A small blue check mark, bottom-left at (x,y), roughly `s` tall. */
    private void drawCheck(float x, float y, float s) {
        glColor3f(0.25f, 0.55f, 1f);
        glLineWidth(3);
        glBegin(GL_LINES);
        glVertex2f(x, y + s * 0.6f);          glVertex2f(x + s * 0.35f, y + s);
        glVertex2f(x + s * 0.35f, y + s);     glVertex2f(x + s, y);
        glEnd();
        glColor3f(1, 1, 1);
    }

    /** Project a world point to screen pixels using the current camera. Returns null if behind. */
    private float[] worldToScreen(double wx, double wy, double wz) {
        double dx = wx - player.x, dy = wy - (player.y + Player.EYE), dz = wz - player.z;
        double ry = Math.toRadians(-player.yaw);
        double x1 = dx * Math.cos(ry) + dz * Math.sin(ry);
        double z1 = -dx * Math.sin(ry) + dz * Math.cos(ry);
        double rx = Math.toRadians(-player.pitch);
        double y2 = dy * Math.cos(rx) - z1 * Math.sin(rx);
        double z2 = dy * Math.sin(rx) + z1 * Math.cos(rx);
        if (z2 >= -0.1) return null;                 // behind / too close
        double tanHalf = Math.tan(Math.toRadians(70) / 2);
        double aspect = (double) width / height;
        double ndcx = (x1 / (-z2)) / (tanHalf * aspect);
        double ndcy = (y2 / (-z2)) / tanHalf;
        float sx = (float) ((ndcx * 0.5 + 0.5) * width);
        float sy = (float) ((1 - (ndcy * 0.5 + 0.5)) * height);
        return new float[]{sx, sy};
    }

    // all block types shown in the inventory grid
    private static final byte[] ALL_BLOCKS = {
        World.GRASS, World.DIRT, World.STONE, World.WOOD, World.LEAVES, World.SAND,
        World.COAL, World.IRON
    };
    private float invCell = 50, invGap = 6;
    private float invOriginX() { return width / 2f - (9 * (invCell + invGap) - invGap) / 2; }
    private float invOriginY() { return height / 2f - (9 * (invCell + invGap) - invGap) / 2; }

    private void inventoryClick() {
        float ox = invOriginX(), oy = invOriginY();
        for (int i = 0; i < ALL_BLOCKS.length; i++) {
            int row = i / 9, col = i % 9;
            float x = ox + col * (invCell + invGap), y = oy + row * (invCell + invGap);
            if (mouseX >= x && mouseX <= x + invCell && mouseY >= y && mouseY <= y + invCell) {
                hotbar[selectedSlot] = ALL_BLOCKS[i];  // put this block into the active hotbar slot
                return;
            }
        }
    }

    /** 9x9 inventory overlay showing collected blocks and counts (open with E). */
    private void drawInventory() {
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity();
        glOrtho(0, width, height, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity();
        glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glDisable(GL_TEXTURE_2D);
        glColor4f(0, 0, 0, 0.6f); quad(0, 0, width, height);

        float ox = invOriginX(), oy = invOriginY();
        float gw = 9 * (invCell + invGap) - invGap, gh = gw;
        glColor4f(0.16f, 0.18f, 0.24f, 0.97f); quad(ox - 14, oy - 40, ox + gw + 14, oy + gh + 14);
        glColor3f(0.85f, 0.95f, 0.6f);
        Font5x7.draw("INVENTORY", ox, oy - 34, 4);

        for (int i = 0; i < 81; i++) {
            int row = i / 9, col = i % 9;
            float x = ox + col * (invCell + invGap), y = oy + row * (invCell + invGap);
            glDisable(GL_TEXTURE_2D);
            glColor4f(0, 0, 0, 0.4f); quad(x, y, x + invCell, y + invCell);
            if (i < ALL_BLOCKS.length) {
                byte b = ALL_BLOCKS[i];
                glEnable(GL_TEXTURE_2D); atlas.bind();
                float[] uv = atlas.uv(atlas.tileFor(b, 2));
                glColor4f(1, 1, 1, 1);
                float p = 6;
                glBegin(GL_QUADS);
                glTexCoord2f(uv[0], uv[1]); glVertex2f(x + p, y + p);
                glTexCoord2f(uv[2], uv[1]); glVertex2f(x + invCell - p, y + p);
                glTexCoord2f(uv[2], uv[3]); glVertex2f(x + invCell - p, y + invCell - p);
                glTexCoord2f(uv[0], uv[3]); glVertex2f(x + p, y + invCell - p);
                glEnd();
                glDisable(GL_TEXTURE_2D);
                glColor3f(1, 1, 1);
                Font5x7.draw(String.valueOf(inv[b]), x + 4, y + invCell - 14, 1.6f);
            }
        }
        glDisable(GL_BLEND);
        glEnable(GL_TEXTURE_2D); glEnable(GL_CULL_FACE); glEnable(GL_DEPTH_TEST);
        glPopMatrix(); glMatrixMode(GL_PROJECTION); glPopMatrix(); glMatrixMode(GL_MODELVIEW);
    }

    /** Hotbar of block slots at the bottom-centre; selected slot is highlighted. */
    private void drawHotbar() {
        int n = hotbar.length;
        float slot = 56, gap = 6, pad = 6;
        float totalW = n * (slot + gap) - gap;
        float x0 = width / 2f - totalW / 2;
        float y0 = height - slot - 18;

        for (int i = 0; i < n; i++) {
            float sx = x0 + i * (slot + gap);
            boolean sel = i == selectedSlot;
            // slot background
            glDisable(GL_TEXTURE_2D);
            glColor4f(sel ? 0.9f : 0f, sel ? 0.9f : 0f, sel ? 0.5f : 0f, sel ? 0.6f : 0.45f);
            quad(sx - 2, y0 - 2, sx + slot + 2, y0 + slot + 2);
            // block icon from the atlas
            glEnable(GL_TEXTURE_2D);
            atlas.bind();
            float[] uv = atlas.uv(atlas.tileFor(hotbar[i], 2));
            glColor4f(1, 1, 1, 1);
            glBegin(GL_QUADS);
            glTexCoord2f(uv[0], uv[1]); glVertex2f(sx + pad, y0 + pad);
            glTexCoord2f(uv[2], uv[1]); glVertex2f(sx + slot - pad, y0 + pad);
            glTexCoord2f(uv[2], uv[3]); glVertex2f(sx + slot - pad, y0 + slot - pad);
            glTexCoord2f(uv[0], uv[3]); glVertex2f(sx + pad, y0 + slot - pad);
            glEnd();
            glDisable(GL_TEXTURE_2D);
            // count in the corner
            glColor3f(1, 1, 1);
            Font5x7.draw(String.valueOf(inv[hotbar[i]]), sx + 4, y0 + slot - 14, 1.5f);
        }
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

    public static void main(String[] args) {
        if (args.length > 0 && args[0].startsWith("--server.")) {
            runDedicatedServer(args[0].substring("--server.".length()));
            return;
        }
        new Main().run();
    }

    /** Headless dedicated server: ./run.sh --server.{ip|localhost} */
    private static void runDedicatedServer(String bind) {
        try {
            World world = new World(System.nanoTime(), 8, true);
            java.util.Queue<NetEvent> sink = new java.util.concurrent.ConcurrentLinkedQueue<>();
            NetServer s = new NetServer(world, true, sink);
            s.setDedicated();
            s.start();
            System.out.println("=== PACMine dedicated server ===");
            System.out.println("Listening on port " + NetServer.PORT);
            try {
                String lan = java.net.InetAddress.getLocalHost().getHostAddress();
                System.out.println("LAN address: " + lan);
            } catch (Exception ignored) {}
            if (bind.equalsIgnoreCase("localhost"))
                System.out.println("Mode: local/LAN — players join via 'localhost' (same PC) or your 192.168.x.x address.");
            else
                System.out.println("Mode: public — players join via '" + bind + "' (IP or domain pointing to it). Forward port " + NetServer.PORT + ".");
            System.out.println("Press Ctrl+C to stop.");
            while (true) {
                sink.clear();                 // drop host-facing events; nobody is hosting locally
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        } catch (IOException e) {
            System.err.println("Server failed to start: " + e.getMessage());
        }
    }
}
