package com.voxel;

/**
 * View-frustum culling. The six clip planes are extracted from the current
 * projection * modelview matrix, and chunk bounding boxes are tested against
 * them: anything behind the camera or off to the side is skipped before any
 * OpenGL state is touched.
 *
 * The extraction is the textbook Gribb/Hartmann one and has no dependency on
 * LWJGL, so it can be unit-tested without a GPU (see SelfTest).
 */
public class Frustum {
    /** 6 planes, each (a, b, c, d) with a*x + b*y + c*z + d >= 0 inside. */
    private final float[] planes = new float[24];

    /** Rebuild the planes from column-major 4x4 projection and modelview matrices. */
    public void set(float[] proj, float[] mv) {
        float[] m = new float[16];
        multiply(proj, mv, m);

        // row i of a column-major matrix is (m[i], m[4+i], m[8+i], m[12+i])
        float[] r0 = { m[0], m[4], m[8],  m[12] };
        float[] r1 = { m[1], m[5], m[9],  m[13] };
        float[] r2 = { m[2], m[6], m[10], m[14] };
        float[] r3 = { m[3], m[7], m[11], m[15] };

        plane(0, r3[0] + r0[0], r3[1] + r0[1], r3[2] + r0[2], r3[3] + r0[3]); // left
        plane(1, r3[0] - r0[0], r3[1] - r0[1], r3[2] - r0[2], r3[3] - r0[3]); // right
        plane(2, r3[0] + r1[0], r3[1] + r1[1], r3[2] + r1[2], r3[3] + r1[3]); // bottom
        plane(3, r3[0] - r1[0], r3[1] - r1[1], r3[2] - r1[2], r3[3] - r1[3]); // top
        plane(4, r3[0] + r2[0], r3[1] + r2[1], r3[2] + r2[2], r3[3] + r2[3]); // near
        plane(5, r3[0] - r2[0], r3[1] - r2[1], r3[2] - r2[2], r3[3] - r2[3]); // far
    }

    private void plane(int i, float a, float b, float c, float d) {
        double len = Math.sqrt(a * a + b * b + c * c);
        if (len < 1e-9) len = 1;
        planes[i * 4]     = (float) (a / len);
        planes[i * 4 + 1] = (float) (b / len);
        planes[i * 4 + 2] = (float) (c / len);
        planes[i * 4 + 3] = (float) (d / len);
    }

    /** Axis-aligned box (inclusive-ish bounds) against all six planes. */
    public boolean boxVisible(double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ) {
        for (int i = 0; i < 6; i++) {
            float a = planes[i * 4], b = planes[i * 4 + 1], c = planes[i * 4 + 2], d = planes[i * 4 + 3];
            // the corner furthest along the plane normal decides whether the box can be outside
            double x = a >= 0 ? maxX : minX;
            double y = b >= 0 ? maxY : minY;
            double z = c >= 0 ? maxZ : minZ;
            if (a * x + b * y + c * z + d < 0) return false;
        }
        return true;
    }

    /** Column-major 4x4 product out = a * b. */
    static void multiply(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 4; col++)
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) sum += a[k * 4 + row] * b[col * 4 + k];
                out[col * 4 + row] = sum;
            }
    }
}
