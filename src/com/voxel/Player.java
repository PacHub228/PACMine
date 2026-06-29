package com.voxel;

/**
 * First-person player with an AABB, gravity, jumping and axis-separated
 * collision against the voxel world. Position is the centre of the body at
 * feet level; the eye sits near the top.
 */
public class Player {
    public double x, y, z;        // feet position (centre on x/z)
    public float yaw, pitch;      // degrees
    public double vy;             // vertical velocity
    public boolean onGround;

    public static final double MAX_HEARTS = 10;
    public double health = MAX_HEARTS;

    public boolean creative = false;   // fly + invulnerable
    public boolean borderWalls = true; // invisible walls at world edges

    private static final double W = 0.3;   // half-width
    private static final double H = 1.8;    // total height
    public static final double EYE = 1.62;  // eye offset from feet

    private static final double GRAVITY = 28.0;
    private static final double JUMP = 9.0;
    private static final double SPEED = 6.0;

    private final World world;

    public Player(World world, double x, double y, double z) {
        this.world = world; this.x = x; this.y = y; this.z = z;
    }

    public void addYawPitch(float dyaw, float dpitch) {
        yaw += dyaw;
        pitch += dpitch;
        if (pitch > 89) pitch = 89;
        if (pitch < -89) pitch = -89;
    }

    public void jump() { if (onGround) { vy = JUMP; onGround = false; } }

    public boolean isDead() { return health <= 0; }

    /** Take damage (in hearts) and get knocked back away from the source. */
    public void hurt(double hearts, double srcX, double srcZ, double knockback) {
        if (creative) return;
        health -= hearts;
        double dx = x - srcX, dz = z - srcZ;
        double len = Math.hypot(dx, dz);
        if (len < 1e-4) { dx = 1; dz = 0; len = 1; }
        moveAxis(dx / len * knockback, 0, 0);
        moveAxis(0, 0, dz / len * knockback);
    }

    /** forward/strafe in [-1,1], vertical in [-1,1] (creative only), dt seconds. */
    public void update(double forward, double strafe, double vertical, double dt) {
        double yr = Math.toRadians(yaw);
        double fx = -Math.sin(yr), fz = -Math.cos(yr);
        double rx = Math.cos(yr), rz = -Math.sin(yr);
        double mx = fx * forward + rx * strafe;
        double mz = fz * forward + rz * strafe;
        double len = Math.hypot(mx, mz);
        if (len > 1e-6) { mx /= len; mz /= len; }

        double dx = mx * SPEED * dt;
        double dz = mz * SPEED * dt;
        double dy;
        if (creative) {
            vy = 0;
            dy = vertical * SPEED * dt; // free flight
        } else {
            vy -= GRAVITY * dt;
            dy = vy * dt;
        }

        moveAxis(dx, 0, 0);
        moveAxis(0, 0, dz);
        onGround = false;
        moveAxis(0, dy, 0);

        // fall damage: accumulate distance fallen, apply on landing
        if (!creative) {
            if (onGround) {
                double safe = 3.0;                       // free fall up to 3 blocks
                if (fallAccum > safe) health -= (fallAccum - safe) * 0.5; // 0.5 heart per extra block
                fallAccum = 0;
            } else if (dy < 0) {
                fallAccum += -dy;
            }
        }
    }
    private double fallAccum = 0;

    private void moveAxis(double dx, double dy, double dz) {
        x += dx; y += dy; z += dz;
        if (collides()) {
            if (dy > 0) { vy = 0; }
            if (dy < 0) { vy = 0; onGround = true; }
            x -= dx; y -= dy; z -= dz;
        }
    }

    private boolean collides() {
        int x0 = (int) Math.floor(x - W), x1 = (int) Math.floor(x + W);
        int y0 = (int) Math.floor(y),     y1 = (int) Math.floor(y + H);
        int z0 = (int) Math.floor(z - W), z1 = (int) Math.floor(z + W);
        // invisible walls at the world edges so you can't fall off the map
        if (borderWalls && (x0 < 0 || x1 >= world.sx || z0 < 0 || z1 >= world.sz)) return true;
        for (int bx = x0; bx <= x1; bx++)
            for (int by = y0; by <= y1; by++)
                for (int bz = z0; bz <= z1; bz++)
                    if (world.isSolid(bx, by, bz)) return true;
        return false;
    }
}
