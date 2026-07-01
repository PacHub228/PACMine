package com.voxel;

/**
 * Zombie with a bit of pathing "brain": it walks toward the player, steps up
 * 1-block ledges, falls with gravity, and — when a wall blocks the way — mines
 * the obstructing block to break through. Super-mode zombies are faster and
 * strafe like a duelling player.
 */
public class Zombie {
    /** Called when the zombie breaks a world block (host applies + re-meshes). */
    public interface Breaker { void breakAt(int x, int y, int z); }

    public double x, y, z;   // feet position (centre on x/z)
    public float yaw;        // degrees, 0 = facing +Z
    public boolean alive = true;
    public boolean combat = false;
    private double phase = Math.random() * Math.PI * 2;

    private static final double ATTACK_RANGE = 2.0;
    private double attackTimer = 0;

    public static final double WIDTH = 0.6, DEPTH = 0.4, BODY_H = 0.9, HEAD = 0.5;
    public static final double HEIGHT = BODY_H + HEAD;
    private static final double SPEED = 2.2, COMBAT_SPEED = 4.3, GRAVITY = 24;

    private double vy = 0, breakTimer = 0;
    private int breakX = Integer.MIN_VALUE, breakY, breakZ;

    private final World world;

    public Zombie(World world, double x, double y, double z) {
        this.world = world; this.x = x; this.y = y; this.z = z;
    }

    private boolean solid(int bx, int by, int bz) { return world.isSolid(bx, by, bz); }

    public void update(Player p, double dt, Breaker breaker) {
        double dx = p.x - x, dz = p.z - z;
        double dist = Math.hypot(dx, dz);
        if (dist > 1e-3) yaw = (float) Math.toDegrees(Math.atan2(dx, dz));

        // desired horizontal velocity
        double vx = 0, vz = 0;
        if (combat) {
            phase += dt * 2.2;
            double nx = dist > 1e-3 ? dx / dist : 0, nz = dist > 1e-3 ? dz / dist : 0;
            double perpx = -nz, perpz = nx, approach, strafe;
            if (dist > 4.5)      { approach = 1.0; strafe = 0.15; }
            else if (dist > 2.0) { approach = 0.7; strafe = 0.6;  }
            else                 { approach = -0.25; strafe = 1.0; }
            strafe *= Math.sin(phase);
            double mx = nx * approach + perpx * strafe, mz = nz * approach + perpz * strafe;
            double ml = Math.hypot(mx, mz);
            if (ml > 1e-3) { vx = mx / ml * COMBAT_SPEED; vz = mz / ml * COMBAT_SPEED; }
        } else if (dist > 1.4) {
            vx = dx / dist * SPEED; vz = dz / dist * SPEED;
        }

        // move axis-by-axis, stepping up or mining through obstacles
        moveH(vx * dt, 0, dt, breaker);
        moveH(0, vz * dt, dt, breaker);
        applyGravity(dt);
        digToward(p, dt, breaker);

        // attack
        double cooldown = combat ? 0.9 : 1.5, damage = combat ? 1.0 : 0.5;
        if (attackTimer > 0) attackTimer -= dt;
        if (dist <= ATTACK_RANGE && Math.abs(p.y - y) < 1.5 && attackTimer <= 0) {
            p.hurt(damage, x, z, 0.5);
            attackTimer = cooldown;
        }

        if (x < 1) x = 1; if (x > world.sx - 1) x = world.sx - 1;
        if (z < 1) z = 1; if (z > world.sz - 1) z = world.sz - 1;
    }

    /** Move along one horizontal axis; step up a ledge or mine the wall if blocked. */
    private void moveH(double ddx, double ddz, double dt, Breaker breaker) {
        if (ddx == 0 && ddz == 0) return;
        double nx = x + ddx, nz = z + ddz;
        int fy = (int) Math.floor(y);
        int bx = (int) Math.floor(nx + (ddx > 0 ? 0.3 : ddx < 0 ? -0.3 : 0));
        int bz = (int) Math.floor(nz + (ddz > 0 ? 0.3 : ddz < 0 ? -0.3 : 0));
        boolean feet = solid(bx, fy, bz), head = solid(bx, fy + 1, bz);
        if (!feet && !head) { x = nx; z = nz; return; }               // clear
        // step up a single block
        if (feet && !head && !solid(bx, fy + 2, bz)
                && !solid((int) Math.floor(x), fy + 2, (int) Math.floor(z))) {
            y = fy + 1; x = nx; z = nz; return;
        }
        // otherwise mine the obstruction
        tryBreak(bx, feet ? fy : fy + 1, bz, dt, breaker);
    }

    private void applyGravity(double dt) {
        int fx = (int) Math.floor(x), fz = (int) Math.floor(z), fy = (int) Math.floor(y);
        boolean ground = solid(fx, fy - 1, fz);
        if (ground && y - fy < 0.02) { vy = 0; y = fy; return; }       // resting on a block
        vy -= GRAVITY * dt;
        y += vy * dt;
        int ny = (int) Math.floor(y);
        if (solid(fx, ny, fz)) { y = ny + 1; vy = 0; }                 // landed
    }

    /** If the player is below and there's floor in the way, dig down toward them. */
    private void digToward(Player p, double dt, Breaker breaker) {
        int fx = (int) Math.floor(x), fz = (int) Math.floor(z), fy = (int) Math.floor(y);
        if (p.y < y - 1.2 && solid(fx, fy - 1, fz)) tryBreak(fx, fy - 1, fz, dt, breaker);
    }

    private void tryBreak(int tx, int ty, int tz, double dt, Breaker breaker) {
        if (world.get(tx, ty, tz) == World.BEDROCK) return;           // can't mine bedrock
        if (tx != breakX || ty != breakY || tz != breakZ) { breakX = tx; breakY = ty; breakZ = tz; breakTimer = 0; }
        breakTimer += dt;
        double need = combat ? 0.6 : 1.0;                              // seconds to break
        if (breakTimer >= need) { breaker.breakAt(tx, ty, tz); breakTimer = 0; breakX = Integer.MIN_VALUE; }
    }
}
