package com.voxel;

/** Simple 2D value noise with smooth interpolation. Returns ~[-1, 1]. */
public class Noise {
    private final long seed;
    public Noise(long seed) { this.seed = seed; }

    private double hash(int x, int z) {
        long h = x * 374761393L + z * 668265263L + seed * 1442695040888963407L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h = h ^ (h >>> 16);
        return (h & 0xffffff) / (double) 0xffffff * 2.0 - 1.0; // [-1,1]
    }

    private static double fade(double t) { return t * t * (3 - 2 * t); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    public double noise(double x, double z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double xf = x - xi, zf = z - zi;
        double v00 = hash(xi, zi), v10 = hash(xi + 1, zi);
        double v01 = hash(xi, zi + 1), v11 = hash(xi + 1, zi + 1);
        double u = fade(xf), v = fade(zf);
        return lerp(lerp(v00, v10, u), lerp(v01, v11, u), v);
    }
}
