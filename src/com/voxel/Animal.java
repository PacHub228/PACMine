package com.voxel;

/**
 * Passive mob: pig, cow or chicken. Wanders around in walk/pause cycles,
 * steps up single-block ledges and falls with gravity. Spawns only during
 * the day; killed with one hit and drops a healing heart.
 */
public class Animal {
    public static final int PIG = 0, COW = 1, CHICKEN = 2;

    public double x, y, z;   // feet position (centre on x/z)
    public float yaw;
    public int type;

    private double vy = 0;
    private double ang = Math.random() * Math.PI * 2;
    private double moveTimer = 0, pauseTimer = 0;

    private final World world;

    public Animal(World world, double x, double y, double z, int type) {
        this.world = world; this.x = x; this.y = y; this.z = z; this.type = type;
    }

    private double speed() { return type == CHICKEN ? 1.3 : 0.9; }

    private boolean solid(int bx, int by, int bz) { return world.isSolid(bx, by, bz); }

    public void update(double dt) {
        if (pauseTimer > 0) {
            pauseTimer -= dt;                       // standing around, chewing air
        } else {
            moveTimer -= dt;
            if (moveTimer <= 0) {
                if (Math.random() < 0.4) pauseTimer = 1 + Math.random() * 2.5;
                ang = Math.random() * Math.PI * 2;
                moveTimer = 2 + Math.random() * 3;
            }
            double vx = Math.sin(ang) * speed(), vz = Math.cos(ang) * speed();
            yaw = (float) Math.toDegrees(Math.atan2(vx, vz));
            moveH(vx * dt, 0);
            moveH(0, vz * dt);
        }
        applyGravity(dt);

        if (!world.infinite) {
            if (x < 1) x = 1; if (x > world.sx - 1) x = world.sx - 1;
            if (z < 1) z = 1; if (z > world.sz - 1) z = world.sz - 1;
        }
    }

    /** Move along one axis; step up single ledges, otherwise turn away. */
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
            y = fy + 1; x = nx; z = nz; return;      // hop up a ledge
        }
        ang = Math.random() * Math.PI * 2;           // wall: wander elsewhere
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
