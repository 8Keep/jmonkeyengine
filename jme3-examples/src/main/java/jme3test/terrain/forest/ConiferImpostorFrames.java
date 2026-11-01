/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDER AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain.forest;

import com.jme3.math.Vector3f;

/** CPU frame layout and allocation-free selection for the 65 conifer views. */
public final class ConiferImpostorFrames {
    public static final int AZIMUTH_COUNT = 8;
    public static final int GRID_WIDTH = 8;
    public static final int GRID_HEIGHT = 8;
    public static final int GRID_FRAME_COUNT = GRID_WIDTH * GRID_HEIGHT;
    public static final int ZENITH_INDEX = GRID_FRAME_COUNT;
    public static final int COUNT = GRID_FRAME_COUNT + 1;
    public static final int WIDTH = 128;
    public static final int HEIGHT = 256;
    public static final float CENTER_Y = 9f;
    public static final float EXTENT_X = 4f;
    public static final float EXTENT_Y = 9f;
    public static final float EXTENT_Z = 4f;

    private static final float[][] DIRECTIONS = new float[COUNT][3];
    private static final float[][] RIGHTS = new float[COUNT][3];
    private static final float[][] UPS = new float[COUNT][3];

    static {
        for (int index = 0; index < GRID_FRAME_COUNT; index++) {
            int x = index % GRID_WIDTH;
            int y = index / GRID_WIDTH;
            double u = 2.0 * x / (GRID_WIDTH - 1) - 1.0;
            double v = 2.0 * y / (GRID_HEIGHT - 1) - 1.0;
            double px = (u + v) * .5;
            double pz = (u - v) * .5;
            // This grid includes the horizon. Roundoff in the equivalent
            // diamond-edge expression must not move its vertices below it.
            double py = Math.max(0.0, 1.0 - Math.abs(px) - Math.abs(pz));
            storeDirection(index, px, py, pz);
        }
        storeDirection(ZENITH_INDEX, 0, 1, 0);
        for (int index = 0; index < COUNT; index++) {
            double dx = DIRECTIONS[index][0];
            double dy = DIRECTIONS[index][1];
            double dz = DIRECTIONS[index][2];
            // The zenith's azimuth is undefined. Use +X as a stable frame axis.
            if (index == ZENITH_INDEX || dx * dx + dz * dz < 1e-12) {
                RIGHTS[index][0] = 1;
                RIGHTS[index][1] = 0;
                RIGHTS[index][2] = 0;
            } else {
                // reference up (+Y) cross view direction
                double length = Math.sqrt(dx * dx + dz * dz);
                RIGHTS[index][0] = (float) (dz / length);
                RIGHTS[index][1] = 0;
                RIGHTS[index][2] = (float) (-dx / length);
            }
            // view direction cross right
            UPS[index][0] = (float) (dy * RIGHTS[index][2] - dz * RIGHTS[index][1]);
            UPS[index][1] = (float) (dz * RIGHTS[index][0] - dx * RIGHTS[index][2]);
            UPS[index][2] = (float) (dx * RIGHTS[index][1] - dy * RIGHTS[index][0]);
            normalize(UPS[index]);
        }
    }

    private ConiferImpostorFrames() { }

    /** Store the unit source-local direction associated with a frame index. */
    public static Vector3f direction(int index, Vector3f store) {
        checkStore(index, store);
        return store.set(DIRECTIONS[index][0], DIRECTIONS[index][1], DIRECTIONS[index][2]);
    }

    /** Store screen-right for the baked orthographic view. */
    public static Vector3f right(int index, Vector3f store) {
        checkStore(index, store);
        return store.set(RIGHTS[index][0], RIGHTS[index][1], RIGHTS[index][2]);
    }

    /** Store screen-up for the baked orthographic view. */
    public static Vector3f up(int index, Vector3f store) {
        checkStore(index, store);
        return store.set(UPS[index][0], UPS[index][1], UPS[index][2]);
    }

    /** Half-width of the orthographic bake needed to contain the source box. */
    public static float halfWidth(int index) {
        checkIndex(index);
        return (float) (Math.abs(RIGHTS[index][0]) * EXTENT_X
                + Math.abs(RIGHTS[index][1]) * EXTENT_Y
                + Math.abs(RIGHTS[index][2]) * EXTENT_Z);
    }

    /** Half-height of the orthographic bake needed to contain the source box. */
    public static float halfHeight(int index) {
        checkIndex(index);
        return (float) (Math.abs(UPS[index][0]) * EXTENT_X
                + Math.abs(UPS[index][1]) * EXTENT_Y
                + Math.abs(UPS[index][2]) * EXTENT_Z);
    }

    /**
     * Select a containing triangle on the upper-hemisphere octahedral grid.
     * Writes three layer IDs and barycentric weights into caller-owned arrays.
     * Exact zenith uses layer 64; a direction below the horizon returns false.
     * The direction is normalized internally using double precision.
     *
     * @throws IllegalArgumentException for invalid, zero, or non-finite vectors
     *     or output arrays shorter than three entries
     */
    public static boolean select(Vector3f toObserver, int[] ids3, float[] weights3) {
        if (toObserver == null || ids3 == null || weights3 == null
                || ids3.length < 3 || weights3.length < 3) {
            throw new IllegalArgumentException("direction and three-element output arrays are required");
        }
        double x = toObserver.x, y = toObserver.y, z = toObserver.z;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("observer direction must be finite");
        }
        double length = Math.sqrt(x * x + y * y + z * z);
        if (!(length > 0) || !Double.isFinite(length)) {
            throw new IllegalArgumentException("observer direction must be nonzero and finite");
        }
        x /= length;
        y /= length;
        z /= length;
        ids3[0] = ids3[1] = ids3[2] = -1;
        weights3[0] = weights3[1] = weights3[2] = 0;
        if (y < 0) return false;

        double l1 = Math.abs(x) + Math.abs(y) + Math.abs(z);
        double px = x / l1;
        double pz = z / l1;
        double u = px + pz;
        double v = px - pz;
        double gx = clamp((u + 1.0) * .5 * (GRID_WIDTH - 1), 0, GRID_WIDTH - 1);
        double gy = clamp((v + 1.0) * .5 * (GRID_HEIGHT - 1), 0, GRID_HEIGHT - 1);
        int cellX = Math.min(GRID_WIDTH - 2, (int) Math.floor(gx));
        int cellY = Math.min(GRID_HEIGHT - 2, (int) Math.floor(gy));
        float fx = (float) (gx - cellX);
        float fy = (float) (gy - cellY);
        int a = cellY * GRID_WIDTH + cellX;
        int b = a + 1;
        int c = a + GRID_WIDTH;
        int d = c + 1;

        if (cellX == 3 && cellY == 3) {
            return selectZenithFan(cellX, cellY, fx, fy, ids3, weights3);
        }
        if (cellX + cellY == GRID_WIDTH - 2) {
            if (fx + fy <= 1f) {
                ids3[0] = a; ids3[1] = b; ids3[2] = c;
                setAndNormalize(weights3, 1f - fx - fy, fx, fy);
            } else {
                ids3[0] = b; ids3[1] = d; ids3[2] = c;
                setAndNormalize(weights3, 1f - fy, fx + fy - 1f, 1f - fx);
            }
            return true;
        }
        if (fx >= fy) {
            ids3[0] = a; ids3[1] = b; ids3[2] = d;
            setAndNormalize(weights3, 1f - fx, fx - fy, fy);
        } else {
            ids3[0] = a; ids3[1] = d; ids3[2] = c;
            setAndNormalize(weights3, 1f - fy, fx, fy - fx);
        }
        return true;
    }

    private static boolean selectZenithFan(int cellX, int cellY, float fx, float fy,
            int[] ids, float[] weights) {
        double dx = fx - .5;
        double dy = fy - .5;
        int a = cellY * GRID_WIDTH + cellX;
        int b = a + 1;
        int c = a + GRID_WIDTH;
        int d = c + 1;
        int first, second;
        float x1, y1, x2, y2;
        if (Math.abs(dx) >= Math.abs(dy)) {
            if (dx >= 0) { // right edge: b -> d -> zenith
                first = b; second = d; x1 = 1; y1 = 0; x2 = 1; y2 = 1;
            } else { // left edge: c -> a -> zenith
                first = c; second = a; x1 = 0; y1 = 1; x2 = 0; y2 = 0;
            }
        } else if (dy >= 0) { // top edge: d -> c -> zenith
            first = d; second = c; x1 = 1; y1 = 1; x2 = 0; y2 = 1;
        } else { // bottom edge: a -> b -> zenith
            first = a; second = b; x1 = 0; y1 = 0; x2 = 1; y2 = 0;
        }
        ids[0] = first;
        ids[1] = second;
        ids[2] = ZENITH_INDEX;
        barycentric(fx, fy, x1, y1, x2, y2, .5f, .5f, weights);
        normalizeWeights(weights);
        return true;
    }

    private static void barycentric(float x, float y,
            float ax, float ay, float bx, float by, float cx, float cy, float[] weights) {
        float denominator = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
        float wa = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / denominator;
        float wb = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / denominator;
        weights[0] = wa;
        weights[1] = wb;
        weights[2] = 1f - wa - wb;
    }

    private static void setAndNormalize(float[] weights, float a, float b, float c) {
        weights[0] = a;
        weights[1] = b;
        weights[2] = c;
        normalizeWeights(weights);
    }

    private static void normalizeWeights(float[] weights) {
        float a = Math.max(0f, weights[0]);
        float b = Math.max(0f, weights[1]);
        float c = Math.max(0f, weights[2]);
        float sum = a + b + c;
        weights[0] = a / sum;
        weights[1] = b / sum;
        weights[2] = c / sum;
    }

    private static void storeDirection(int index, double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        DIRECTIONS[index][0] = (float) (x / length);
        DIRECTIONS[index][1] = (float) (y / length);
        DIRECTIONS[index][2] = (float) (z / length);
    }

    private static void normalize(float[] vector) {
        double length = Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
        vector[0] /= length; vector[1] /= length; vector[2] /= length;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void checkStore(int index, Vector3f store) {
        checkIndex(index);
        if (store == null) throw new IllegalArgumentException("store vector is required");
    }

    private static void checkIndex(int index) {
        if (index < 0 || index >= COUNT) throw new IllegalArgumentException("frame index outside [0,64]");
    }
}
