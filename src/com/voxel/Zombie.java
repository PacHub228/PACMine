package com.voxel;

/**
 * Zombie with a bit of pathing "brain": it walks toward the player, steps up
 * 1-block ledges, falls with gravity, mines blocks that stand in the way and
 * pillars up (placing blocks) when the player is above. Far away it wanders
 * idly instead of beelining. Variants: NORMAL, RUNNER (small, fast, weak),
 * BRUTE (big, slow, tanky, hits harder). Super-mode zombies are faster and
 * strafe like a duelling player.
 */
public class Zombie {
    /** World edits requested by the zombie (host applies + re-meshes). */
    public interface Breaker {
        void breakAt(int x, int y, int z);
        default void placeAt(int x, int y, int z) {}
    }

    public static final int NORMAL = 0, RUNNER = 1, BRUTE = 2;

    public double x, y, z;   // feet position (centre on x/z)
    public float yaw;        // degrees, 0 = facing +Z
    public boolean alive = true;
    public boolean combat = false;
    public int type = NORMAL;
    public double hp;
    public double scale;     // render size multiplier
    private double speed, damage, attackCooldown;
    private double phase = Math.random() * Math.PI * 2;

    private static final double ATTACK_RANGE = 2.0;
    private static final double AGGRO_RANGE = 26;    // beyond this it wanders
    private double attackTimer = 0;

    // idle wandering
    private double wanderAng = Math.random() * Math.PI * 2, wanderTimer = 0;

    public static final double WIDTH = 0.6, DEPTH = 0.4, BODY_H = 0.9, HEAD = 0.5;
    public static final double HEIGHT = BODY_H + HEAD;
    private static final double GRAVITY = 24;

    private double vy = 0, breakTimer = 0, pillarTimer = 0;
    private int breakX = Integer.MIN_VALUE, breakY, breakZ;

    private final World world;

    public Zombie(World world, double x, double y, double z) { this(world, x, y, z, NORMAL); }

    public Zombie(World world, double x, double y, double z, int type) {
        this.world = world; this.x = x; this.y = y; this.z = z;
        setType(type);
    }

    /** Apply per-variant stats. */
    public void setType(int t) {
        type = t;
        switch (t) {
            case RUNNER: hp = 1; scale = 0.7;  speed = 3.6; damage = 0.5; attackCooldown = 0.9; break;
            case BRUTE:  hp = 5; scale = 1.35; speed = 1.5; damage = 1.5; attackCooldown = 1.8; break;
            default:     hp = 2; scale = 1.0;  speed = 2.2; damage = 0.5; attackCooldown = 1.5; break;
        }
    }

    /** Take a sword hit: knockback away from the player. Returns true if it died. */
    public boolean hit(double dmg, double srcX, double srcZ) {
        hp -= dmg;
        double dx = x - srcX, dz = z - srcZ, len = Math.hypot(dx, dz);
        if (len > 1e-4) {
            double kb = 0.9 / (type == BRUTE ? 2 : 1);
            double nx = x + dx / len * kb, nz = z + dz / len * kb;
            int fy = (int) Math.floor(y);
            if (!world.isSolid((int) Math.floor(nx), fy, (int) Math.floor(nz))
                && !world.isSolid((int) Math.floor(nx), fy + 1, (int) Math.floor(nz))) { x = nx; z = nz; }
        }
        if (hp <= 0) { alive = false; return true; }
        return false;
    }

    private boolean solid(int bx, int by, int bz) { return world.isSolid(bx, by, bz); }

    public void update(Player p, double dt, Breaker breaker) {
        double dx = p.x - x, dz = p.z - z;
        double dist = Math.hypot(dx, dz);

        // siege mode: the player is perched above us nearby — stand still and
        // either mine their support block or pillar up (no more circling)
        boolean siege = p.y - y > 1.6 && dist < 3.2;

        // desired horizontal velocity
        double vx = 0, vz = 0;
        if (siege) {
            if (dist > 1e-3) yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
        } else if (combat) {
            if (dist > 1e-3) yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
            phase += dt * 2.2;
            double spd = speed * 2.0;
            double nx = dist > 1e-3 ? dx / dist : 0, nz = dist > 1e-3 ? dz / dist : 0;
            double perpx = -nz, perpz = nx, approach, strafe;
            if (dist > 4.5)      { approach = 1.0; strafe = 0.15; }
            else if (dist > 2.0) { approach = 0.7; strafe = 0.6;  }
            else                 { approach = -0.25; strafe = 1.0; }
            strafe *= Math.sin(phase);
            double mx = nx * approach + perpx * strafe, mz = nz * approach + perpz * strafe;
            double ml = Math.hypot(mx, mz);
            if (ml > 1e-3) { vx = mx / ml * spd; vz = mz / ml * spd; }
        } else if (dist > AGGRO_RANGE) {
            // out of aggro range: wander slowly, re-rolling direction now and then
            wanderTimer -= dt;
            if (wanderTimer <= 0) { wanderAng = Math.random() * Math.PI * 2; wanderTimer = 2 + Math.random() * 3; }
            vx = Math.sin(wanderAng) * speed * 0.4;
            vz = Math.cos(wanderAng) * speed * 0.4;
            yaw = (float) Math.toDegrees(Math.atan2(vx, vz));
        } else if (dist > 1.4) {
            yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
            vx = dx / dist * speed; vz = dz / dist * speed;
        } else if (dist > 1e-3) {
            yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
        }

        // move axis-by-axis, stepping up or mining through obstacles
        moveH(vx * dt, 0, dt, breaker);
        moveH(0, vz * dt, dt, breaker);
        applyGravity(dt);
        digToward(p, dt, breaker);
        if (siege) siege(p, dt, breaker);

        // attack
        if (attackTimer > 0) attackTimer -= dt;
        double dmg = combat ? Math.max(damage, 1.0) : damage;
        double cd = combat ? Math.min(attackCooldown, 0.9) : attackCooldown;
        if (dist <= ATTACK_RANGE && Math.abs(p.y - y) < 1.5 && attackTimer <= 0) {
            p.hurt(dmg, x, z, 0.5);
            attackTimer = cd;
        }

        if (!world.infinite) {
            if (x < 1) x = 1; if (x > world.sx - 1) x = world.sx - 1;
            if (z < 1) z = 1; if (z > world.sz - 1) z = world.sz - 1;
        }
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

    /** Player is above and close: mine the block they stand on, or pillar up to them. */
    private void siege(Player p, double dt, Breaker breaker) {
        pillarTimer -= dt;
        int px = (int) Math.floor(p.x), pz = (int) Math.floor(p.z);
        int py = (int) Math.floor(p.y) - 1;                            // their support block
        // within arm's reach (up to ~3 blocks above our feet): chew the support out
        if (py - (int) Math.floor(y) <= 3 && solid(px, py, pz)
                && world.get(px, py, pz) != World.BEDROCK) {
            tryBreak(px, py, pz, dt, breaker);
            return;
        }
        // too high to reach: build a pillar underneath ourselves
        if (pillarTimer > 0) return;
        int fx = (int) Math.floor(x), fz = (int) Math.floor(z), fy = (int) Math.floor(y);
        if (!solid(fx, fy - 1, fz)) return;                            // must stand on ground
        if (solid(fx, fy + 2, fz)) return;                             // ceiling — no room
        breaker.placeAt(fx, fy, fz);                                   // block under the new feet
        y = fy + 1;
        pillarTimer = 1.0;
    }

    private void tryBreak(int tx, int ty, int tz, double dt, Breaker breaker) {
        if (world.get(tx, ty, tz) == World.BEDROCK) return;           // can't mine bedrock
        if (tx != breakX || ty != breakY || tz != breakZ) { breakX = tx; breakY = ty; breakZ = tz; breakTimer = 0; }
        breakTimer += dt;
        double need = (combat ? 0.6 : 1.0) * (type == BRUTE ? 0.7 : 1.0);  // brutes smash faster
        if (breakTimer >= need) { breaker.breakAt(tx, ty, tz); breakTimer = 0; breakX = Integer.MIN_VALUE; }
    }
}
