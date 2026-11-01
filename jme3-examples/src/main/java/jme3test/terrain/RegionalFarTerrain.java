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
    private static final Ridge[] RIDGES = {
        new Ridge(-1450, 1450, 420, 460, 155, 25),
        new Ridge(1450, 1550, 390, 420, 145, -24),
        new Ridge(-1450, -1350, 400, 440, 160, -31),
        new Ridge(1500, -1400, 390, 455, 150, 29),
        new Ridge(-470, 1660, 280, 345, 125, -19),
        new Ridge(810, -1690, 275, 360, 135, 34),
        new Ridge(-1730, 80, 250, 310, 115, 53),
        new Ridge(1750, 30, 255, 330, 120, -48),
        new Ridge(-1110, 950, 145, 175, 108, -8),
        new Ridge(-1780, 1740, 135, 155, 105, -17),
        new Ridge(1120, 1240, 150, 180, 112, 21),
        new Ridge(1900, 1660, 140, 165, 102, -33),
        new Ridge(-1190, -910, 150, 185, 110, 28),
        new Ridge(-1760, -1620, 140, 160, 100, -41),
        new Ridge(1120, -1120, 150, 178, 108, -26),
        new Ridge(1900, -1640, 145, 172, 106, 37),
        new Ridge(-660, 1120, 95, 130, 82, 16),
        new Ridge(670, 1670, 105, 142, 88, -38),
        new Ridge(-720, -1640, 100, 138, 86, -12),
        new Ridge(690, -910, 105, 145, 92, 44)
    };

    private final long revision;
    private final float maximumHeight;

    RegionalFarTerrain(long revision) {
        if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
        this.revision = revision;
        double ceiling = BASE_HEIGHT;
        for (Ridge ridge : RIDGES) ceiling += ridge.amplitude;
        maximumHeight = Math.max(736f, Math.nextUp((float) ceiling));
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
     * rotated Gaussian functions over the rectangle. For a component
     * {@code A*exp(-q/2)}, its Hessian spectral norm is bounded by
     * {@code A/minSigma^2 * exp(-q/2)*max(1,abs(q-1))}. A conservative interval
     * for q on the rectangle gives a local bound; summing components gives M.
     * On a square P1
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
        double hessianBound = 0;
        for (Ridge ridge : RIDGES) {
            double dx0 = worldMinX - ridge.centerX, dx1 = worldMaxX - ridge.centerX;
            double dz0 = worldMinZ - ridge.centerZ, dz1 = worldMaxZ - ridge.centerZ;
            double u00Low = ridge.uLower(dx0, dz0), u00High = ridge.uUpper(dx0, dz0);
            double u10Low = ridge.uLower(dx1, dz0), u10High = ridge.uUpper(dx1, dz0);
            double u01Low = ridge.uLower(dx0, dz1), u01High = ridge.uUpper(dx0, dz1);
            double u11Low = ridge.uLower(dx1, dz1), u11High = ridge.uUpper(dx1, dz1);
            double v00Low = ridge.vLower(dx0, dz0), v00High = ridge.vUpper(dx0, dz0);
            double v10Low = ridge.vLower(dx1, dz0), v10High = ridge.vUpper(dx1, dz0);
            double v01Low = ridge.vLower(dx0, dz1), v01High = ridge.vUpper(dx0, dz1);
            double v11Low = ridge.vLower(dx1, dz1), v11High = ridge.vUpper(dx1, dz1);
            double uMin = Math.min(Math.min(u00Low, u10Low), Math.min(u01Low, u11Low));
            double uMax = Math.max(Math.max(u00High, u10High), Math.max(u01High, u11High));
            double vMin = Math.min(Math.min(v00Low, v10Low), Math.min(v01Low, v11Low));
            double vMax = Math.max(Math.max(v00High, v10High), Math.max(v01High, v11High));
            double nearU = nonnegativePadDown(distanceToInterval(0, uMin, uMax), 2);
            double nearV = nonnegativePadDown(distanceToInterval(0, vMin, vMax), 2);
            double uSquared = nonnegativePadDown(nearU * nearU, 2);
            double vSquared = nonnegativePadDown(nearV * nearV, 2);
            double qMin = nonnegativePadDown(uSquared + vSquared, 4);
            double qMax = Math.max(Math.max(ridge.qUpper(dx0, dz0), ridge.qUpper(dx1, dz0)),
                    Math.max(ridge.qUpper(dx0, dz1), ridge.qUpper(dx1, dz1)));
            qMax = padUp(qMax, 2);
            minimum = nonnegativePadDown(minimum
                    + nonnegativePadDown(ridge.amplitude * padDown(StrictMath.exp(-0.5 * qMax), 2), 2), 2);
            maximum = padUp(maximum
                    + padUp(ridge.amplitude * padUp(StrictMath.exp(-0.5 * qMin), 2), 2), 2);
            hessianBound = padUp(hessianBound + ridge.localHessianBound(qMin, qMax), 4);
        }

        double floatAllowance = 2 * Math.ulp(maximumHeight);
        double sampleSpacing = stride * SPACING;
        double interpolationError = stride == 1 ? 0 : hessianBound * sampleSpacing * sampleSpacing / 4;
        float minimumFloat = Math.nextDown((float) (minimum - floatAllowance));
        float maximumFloat = Math.nextUp((float) (maximum + floatAllowance));
        double surfaceError = stride == 1 ? 0 : padUp(interpolationError + floatAllowance, 4);
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

    private static double padDown(double value, int ulps) {
        for (int i = 0; i < ulps; i++) value = Math.nextDown(value);
        return value;
    }

    private static double padUp(double value, int ulps) {
        for (int i = 0; i < ulps; i++) value = Math.nextUp(value);
        return value;
    }

    private static double nonnegativePadDown(double value, int ulps) {
        return Math.max(0, padDown(value, ulps));
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
        for (Ridge ridge : RIDGES) height += ridge.height(worldX, worldZ);
        return height;
    }

    private static double hessianFactor(double q) {
        double radial = Math.max(1, padUp(Math.abs(q - 1), 1));
        return padUp(padUp(StrictMath.exp(-0.5 * q), 2) * radial, 2);
    }

    private static final class Ridge {
        final double centerX, centerZ, amplitude;
        final double ux, uz, vx, vz;
        final double maximumTransformEigenvalue;

        Ridge(double centerX, double centerZ, double amplitude,
                double alongWidth, double acrossWidth, double angleDegrees) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.amplitude = amplitude;
            double angle = StrictMath.toRadians(angleDegrees);
            double cosine = StrictMath.cos(angle), sine = StrictMath.sin(angle);
            ux = cosine / alongWidth;
            uz = sine / alongWidth;
            vx = -sine / acrossWidth;
            vz = cosine / acrossWidth;
            // Bound the actual stored transform, including coefficient rounding,
            // rather than assuming its floating-point rows remain orthogonal.
            // lambda_max is monotone in each diagonal and in |off-diagonal|.
            double qxx = padUp(padUp(ux * ux, 1) + padUp(vx * vx, 1), 1);
            double qzz = padUp(padUp(uz * uz, 1) + padUp(vz * vz, 1), 1);
            double cross = Math.max(Math.abs(linearLower(ux, uz, vx, vz)),
                    Math.abs(linearUpper(ux, uz, vx, vz)));
            double diagonalDifference = padUp(Math.abs(qxx - qzz), 1);
            double discriminant = padUp(StrictMath.hypot(diagonalDifference, padUp(2 * cross, 1)), 2);
            maximumTransformEigenvalue = 0.5 * padUp(padUp(qxx + qzz, 1) + discriminant, 1);
        }

        double uLower(double dx, double dz) { return linearLower(ux, dx, uz, dz); }
        double uUpper(double dx, double dz) { return linearUpper(ux, dx, uz, dz); }
        double vLower(double dx, double dz) { return linearLower(vx, dx, vz, dz); }
        double vUpper(double dx, double dz) { return linearUpper(vx, dx, vz, dz); }
        double q(double dx, double dz) {
            double u = ux * dx + uz * dz, v = vx * dx + vz * dz;
            return u * u + v * v;
        }
        double qUpper(double dx, double dz) {
            double uLow = uLower(dx, dz), uHigh = uUpper(dx, dz);
            double vLow = vLower(dx, dz), vHigh = vUpper(dx, dz);
            double maxU = Math.max(Math.abs(uLow), Math.abs(uHigh));
            double maxV = Math.max(Math.abs(vLow), Math.abs(vHigh));
            double uSquared = padUp(maxU * maxU, 2);
            double vSquared = padUp(maxV * maxV, 2);
            return padUp(uSquared + vSquared, 2);
        }

        private static double linearLower(double a, double x, double b, double y) {
            return Math.nextDown(Math.nextDown(a * x) + Math.nextDown(b * y));
        }

        private static double linearUpper(double a, double x, double b, double y) {
            return Math.nextUp(Math.nextUp(a * x) + Math.nextUp(b * y));
        }
        double height(double x, double z) {
            return amplitude * StrictMath.exp(-0.5 * q(x - centerX, z - centerZ));
        }

        double localHessianBound(double qMin, double qMax) {
            double factor = Math.max(hessianFactor(qMin), hessianFactor(qMax));
            if (qMin <= 3 && qMax >= 3) factor = Math.max(factor, hessianFactor(3));
            return padUp(padUp(amplitude * maximumTransformEigenvalue, 1) * factor, 1);
        }
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
