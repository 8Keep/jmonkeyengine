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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldReconstruction;

/** Finite headless checks for the connected synthetic four-kilometre source. */
public final class TestRegionalFarTerrain {
    private static final int LEGACY_OFFSET = RegionalHeightfieldSource.FAR_CELLS / 2;
    private static final int PATCH_CELLS = 64;

    private TestRegionalFarTerrain() { }

    public static void main(String[] args) {
        verifyLegacyCompatibility();
        verifyPagesAndGradients();
        verifyBlendBoundaries();
        verifyFarBounds();
        verifyBoundsAndBootstrap();
        verifyBoundedCache();
        System.out.println("PASS regional far terrain: cells=" + RegionalHeightfieldSource.FAR_CELLS
                + " spacing=" + RegionalHeightfieldSource.SPACING
                + " origin=" + new RegionalHeightfieldSource(RegionalHeightfieldSource.FAR_CELLS, 0).getWorldOrigin()
                + " cacheLimitBytes=" + RegionalHeightfieldSource.maximumCacheBytes(RegionalHeightfieldSource.FAR_CELLS));
    }

    private static void verifyLegacyCompatibility() {
        int[] coordinates = {-1, 0, 1, 127, 1024, 2048, 2049};
        for (long revision : new long[]{0, 1}) {
            RegionalHeightfieldSource far = new RegionalHeightfieldSource(RegionalHeightfieldSource.FAR_CELLS, revision);
            RegionalHeightfieldSource old1024 = new RegionalHeightfieldSource(1024, revision);
            RegionalHeightfieldSource old2048 = new RegionalHeightfieldSource(2048, revision);
            for (int z : coordinates) for (int x : coordinates) {
                int farX = x + LEGACY_OFFSET, farZ = z + LEGACY_OFFSET;
                float expected = RegionalHeightfieldSource.legacyHeight(x, z, revision);
                require(far.getHeight(farX, farZ) == expected,
                        "far source changed an original height at " + x + "," + z + " rev=" + revision);
                if (x >= -1 && x <= 1025 && z >= -1 && z <= 1025) {
                    require(old1024.getHeight(x, z) == expected, "1024 source changed legacy sampling");
                }
                require(old2048.getHeight(x, z) == expected, "2048 source changed legacy sampling");
            }
            require(far.getFarTerrain() != null && old1024.getFarTerrain() == null
                    && old2048.getFarTerrain() == null, "far helper ownership does not match source size");
        }
    }

    private static void verifyPagesAndGradients() {
        for (long revision : new long[]{0, 1}) {
            RegionalHeightfieldSource far = new RegionalHeightfieldSource(RegionalHeightfieldSource.FAR_CELLS, revision);
            RegionalHeightfieldSource old = new RegionalHeightfieldSource(2048, revision);
            HeightfieldPage farLegacy = HeightfieldPage.fromSource(far, LEGACY_OFFSET, LEGACY_OFFSET,
                    PATCH_CELLS, 1, true);
            HeightfieldPage oldLegacy = HeightfieldPage.fromSource(old, 0, 0, PATCH_CELLS, 1, true);
            comparePages(farLegacy, oldLegacy, "reused legacy patch");

            // One page centred on a far hill verifies the original far payload
            // and canonical gradients are extracted consistently through halos.
            int hillX = 1496, hillZ = 7296;
            HeightfieldPage hill = HeightfieldPage.fromSource(far, hillX, hillZ, PATCH_CELLS, 1, true);
            for (int z = -1; z <= PATCH_CELLS + 1; z++) {
                for (int x = -1; x <= PATCH_CELLS + 1; x++) {
                    int gx = hillX + x, gz = hillZ + z;
                    require(hill.getHeight(x, z) == far.getHeight(gx, gz), "far hill page sample mismatch");
                    float dx = far.getHeight(gx - 1, gz) * .5f - far.getHeight(gx + 1, gz) * .5f;
                    float dz = far.getHeight(gx, gz - 1) * .5f - far.getHeight(gx, gz + 1) * .5f;
                    require(hill.getSourceGradientX(x, z) == dx && hill.getSourceGradientZ(x, z) == dz,
                            "far hill canonical gradient mismatch");
                }
            }
        }
    }

    private static void comparePages(HeightfieldPage actual, HeightfieldPage expected, String label) {
        for (int z = -1; z <= PATCH_CELLS + 1; z++) for (int x = -1; x <= PATCH_CELLS + 1; x++) {
            require(actual.getHeight(x, z) == expected.getHeight(x, z), label + " height differs");
            // Old finite sources clamp the gradient's extra stencil samples to
            // their one-fine-sample halo. The connected far source continues
            // beyond that old source boundary. Heights, including page halos,
            // remain identical; compare each gradient only where its complete
            // one-sample stencil lies in the old source's defined domain.
            if (x > -1) {
                require(actual.getSourceGradientX(x, z) == expected.getSourceGradientX(x, z),
                        label + " canonical X gradient differs inside common source domain");
            }
            if (z > -1) {
                require(actual.getSourceGradientZ(x, z) == expected.getSourceGradientZ(x, z),
                        label + " canonical Z gradient differs inside common source domain");
            }
        }
    }

    private static void verifyBlendBoundaries() {
        for (long revision : new long[]{0, 1}) {
            RegionalHeightfieldSource source = new RegionalHeightfieldSource(RegionalHeightfieldSource.FAR_CELLS, revision);
            int[] edges = {-513, -1, 2049, 2561};
            for (int edge : edges) {
                for (int delta = -2; delta <= 2; delta++) {
                    int globalX = edge + delta;
                    int localX = globalX + LEGACY_OFFSET;
                    int localZ = 1024 + LEGACY_OFFSET;
                    float height = source.getHeight(localX, localZ);
                    require(Float.isFinite(height) && height >= 0 && height <= source.getFarTerrain().getMaximumHeight(),
                            "non-finite/out-of-range sample near fade edge " + edge);
                    if (globalX >= -1 && globalX <= 2049) {
                        require(height == RegionalHeightfieldSource.legacyHeight(globalX, 1024, revision),
                                "legacy plateau changed near fade boundary");
                    }
                    if (Math.abs(globalX - edge) <= 1) {
                        float neighbor = source.getHeight(localX + 1, localZ);
                        require(Math.abs(height - neighbor) < 100,
                                "discontinuous height step at fade edge " + edge);
                    }
                }
            }
        }
    }

    private static void verifyFarBounds() {
        for (long revision : new long[]{0, 1}) {
            RegionalHeightfieldSource source = new RegionalHeightfieldSource(RegionalHeightfieldSource.FAR_CELLS, revision);
            float maximum = source.getFarTerrain().getMaximumHeight();
            for (int z = 0; z <= RegionalHeightfieldSource.FAR_CELLS; z += 512) {
                for (int x = 0; x <= RegionalHeightfieldSource.FAR_CELLS; x += 512) {
                    float height = source.getHeight(x, z);
                    require(Float.isFinite(height) && height > 0 && height <= maximum,
                            "coarse far sample escaped positive global height range");
                }
            }
            require(maximum > 736, "far ceiling does not include synthetic peaks");
        }
    }

    private static void verifyBoundsAndBootstrap() {
        RegionalHeightfieldSource source = new RegionalHeightfieldSource(RegionalHeightfieldSource.FAR_CELLS, 1);
        RegionalFarTerrain far = source.getFarTerrain();
        require(far.bounds(4096, 4096, 64, 8) == null, "legacy/blend node received analytic Gaussian bounds");
        int originX = 1400, originZ = 7200, span = 64, stride = 8;
        RegionalFarTerrain.Bounds bound = far.bounds(originX, originZ, span, stride);
        require(bound != null && bound.minimumHeight <= bound.maximumHeight
                && Double.isFinite(bound.surfaceError) && bound.surfaceError > 0,
                "pure-far analytic bounds are invalid");
        for (int z = originZ; z <= originZ + span; z++) {
            for (int x = originX; x <= originX + span; x++) {
                float height = source.getHeight(x, z);
                require(height >= bound.minimumHeight && height <= bound.maximumHeight,
                        "analytic bounds do not contain a canonical fine sample");
                if (x % stride == 0 && z % stride == 0) {
                    double coarse = HeightfieldReconstruction.height(source, x, z, stride);
                    require(Math.abs(height - coarse) <= bound.surfaceError,
                            "analytic stable surface error underestimates measured P1 deviation");
                }
            }
        }
        double measured = HeightfieldReconstruction.maximumError(source, originX, originZ, span, stride);
        require(measured <= bound.surfaceError, "analytic error bound underestimates region maximum");

        HeightfieldHierarchy bootstrap = source.bootstrapHierarchy(PATCH_CELLS);
        require(!bootstrap.hasCanonicalPage() && bootstrap.getNodes().size() == 21845,
                "far bootstrap allocated dense data or has unexpected topology");
        require(bootstrap.getRoot().getBound().getYExtent() > 0,
                "far bootstrap bounds are empty");
    }

    private static void verifyBoundedCache() {
        RegionalHeightfieldSource source = new RegionalHeightfieldSource(RegionalHeightfieldSource.FAR_CELLS, 0);
        for (int tile = 0; tile <= 128; tile++) source.getHeight(tile * 64, 0);
        long limit = RegionalHeightfieldSource.maximumCacheBytes(RegionalHeightfieldSource.FAR_CELLS);
        require(source.getCachedFloatBytes() <= limit, "far source exceeded its 128-tile cache bound");
        require(source.getPeakTransientFloatBytes() <= limit + 64L * 64 * Float.BYTES,
                "far tile loader exceeded one transient tile");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
