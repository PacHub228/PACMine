package com.voxel;

/**
 * Villager NPC for singleplayer worlds: wanders around like an animal but
 * looks like a player, has a name tag and chats at players who come close.
 * Persisted in the world save.
 */
public class Npc {
    public String name;
    public double x, y, z;
    public float yaw;
    public double chatCooldown = 0;   // seconds until it may speak again

    private double vy = 0;
    private double ang = Math.random() * Math.PI * 2;
    private double moveTimer = 0, pauseTimer = 1 + Math.random() * 3;

    private final World world;

    public Npc(World world, String name, double x, double y, double z) {
        this.world = world; this.name = name; this.x = x; this.y = y; this.z = z;
    }

    private boolean solid(int bx, int by, int bz) { return world.isSolid(bx, by, bz); }

    public void update(double dt) {
        if (chatCooldown > 0) chatCooldown -= dt;
        if (pauseTimer > 0) {
            pauseTimer -= dt;                        // standing, contemplating blocks
        } else {
            moveTimer -= dt;
            if (moveTimer <= 0) {
                if (Math.random() < 0.5) pauseTimer = 2 + Math.random() * 4;
                ang = Math.random() * Math.PI * 2;
                moveTimer = 2 + Math.random() * 4;
            }
            double vx = Math.sin(ang), vz = Math.cos(ang);
            boolean inLiquid = World.isLiquid(world.get((int) Math.floor(x), (int) Math.floor(y + 0.2), (int) Math.floor(z)));
            double sp = inLiquid ? 0.5 : 1.1;
            yaw = (float) Math.toDegrees(Math.atan2(vx, vz));
            moveH(vx * sp * dt, 0);
            moveH(0, vz * sp * dt);
        }
        if (World.isLiquid(world.get((int) Math.floor(x), (int) Math.floor(y + 0.2), (int) Math.floor(z)))) {
            vy += 6 * dt;                            // villagers can swim
            if (vy > 1.4) vy = 1.4;
            y += vy * dt;
        } else {
            applyGravity(dt);
        }
        if (!world.infinite) {
            if (x < 1) x = 1; if (x > world.sx - 1) x = world.sx - 1;
            if (z < 1) z = 1; if (z > world.sz - 1) z = world.sz - 1;
        }
    }

    private void moveH(double ddx, double ddz) {
        if (ddx == 0 && ddz == 0) return;
        double nx = x + ddx, nz = z + ddz;
        int fy = (int) Math.floor(y);
        int bx = (int) Math.floor(nx + (ddx > 0 ? 0.3 : ddx < 0 ? -0.3 : 0));
        int bz = (int) Math.floor(nz + (ddz > 0 ? 0.3 : ddz < 0 ? -0.3 : 0));
        boolean feet = solid(bx, fy, bz), head = solid(bx, fy + 1, bz);
        if (!feet && !head) { x = nx; z = nz; return; }
        if (feet && !head && !solid(bx, fy + 2, bz)
                && !solid((int) Math.floor(x), fy + 2, (int) Math.floor(z))) {
            y = fy + 1; x = nx; z = nz; return;
        }
        ang = Math.random() * Math.PI * 2;           // wall: pick another direction
    }

    private void applyGravity(double dt) {
        int fx = (int) Math.floor(x), fz = (int) Math.floor(z), fy = (int) Math.floor(y);
        boolean ground = solid(fx, fy - 1, fz);
        if (ground && y - fy < 0.02) { vy = 0; y = fy; return; }
        vy -= 24 * dt;
        y += vy * dt;
        int ny = (int) Math.floor(y);
        if (solid(fx, ny, fz)) { y = ny + 1; vy = 0; }
    }
}
