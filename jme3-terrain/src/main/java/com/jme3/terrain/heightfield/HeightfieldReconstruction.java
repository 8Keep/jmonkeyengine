/*
 * Copyright (c) 2009-2024 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.terrain.heightfield;

import com.jme3.math.Vector3f;

/** CPU reconstruction of the regular heightfield grid's triangle surface. */
public final class HeightfieldReconstruction {
    private HeightfieldReconstruction() {
    }

    /**
     * Interpolate the page surface at fine-lattice coordinates using the
     * {@code a,d,b} / {@code a,c,d} diagonal used by {@link HeightfieldGrid}.
     * Coordinates may be fractional; the diagonal in each stride-sized cell
     * is {@code x == z} in that cell's local coordinates.
     */
    public static double height(HeightfieldPage page, double x, double z, int stride) {
        requireStride(page, stride);
        if (!Double.isFinite(x) || !Double.isFinite(z)
                || x < 0 || z < 0 || x > page.getCells() || z > page.getCells()) {
            throw new IllegalArgumentException("coordinates must be finite and inside the page");
        }

        int cells = page.getCells();
        int ix = x == cells ? cells - stride : (int) Math.floor(x / stride) * stride;
        int iz = z == cells ? cells - stride : (int) Math.floor(z / stride) * stride;
        double u = (x - ix) / stride;
        double v = (z - iz) / stride;
        double a = page.getHeight(ix, iz);
        double b = page.getHeight(ix + stride, iz);
        double c = page.getHeight(ix, iz + stride);
        double d = page.getHeight(ix + stride, iz + stride);

        // The first grid triangle is a,d,b (u >= v); the second is a,c,d.
        if (u >= v) {
            return a + u * (b - a) + v * (d - b);
        }
        return a + u * (d - c) + v * (c - a);
    }

    /**
     * Maximum absolute deviation of a stride surface from the fine surface
     * over every fine lattice vertex in an aligned rectangular region.
     */
    public static double maximumError(HeightfieldPage page, int originX, int originZ,
            int span, int stride) {
        requireStride(page, stride);
        int cells = page.getCells();
        if (span <= 0 || originX < 0 || originZ < 0
                || originX > cells || originZ > cells
                || span > cells - originX || span > cells - originZ
                || originX % stride != 0 || originZ % stride != 0
                || span % stride != 0) {
            throw new IllegalArgumentException("region must be positive, in-page, and stride-aligned");
        }

        double maximum = 0;
        for (int z = originZ; z <= originZ + span; z++) {
            for (int x = originX; x <= originX + span; x++) {
                double reference = height(page, x, z, 1);
                double candidate = height(page, x, z, stride);
                maximum = Math.max(maximum, Math.abs(reference - candidate));
            }
        }
        return maximum;
    }

    /**
     * Reconstruct one stride-grid vertex, blending its fine position toward
     * its lower-left parent-grid vertex. The returned x, y, and z are in the
     * page's local units; {@code store} is reused when non-null.
     */
    public static Vector3f morphVertex(HeightfieldPage page, int x, int z,
            int stride, float alpha, Vector3f store) {
        requireStride(page, stride);
        int cells = page.getCells();
        if (x < 0 || z < 0 || x > cells || z > cells
                || x % stride != 0 || z % stride != 0) {
            throw new IllegalArgumentException("vertex must lie on the in-page stride grid");
        }
        if (!Float.isFinite(alpha) || alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("alpha must be finite and in [0,1]");
        }

        int parentStride = stride * 2;
        int parentX = (x / parentStride) * parentStride;
        int parentZ = (z / parentStride) * parentStride;
        float spacing = page.getSampleSpacing();
        float originalX = x * spacing;
        float originalY = page.getHeight(x, z);
        float originalZ = z * spacing;
        float collapsedX = parentX * spacing;
        float collapsedY = page.getHeight(parentX, parentZ);
        float collapsedZ = parentZ * spacing;

        Vector3f result = store == null ? new Vector3f() : store;
        result.set(interpolateFinite(originalX, collapsedX, alpha),
                interpolateFinite(originalY, collapsedY, alpha),
                interpolateFinite(originalZ, collapsedZ, alpha));
        return result;
    }

    /**
     * Float interpolation that preserves exact endpoints and stays inside the
     * finite endpoint range despite rounded weights or a product-sum overflow.
     * The finite inputs and {@code alpha} in [0,1] make the real-valued result
     * a convex combination; clamping only corrects float-arithmetic excursions.
     */
    private static float interpolateFinite(float first, float second, float alpha) {
        if (alpha == 0f) {
            return first;
        }
        if (alpha == 1f) {
            return second;
        }
        if (first == second) {
            return first;
        }

        float mixed = first * (1f - alpha) + second * alpha;
        float low = Math.min(first, second);
        float high = Math.max(first, second);
        if (mixed < low) {
            return low;
        }
        if (mixed > high) {
            return high;
        }
        return mixed;
    }

    private static void requireStride(HeightfieldPage page, int stride) {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        int cells = page.getCells();
        if (stride <= 0 || (stride & (stride - 1)) != 0 || stride > cells || cells % stride != 0) {
            throw new IllegalArgumentException("stride must be a power of two dividing page cells");
        }
    }
}
