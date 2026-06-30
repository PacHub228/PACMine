package com.voxel;

/**
 * A simple zombie entity: walks toward the player across the terrain surface.
 * No real physics — it snaps to the ground height each tick, which is enough
 * for a mob that wanders over hills.
 */
public class Zombie {
    public double x, y, z;   // feet position (centre on x/z)
    public float yaw;        // degrees, 0 = facing +Z
    public boolean alive = true;
    public boolean combat = false;          // Super mode: armed, strafing, fast
    private double phase = Math.random() * Math.PI * 2;

    private static final double ATTACK_RANGE = 2.0;
    private double attackTimer = 0;

    public static final double WIDTH = 0.6, DEPTH = 0.4, BODY_H = 0.9, HEAD = 0.5;
    public static final double HEIGHT = BODY_H + HEAD;
    private static final double SPEED = 2.2;
    private static final double COMBAT_SPEED = 4.3;   // player-like

    private final World world;

    public Zombie(World world, double x, double y, double z) {
        this.world = world; this.x = x; this.y = y; this.z = z;
    }

    public void update(Player p, double dt) {
        double dx = p.x - x, dz = p.z - z;
        double dist = Math.hypot(dx, dz);
        if (dist > 1e-3) yaw = (float) Math.toDegrees(Math.atan2(dx, dz));

        if (combat) {
            // strafe around the player like a duelling player
            phase += dt * 2.2;
            double nx = dist > 1e-3 ? dx / dist : 0, nz = dist > 1e-3 ? dz / dist : 0;
            double perpx = -nz, perpz = nx;
            double approach, strafe;
            if (dist > 4.5)      { approach = 1.0; strafe = 0.15; } // far: charge straight in
            else if (dist > 2.0) { approach = 0.7; strafe = 0.6;  } // mid: close while weaving
            else                 { approach = -0.25; strafe = 1.0; } // close: back off & circle
            strafe *= Math.sin(phase);
            double mx = nx * approach + perpx * strafe;
            double mz = nz * approach + perpz * strafe;
            double ml = Math.hypot(mx, mz);
            if (ml > 1e-3) { x += mx / ml * COMBAT_SPEED * dt; z += mz / ml * COMBAT_SPEED * dt; }
        } else if (dist > 1.6) {           // normal zombie: straight chase
            x += dx / dist * SPEED * dt;
            z += dz / dist * SPEED * dt;
        }

        // attack the player when close enough, respecting the cooldown
        double cooldown = combat ? 0.9 : 1.5;
        double damage = combat ? 1.0 : 0.5;
        if (attackTimer > 0) attackTimer -= dt;
        boolean sameLevel = Math.abs(p.y - y) < 1.5;
        if (dist <= ATTACK_RANGE && sameLevel && attackTimer <= 0) {
            p.hurt(damage, x, z, 0.5);
            attackTimer = cooldown;
        }
        // keep inside the world
        if (x < 1) x = 1; if (x > world.sx - 1) x = world.sx - 1;
        if (z < 1) z = 1; if (z > world.sz - 1) z = world.sz - 1;

        // target ground surface at this column (ignore tree leaves/logs)
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int sy = World.SY - 1;
        while (sy > 0) {
            byte b = world.get(bx, sy, bz);
            if (b != World.AIR && b != World.LEAVES && b != World.WOOD) break;
            sy--;
        }
        double targetY = sy + 1;
        // smooth vertical movement so crossing block edges doesn't make it pop
        y += (targetY - y) * Math.min(1.0, 12 * dt);
    }
}
