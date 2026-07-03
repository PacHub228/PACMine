package com.voxel;

/**
 * A dropped block item: pops out of a broken block, falls with gravity,
 * rests on the ground bobbing and spinning, and despawns after LIFETIME.
 */
public class ItemDrop {
    public static final double LIFETIME = 120;   // seconds before despawn

    public final byte type;
    public double x, y, z;
    public double vx, vy, vz;
    public double age;

    private final World world;

    public ItemDrop(World world, byte type, double x, double y, double z) {
        this.world = world; this.type = type;
        this.x = x; this.y = y; this.z = z;
        double a = Math.random() * Math.PI * 2;   // small random pop impulse
        vx = Math.cos(a) * 1.2; vz = Math.sin(a) * 1.2; vy = 3.5;
    }

    public void update(double dt) {
        age += dt;
        // horizontal motion, blocked by walls
        double nx = x + vx * dt, nz = z + vz * dt;
        if (!world.isSolid((int) Math.floor(nx), (int) Math.floor(y + 0.1), (int) Math.floor(nz))) {
            x = nx; z = nz;
        } else { vx = 0; vz = 0; }
        // gravity + landing
        vy -= 22 * dt;
        y += vy * dt;
        int by = (int) Math.floor(y);
        if (world.isSolid((int) Math.floor(x), by, (int) Math.floor(z))) {
            y = by + 1; vy = 0;
            vx *= Math.pow(0.02, dt); vz *= Math.pow(0.02, dt);   // ground friction
        }
    }

    public boolean expired() { return age > LIFETIME; }
}
