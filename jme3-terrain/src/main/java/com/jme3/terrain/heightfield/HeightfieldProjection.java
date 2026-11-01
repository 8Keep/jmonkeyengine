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

import com.jme3.bounding.BoundingBox;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;

/**
 * Conservative screen-space error estimates for heightfield displacement.
 *
 * <p>The estimate bounds the pixel displacement of any two points no farther
 * apart than {@code worldError}, provided the connecting segment stays within
 * {@code worldBounds} expanded by that distance. It uses the camera's current
 * view-projection matrix and viewport, so perspective, orthographic, asymmetric
 * projections, and split viewports use the same contract. The supplied error
 * must cover the actual world-space displacement being assessed.
 *
 * <p>The Jacobian of the perspective divide is bounded over the expanded box.
 * A box wholly outside any homogeneous clip plane returns zero. A box that
 * reaches or crosses the near clip plane returns positive infinity because a
 * finite local projection bound cannot be guaranteed there. Arithmetic uses
 * double precision and an outward safety factor; as with other floating-point
 * bounds, callers should retain a small quality margin near decision thresholds.
 */
public final class HeightfieldProjection {
    private static final double SAFETY_FACTOR = 1.000000000001;

    private HeightfieldProjection() {
    }

    /**
     * Estimate an upper bound, in physical viewport pixels, for the projected
     * displacement of a world-space error vector.
     *
     * @param camera camera whose current view-projection matrix is used
     * @param worldBounds finite world-space AABB containing both endpoints
     * @param worldError non-negative displacement length, or positive infinity
     *                   when the error is unknown
     * @return a conservative pixel bound, positive infinity when unbounded or
     *         unknown, and zero when the expanded box is wholly outside the frustum
     * @throws IllegalArgumentException if an argument is null or invalid
     */
    public static double pixels(Camera camera, BoundingBox worldBounds, double worldError) {
        if (camera == null || worldBounds == null) {
            throw new IllegalArgumentException("camera and worldBounds must be non-null");
        }
        if (Double.isNaN(worldError) || worldError < 0) {
            throw new IllegalArgumentException("worldError must be non-negative and not NaN");
        }

        Vector3f center = worldBounds.getCenter();
        double cx = center.x, cy = center.y, cz = center.z;
        double ex = worldBounds.getXExtent(), ey = worldBounds.getYExtent(), ez = worldBounds.getZExtent();
        if (!Double.isFinite(cx) || !Double.isFinite(ex) || ex < 0
                || !Double.isFinite(cy) || !Double.isFinite(ey) || ey < 0
                || !Double.isFinite(cz) || !Double.isFinite(ez) || ez < 0) {
            throw new IllegalArgumentException("worldBounds must have finite center and extents");
        }

        Matrix4f matrix = camera.getViewProjectionMatrix();
        double[][] rows = rows(matrix);
        if (camera.getWidth() <= 0 || camera.getHeight() <= 0
                || !Float.isFinite(camera.getViewPortLeft())
                || !Float.isFinite(camera.getViewPortRight())
                || !Float.isFinite(camera.getViewPortBottom())
                || !Float.isFinite(camera.getViewPortTop())
                || !(camera.getViewPortRight() > camera.getViewPortLeft())
                || !(camera.getViewPortTop() > camera.getViewPortBottom())) {
            throw new IllegalArgumentException("camera viewport dimensions must be finite and positive");
        }
        double pixelX = camera.getWidth() * ((double) camera.getViewPortRight()
                - camera.getViewPortLeft()) * 0.5;
        double pixelY = camera.getHeight() * ((double) camera.getViewPortTop()
                - camera.getViewPortBottom()) * 0.5;
        if (!Double.isFinite(pixelX) || !Double.isFinite(pixelY) || pixelX <= 0 || pixelY <= 0) {
            throw new IllegalArgumentException("camera viewport dimensions must be finite and positive");
        }
        if (worldError == Double.POSITIVE_INFINITY) {
            return Double.POSITIVE_INFINITY;
        }
        if (worldError == 0) {
            return 0;
        }

        double radiusX = ex + worldError, radiusY = ey + worldError, radiusZ = ez + worldError;
        double[] low = {cx - radiusX, cy - radiusY, cz - radiusZ};
        double[] high = {cx + radiusX, cy + radiusY, cz + radiusZ};
        for (int i = 0; i < 3; i++) {
            if (!Double.isFinite(low[i]) || !Double.isFinite(high[i])) {
                return Double.POSITIVE_INFINITY;
            }
        }

        // Homogeneous OpenGL clip inequalities: x+w, w-x, y+w, w-y,
        // z+w (near), and w-z (far). Interval tests are outward-rounded by
        // a small relative allowance before using the result for culling.
        // One call-local output record is reused for all interval queries;
        // no shared scratch can be overwritten by a nested or concurrent call.
        Interval range = new Interval();
        double nearMin = 0;
        for (int plane = 0; plane < 6; plane++) {
            double[] row = rows[plane / 2];
            boolean positive = (plane & 1) == 0;
            double x = positive ? row[0] + rows[3][0] : rows[3][0] - row[0];
            double y = positive ? row[1] + rows[3][1] : rows[3][1] - row[1];
            double z = positive ? row[2] + rows[3][2] : rows[3][2] - row[2];
            double w = positive ? row[3] + rows[3][3] : rows[3][3] - row[3];
            if (!interval(x, y, z, w, low, high, range)) {
                return Double.POSITIVE_INFINITY;
            }
            double tolerance = 1e-12 * Math.max(1, Math.max(Math.abs(range.min), Math.abs(range.max)));
            if (range.max < -tolerance) {
                return 0;
            }
            if (plane == 4) nearMin = range.min;
        }
        // Preserve the original precedence: all six cull tests finish before
        // rejecting a box that reaches the near plane.
        if (nearMin <= 0) {
            return Double.POSITIVE_INFINITY;
        }

        if (!interval(rows[3], low, high, range) || range.min <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        double denominator = range.min;
        if (!interval(rows[0], low, high, range)) {
            return Double.POSITIVE_INFINITY;
        }
        double maxAbsX = Math.max(Math.abs(range.min), Math.abs(range.max));
        if (!interval(rows[1], low, high, range)) {
            return Double.POSITIVE_INFINITY;
        }
        double maxAbsY = Math.max(Math.abs(range.min), Math.abs(range.max));
        double denominatorSquared = denominator * denominator;
        if (!Double.isFinite(denominatorSquared) || denominatorSquared == 0) {
            return Double.POSITIVE_INFINITY;
        }

        // Bound each quotient derivative |(rowN*w - clipN*rowW)/w^2|
        // by |rowN|/wMin + max|clipN|*|rowW|/wMin^2, then use its
        // Frobenius norm as an upper bound on the Jacobian operator norm.
        double sumSquares = 0;
        for (int axis = 0; axis < 3; axis++) {
            double dx = pixelX * (Math.abs(rows[0][axis]) / denominator
                    + maxAbsX * Math.abs(rows[3][axis]) / denominatorSquared);
            double dy = pixelY * (Math.abs(rows[1][axis]) / denominator
                    + maxAbsY * Math.abs(rows[3][axis]) / denominatorSquared);
            sumSquares += dx * dx + dy * dy;
        }
        double result = Math.sqrt(sumSquares) * worldError * SAFETY_FACTOR;
        return Double.isFinite(result) ? Math.nextUp(result) : Double.POSITIVE_INFINITY;
    }

    private static double[][] rows(Matrix4f m) {
        if (m == null) {
            throw new IllegalArgumentException("camera view-projection matrix must be non-null");
        }
        double[][] result = {
            {m.m00, m.m01, m.m02, m.m03},
            {m.m10, m.m11, m.m12, m.m13},
            {m.m20, m.m21, m.m22, m.m23},
            {m.m30, m.m31, m.m32, m.m33}
        };
        for (double[] row : result) {
            for (double value : row) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("camera view-projection matrix must be finite");
                }
            }
        }
        return result;
    }

    private static boolean interval(double[] row, double[] low, double[] high, Interval store) {
        return interval(row[0], row[1], row[2], row[3], low, high, store);
    }

    private static boolean interval(double x, double y, double z, double w,
            double[] low, double[] high, Interval store) {
        double min = w;
        double max = w;
        double magnitude = Math.abs(w);
        for (int i = 0; i < 3; i++) {
            double coefficient = i == 0 ? x : i == 1 ? y : z;
            double a = coefficient * low[i];
            double b = coefficient * high[i];
            min += Math.min(a, b);
            max += Math.max(a, b);
            magnitude += Math.max(Math.abs(a), Math.abs(b));
        }
        if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(magnitude)) {
            return false;
        }
        double pad = 1e-12 * Math.max(1, magnitude);
        store.min = min - pad;
        store.max = max + pad;
        return true;
    }

    private static final class Interval {
        double min;
        double max;
    }
}
