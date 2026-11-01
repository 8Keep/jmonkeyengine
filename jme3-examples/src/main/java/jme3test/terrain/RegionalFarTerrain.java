/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

/** Smooth connected extension of the legacy synthetic region with bounded analytic far hills. */
final class RegionalFarTerrain {
    private static final double BASE_HEIGHT = 20;
    private static final double SPACING = RegionalHeightfieldSource.SPACING;
    private static final double LEGACY_MIN = -1;
    private static final double LEGACY_MAX = 2049;
    private static final double FADE_CELLS = 512;
    private static final double BLEND_MIN = LEGACY_MIN - FADE_CELLS;
    private static final double BLEND_MAX = LEGACY_MAX + FADE_CELLS;
    private static final double[] CENTER_X = {-1300, 1750, -1600, 1700};
    private static final double[] CENTER_Z = {1600, 1700, -1200, -1300};
    private static final double[] AMPLITUDE = {900, 750, 700, 600};
    private static final double[] WIDTH = {550, 650, 650, 700};
    private static final double HESSIAN_BOUND = hessianBound();

    private final long revision;
    private final float maximumHeight;

    RegionalFarTerrain(long revision) {
        if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
        this.revision = revision;
        maximumHeight = Math.max(736f, Math.nextUp((float) (BASE_HEIGHT
                + AMPLITUDE[0] + AMPLITUDE[1] + AMPLITUDE[2] + AMPLITUDE[3])));
    }

    /** Evaluate the global far source at local coordinates, including the source halo. */
    float height(int localFineX, int localFineZ) {
        int globalX = localFineX - RegionalHeightfieldSource.FAR_CELLS / 2;
        int globalZ = localFineZ - RegionalHeightfieldSource.FAR_CELLS / 2;
        double wx = legacyWeight(globalX);
        double wz = legacyWeight(globalZ);
        if (wx == 1 && wz == 1) return RegionalHeightfieldSource.legacyHeight(globalX, globalZ, revision);
        double far = farHeight(globalX, globalZ);
        double legacyWeight = wx * wz;
        if (legacyWeight == 0) return (float) far;
        float legacy = RegionalHeightfieldSource.legacyHeight(globalX, globalZ, revision);
        return (float) (legacyWeight * legacy + (1 - legacyWeight) * far);
    }

    float getMaximumHeight() {
        return maximumHeight;
    }

    /**
     * Return analytic metadata when the complete node rectangle lies outside
     * the legacy/blend footprint. The height range covers the continuous
     * Gaussian function over the rectangle. For a hill
     * {@code A*exp(-r^2/(2*sigma^2))}, the global Hessian spectral norm is at
     * most {@code A/sigma^2}; summing these gives {@code M}. On a square P1
     * cell with the canonical diagonal, Taylor's remainder and maximum
     * barycentric variance bound interpolation error by {@code M*h^2/4},
     * where {@code h=stride*spacing}. Nested coarse/fine triangles have a
     * piecewise-linear difference whose extrema occur at fine vertices, so
     * this also bounds the stable error against the canonical fine P1 surface.
     * Two global-maximum ulps cover source-float rounding for coarse strides.
     * Stride one is the canonical fine surface itself, so its stable error is
     * exactly zero here; the engine adds its separate float reconstruction
     * allowance when constructing node errors.
     */
    Bounds bounds(int localX, int localZ, int span, int stride) {
        if (span < 0 || stride <= 0 || (stride & (stride - 1)) != 0
                || localX < 0 || localZ < 0
                || (long) localX + span > RegionalHeightfieldSource.FAR_CELLS
                || (long) localZ + span > RegionalHeightfieldSource.FAR_CELLS) {
            throw new IllegalArgumentException("invalid far terrain node rectangle or stride");
        }
        boolean overlapsX = (long) localX <= localMaximumCell(BLEND_MAX)
                && (long) localX + span >= localMinimumCell(BLEND_MIN);
        boolean overlapsZ = (long) localZ <= localMaximumCell(BLEND_MAX)
                && (long) localZ + span >= localMinimumCell(BLEND_MIN);
        if (overlapsX && overlapsZ) return null;

        double worldMinX = localX * SPACING - 2048.0;
        double worldMaxX = ((long) localX + span) * SPACING - 2048.0;
        double worldMinZ = localZ * SPACING - 2048.0;
        double worldMaxZ = ((long) localZ + span) * SPACING - 2048.0;
        double minimum = BASE_HEIGHT;
        double maximum = BASE_HEIGHT;
        for (int hill = 0; hill < AMPLITUDE.length; hill++) {
            double nearX = distanceToInterval(CENTER_X[hill], worldMinX, worldMaxX);
            double nearZ = distanceToInterval(CENTER_Z[hill], worldMinZ, worldMaxZ);
            double farX = Math.max(Math.abs(CENTER_X[hill] - worldMinX), Math.abs(CENTER_X[hill] - worldMaxX));
            double farZ = Math.max(Math.abs(CENTER_Z[hill] - worldMinZ), Math.abs(CENTER_Z[hill] - worldMaxZ));
            double amplitude = AMPLITUDE[hill];
            double denominator = 2 * WIDTH[hill] * WIDTH[hill];
            minimum += amplitude * StrictMath.exp(-(farX * farX + farZ * farZ) / denominator);
            maximum += amplitude * StrictMath.exp(-(nearX * nearX + nearZ * nearZ) / denominator);
        }

        double floatAllowance = 2 * Math.ulp(maximumHeight);
        double sampleSpacing = stride * SPACING;
        double interpolationError = stride == 1 ? 0 : HESSIAN_BOUND * sampleSpacing * sampleSpacing / 4;
        float minimumFloat = Math.nextDown((float) (minimum - floatAllowance));
        float maximumFloat = Math.nextUp((float) (maximum + floatAllowance));
        double surfaceError = stride == 1 ? 0 : interpolationError + floatAllowance;
        return new Bounds(minimumFloat, maximumFloat, surfaceError);
    }

    private static long localMinimumCell(double globalFineCoordinate) {
        return (long) (globalFineCoordinate + RegionalHeightfieldSource.FAR_CELLS / 2);
    }

    private static long localMaximumCell(double globalFineCoordinate) {
        return (long) (globalFineCoordinate + RegionalHeightfieldSource.FAR_CELLS / 2);
    }

    private static double distanceToInterval(double point, double minimum, double maximum) {
        if (point < minimum) return minimum - point;
        if (point > maximum) return point - maximum;
        return 0;
    }

    private static double legacyWeight(int globalFineCoordinate) {
        if (globalFineCoordinate >= LEGACY_MIN && globalFineCoordinate <= LEGACY_MAX) return 1;
        if (globalFineCoordinate <= BLEND_MIN || globalFineCoordinate >= BLEND_MAX) return 0;
        double t = globalFineCoordinate < LEGACY_MIN
                ? (globalFineCoordinate - BLEND_MIN) / FADE_CELLS
                : (BLEND_MAX - globalFineCoordinate) / FADE_CELLS;
        t = Math.max(0, Math.min(1, t));
        return t * t * t * (10 + t * (-15 + 6 * t));
    }

    private static double farHeight(int globalFineX, int globalFineZ) {
        double worldX = globalFineX * SPACING;
        double worldZ = globalFineZ * SPACING;
        double height = BASE_HEIGHT;
        for (int hill = 0; hill < AMPLITUDE.length; hill++) {
            double dx = worldX - CENTER_X[hill];
            double dz = worldZ - CENTER_Z[hill];
            height += AMPLITUDE[hill] * StrictMath.exp(-0.5
                    * (dx * dx + dz * dz) / (WIDTH[hill] * WIDTH[hill]));
        }
        return height;
    }

    private static double hessianBound() {
        double result = 0;
        for (int i = 0; i < AMPLITUDE.length; i++) result += AMPLITUDE[i] / (WIDTH[i] * WIDTH[i]);
        return result;
    }

    static final class Bounds {
        final float minimumHeight;
        final float maximumHeight;
        final double surfaceError;

        private Bounds(float minimumHeight, float maximumHeight, double surfaceError) {
            this.minimumHeight = minimumHeight;
            this.maximumHeight = maximumHeight;
            this.surfaceError = surfaceError;
        }
    }
}
