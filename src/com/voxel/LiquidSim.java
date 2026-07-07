package com.voxel;

/**
 * Cellular liquid physics. Water and lava fall straight down and spread
 * sideways with limited reach (water 6, lava 3 — and lava flows at half
 * speed). Water meeting lava hardens into stone. Event-driven: still
 * liquid sleeps until a block changes nearby (disturb), so oceans cost
 * nothing while nobody is digging them.
 */
public class LiquidSim {
    /** Called after the sim changed a block, for re-meshing / network sync. */
    public interface Sync { void changed(int x, int y, int z, byte b); }

    private static final double STEP = 0.25;    // seconds per flow step
    private static final int MAX_OPS = 400;     // cells per step (avoid hitches)

    private final World world;
    private final Sync sync;
    private final java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
    private final java.util.HashSet<Long> queued = new java.util.HashSet<>();
    private final java.util.HashMap<Long, Integer> depth = new java.util.HashMap<>(); // flow reach left
    private double acc = 0;
    private long stepCount = 0;

    public LiquidSim(World world, Sync sync) { this.world = world; this.sync = sync; }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFFL);
    }

    /** A block changed at (x,y,z): wake this cell and the surrounding liquid. */
    public synchronized void disturb(int x, int y, int z) {
        enq(x, y, z);
        enq(x + 1, y, z); enq(x - 1, y, z);
        enq(x, y + 1, z); enq(x, y - 1, z);
        enq(x, y, z + 1); enq(x, y, z - 1);
    }

    private void enq(int x, int y, int z) {
        if (!World.isLiquid(world.get(x, y, z))) return;
        if (queued.add(key(x, y, z))) queue.add(new int[]{x, y, z});
    }

    public synchronized void tick(double dt) {
        acc += dt;
        while (acc >= STEP) { acc -= STEP; step(); }
    }

    private void step() {
        stepCount++;
        int n = Math.min(queue.size(), MAX_OPS);
        for (int i = 0; i < n; i++) {
            int[] p = queue.poll();
            long k = key(p[0], p[1], p[2]);
            byte b = world.get(p[0], p[1], p[2]);
            if (b == World.LAVA && (stepCount & 1) == 0) {   // lava is sluggish
                queue.add(p);                                 // retry next step
                continue;
            }
            queued.remove(k);
            flow(p[0], p[1], p[2], b);
        }
    }

    private void flow(int x, int y, int z, byte b) {
        if (!World.isLiquid(b)) return;
        int max = b == World.WATER ? 6 : 3;
        int d = depth.getOrDefault(key(x, y, z), max);

        // fall
        if (y > 0 && world.inBounds(x, y - 1, z)) {
            byte below = world.get(x, y - 1, z);
            if (below == World.AIR) { place(x, y - 1, z, b, max); return; }
            if (World.isLiquid(below) && below != b) { harden(x, y - 1, z); return; }
        }
        // spread sideways along a supported surface
        if (d <= 1) return;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dd : dirs) {
            int nx = x + dd[0], nz = z + dd[1];
            if (!world.inBounds(nx, y, nz)) continue;
            byte t = world.get(nx, y, nz);
            if (t == World.AIR) place(nx, y, nz, b, d - 1);
            else if (World.isLiquid(t) && t != b) harden(nx, y, nz);
        }
    }

    private void place(int x, int y, int z, byte b, int d) {
        byte other = b == World.WATER ? World.LAVA : World.WATER;
        if (world.get(x, y, z) == other) { harden(x, y, z); return; }
        world.set(x, y, z, b);
        depth.put(key(x, y, z), d);
        sync.changed(x, y, z, b);
        disturb(x, y, z);
    }

    /** Water + lava = stone. */
    private void harden(int x, int y, int z) {
        world.set(x, y, z, World.STONE);
        depth.remove(key(x, y, z));
        sync.changed(x, y, z, World.STONE);
        disturb(x, y, z);
    }
}
