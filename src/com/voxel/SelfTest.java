package com.voxel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Headless checks for everything in PACMine that does not need OpenGL:
 * world generation, chunk math, the save format, the obfuscation layer,
 * crafting tables and the frustum maths used by the renderer.
 *
 * Run with:  java -cp out com.voxel.SelfTest
 * Exit code is 0 when everything passes, 1 otherwise (CI friendly).
 */
public class SelfTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        System.out.println("PACMine self-test");
        noise();
        chunkKeys();
        finiteWorld();
        infiniteWorld();
        seams();
        saveLoad();
        crypt();
        frustum();
        recipes();
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name); }
    }

    // ---- world generation ----

    private static void noise() {
        Noise a = new Noise(42), b = new Noise(42), c = new Noise(43);
        boolean same = true, differs = false, inRange = true;
        for (int i = 0; i < 500; i++) {
            double x = i * 0.37, z = i * -0.11;
            double va = a.noise(x, z);
            if (va != b.noise(x, z)) same = false;
            if (Math.abs(va) > 1.0001) inRange = false;
            if (Math.abs(va - c.noise(x, z)) > 1e-9) differs = true;
        }
        check("noise: same seed is deterministic", same);
        check("noise: different seeds differ", differs);
        check("noise: stays within [-1,1]", inRange);
    }

    private static void chunkKeys() {
        long k = World.chunkKeyFor(-15, -29);
        check("chunk key: negative coords round-trip",
                World.chunkCX(k) == -2 && World.chunkCZ(k) == -3);
        long k2 = World.chunkKeyFor(0, 0);
        check("chunk key: origin maps to chunk 0,0",
                World.chunkCX(k2) == 0 && World.chunkCZ(k2) == 0);
        check("heightAt: stays inside the world",
                World.heightAt(new Noise(1), 1234, -5678) >= 1
                        && World.heightAt(new Noise(1), 1234, -5678) < World.SY);
    }

    private static void finiteWorld() {
        World w = new World(7L, 3, true);
        boolean bedrock = true;
        for (int x = 0; x < w.sx; x += 5)
            for (int z = 0; z < w.sz; z += 5)
                if (w.get(x, 0, z) != World.BEDROCK) bedrock = false;
        check("finite world: bedrock floor", bedrock);

        int water = 0, solidLow = 0;
        for (int x = 0; x < w.sx; x++)
            for (int z = 0; z < w.sz; z++) {
                if (w.get(x, World.SEA, z) == World.WATER) water++;
                if (w.isSolid(x, 2, z)) solidLow++;
            }
        check("finite world: ocean exists", water > 0);
        check("finite world: ground is solid below the surface", solidLow > 0);

        World copy = new World(7L, 3, true);
        check("finite world: same seed generates the same world",
                Arrays.equals(w.data(), copy.data()));
    }

    private static void infiniteWorld() {
        World a = World.createInfinite(555L, true);
        World b = World.createInfinite(555L, true);
        long key = World.chunkKeyFor(0, 0);
        byte[] ca = a.chunkData(key), cb = b.chunkData(key);
        check("infinite world: same seed, same chunk", Arrays.equals(ca, cb));
        check("infinite world: chunkData does not copy",
                a.chunkData(key) == a.chunkData(key));
        check("infinite world: chunk has expected size",
                ca.length == World.CHUNK * World.SY * World.CHUNK);

        long other = World.chunkKeyFor(World.CHUNK * 5, -World.CHUNK * 3);
        byte[] far = a.chunkData(other);
        check("infinite world: distant chunk generates", far != null && far.length == ca.length);
    }

    /**
     * A tree whose trunk stands right next to a chunk border must paint its
     * canopy into the neighbouring chunk. Before the neighbour-tree pass the
     * crown was simply clipped away and every border tree looked half-cut.
     */
    private static void seams() {
        World w = World.createInfinite(1234L, true);
        Noise n = new Noise(1234L);
        int trunksNearBorder = 0, canopiesAcross = 0;
        for (int cx = -1; cx <= 2; cx++) {
            for (int cz = -1; cz <= 2; cz++) {
                int bx = cx * World.CHUNK;            // first column of this chunk
                int bz0 = cz * World.CHUNK;
                for (int dz = 0; dz < World.CHUNK; dz++) {
                    int z = bz0 + dz;
                    int h = World.heightAt(n, bx, z);
                    if (h < 20) continue;
                    if (w.get(bx, h + 1, z) != World.WOOD) continue;   // no trunk here
                    trunksNearBorder++;
                    // this tree's crown reaches x = bx-1 and bx-2 (in the left chunk)
                    for (int y = h + 3; y <= h + 7; y++)
                        for (int dx = 1; dx <= 2; dx++)
                            if (w.get(bx - dx, y, z) == World.LEAVES) { canopiesAcross++; dx = 3; }
                }
            }
        }
        check("chunk seams: trees near a border exist at all", trunksNearBorder > 0);
        check("chunk seams: their canopies continue into the neighbour chunk", canopiesAcross > 0);
    }

    // ---- persistence ----

    private static void saveLoad() throws Exception {
        String name = "SELFTEST_WORLD";
        SaveGame.delete(name);
        World w = new World(99L, 3, true);
        SaveGame s = new SaveGame();
        s.chunks = w.cx;
        s.blocks = w.data();
        s.hostileMobs = true;
        s.creative = false;
        s.superMode = false;
        s.protection = true;
        s.px = 10.5; s.py = 30; s.pz = 12.5;
        s.yaw = 45; s.pitch = -12;
        s.health = 7.5;
        s.timeOfDay = 0.61;
        s.selectedSlot = 3;
        s.slotType = new byte[]{World.STONE, World.WOOD, World.AIR, World.GLASS};
        s.slotCount = new int[]{12, 3, 0, 7};
        s.save(name);

        SaveGame t = SaveGame.load(name);
        check("save: blocks survive a round-trip", Arrays.equals(s.blocks, t.blocks));
        check("save: player state survives", t.health == 7.5 && t.px == 10.5 && t.selectedSlot == 3);
        check("save: inventory survives",
                Arrays.equals(t.slotType, s.slotType) && Arrays.equals(t.slotCount, s.slotCount));
        check("save: time of day survives", Math.abs(t.timeOfDay - 0.61) < 1e-9);
        check("save: file is encrypted on disk", isEncrypted(name));
        SaveGame.delete(name);

        SaveGame inf = new SaveGame();
        inf.infinite = true;
        inf.seed = 555L;
        inf.chunkMap = new HashMap<>();
        byte[] chunk = new byte[World.CHUNK * World.SY * World.CHUNK];
        Arrays.fill(chunk, World.STONE);
        inf.chunkMap.put(World.chunkKeyFor(0, 0), chunk);
        inf.save("SELFTEST_INF");
        SaveGame back = SaveGame.load("SELFTEST_INF");
        check("save: infinite world chunk survives",
                back.infinite && back.seed == 555L
                        && Arrays.equals(back.chunkMap.get(World.chunkKeyFor(0, 0)), chunk));
        SaveGame.delete("SELFTEST_INF");
    }

    private static boolean isEncrypted(String name) {
        File f = new File(SaveGame.DIR, name + ".pms");
        if (!f.isFile()) return false;
        try {
            byte[] head = new byte[4];
            try (java.io.InputStream in = new java.io.FileInputStream(f)) { in.read(head); }
            return head[0] == 'P' && head[1] == 'M' && head[2] == 'C' && head[3] == '1';
        } catch (Exception e) {
            return false;
        }
    }

    private static void crypt() {
        byte[] plain = "PACMine save payload".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = PMCrypt.encrypt(plain);
        check("crypt: payload is obfuscated", !Arrays.equals(plain, encrypted));
        check("crypt: round-trip restores the payload", Arrays.equals(PMCrypt.decrypt(encrypted), plain));
        check("crypt: legacy plain files still load", Arrays.equals(PMCrypt.decrypt(plain), plain));
    }

    // ---- rendering maths ----

    private static void frustum() {
        Frustum f = new Frustum();
        float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        f.set(identity, identity);
        check("frustum: identity keeps the unit box",
                f.boxVisible(0, 0, 0, 0.5, 0.5, 0.5));
        check("frustum: identity culls a far-away box",
                !f.boxVisible(5, 5, 5, 6, 6, 6));

        // a real perspective camera looking down -Z
        float near = 0.1f, far = 300f, fov = (float) Math.toRadians(70), aspect = 16f / 9f;
        float t = (float) (1.0 / Math.tan(fov / 2));
        float[] proj = {
            t / aspect, 0, 0, 0,
            0, t, 0, 0,
            0, 0, (far + near) / (near - far), -1,
            0, 0, (2 * far * near) / (near - far), 0
        };
        f.set(proj, identity);
        check("frustum: box in front of the camera is visible",
                f.boxVisible(-1, -1, -6, 1, 1, -4));
        check("frustum: box behind the camera is culled",
                !f.boxVisible(-1, -1, 4, 1, 1, 6));
        check("frustum: box far to the side is culled",
                !f.boxVisible(200, -1, -6, 202, 1, -4));
    }

    // ---- gameplay tables ----

    private static void recipes() {
        check("recipes: sand smelts to glass", Recipes.smeltResult(World.SAND) == World.GLASS);
        check("recipes: iron ore smelts to an ingot", Recipes.smeltResult(World.IRON) == World.INGOT_IRON);
        check("recipes: wood smelts to charcoal", Recipes.smeltResult(World.WOOD) == World.COAL);
        check("recipes: coal is fuel, dirt is not",
                Recipes.fuelValue(World.COAL) == 8 && Recipes.fuelValue(World.DIRT) == 0);
        check("recipes: crafting table is populated", Recipes.ALL.length >= 6);
    }
}
