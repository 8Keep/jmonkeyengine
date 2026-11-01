/*
 * Copyright (c) 2009-2026 jMonkeyEngine
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
package jme3test.terrain;

import com.jme3.terrain.heightfield.HeightfieldSurfacePage;
import java.util.concurrent.CancellationException;

/** Small finite contract checks for the regional material source. */
final class TestRegionalSurfaceSource {
    private TestRegionalSurfaceSource() {
    }

    public static void main(String[] args) {
        HeightfieldSurfacePage.Revision rev = new HeightfieldSurfacePage.Revision(0, 0, 0);
        RegionalSurfaceSource source = new RegionalSurfaceSource(0);
        HeightfieldSurfacePage fineWest = page(rev, 0, 0, 1, source);
        HeightfieldSurfacePage fineEast = page(rev, 8, 0, 1, source);
        HeightfieldSurfacePage strideTwo = page(rev, 0, 0, 2, source);
        compareSharedTexels(fineWest, fineEast);
        compareFootprint(fineWest, strideTwo);
        require(source.getComposedSamples() > 0, "composition metric did not advance");
        require(source.getCachedBytes() <= RegionalSurfaceSource.maximumCacheBytes(1024),
                "cache exceeded its bounded payload");
        require(source.getPeakCachedBytes() <=
                        RegionalSurfaceSource.maximumCacheBytes(1024) + (64L * 64 * 9 + 64L * 64) * Float.BYTES,
                "peak cache exceeded the conservative resident-plus-loader bound");

        verifyExpandedCoverage();

        RegionalSurfaceSource original = new RegionalSurfaceSource(0);
        RegionalSurfaceSource editA = new RegionalSurfaceSource(1);
        RegionalSurfaceSource editB = new RegionalSurfaceSource(3);
        HeightfieldSurfacePage.Sample a = new HeightfieldSurfacePage.Sample();
        HeightfieldSurfacePage.Sample b = new HeightfieldSurfacePage.Sample();
        HeightfieldSurfacePage.Sample c = new HeightfieldSurfacePage.Sample();
        boolean foundEdit = false;
        boolean foundFractionalRockWeight = false;
        for (int z = 432; z < 448; z++) for (int x = 552; x < 568; x++) {
            original.sample(x, z, a);
            editA.sample(x, z, b);
            editB.sample(x, z, c);
            equalSample(b, c);
            foundEdit |= !sameSample(a, b);
            for (HeightfieldSurfacePage.Sample sample : new HeightfieldSurfacePage.Sample[]{a, b, c}) {
                require(Float.isFinite(sample.detailWeight) && sample.detailWeight >= 0 && sample.detailWeight <= 1,
                        "regional detail weight must remain normalized");
                foundFractionalRockWeight |= sample.detailWeight > 0 && sample.detailWeight < 1;
            }
        }
        require(foundEdit, "edited source revision did not change the local material");
        require(foundFractionalRockWeight, "regional source did not produce a continuous rock detail weight");

        Thread.currentThread().interrupt();
        boolean cancelled = false;
        try {
            source.sample(0, 0, new HeightfieldSurfacePage.Sample());
        } catch (CancellationException expected) {
            cancelled = true;
        }
        require(cancelled && Thread.currentThread().isInterrupted(), "sampling cleared or ignored interruption");
        Thread.interrupted();
    }


    private static void verifyExpandedCoverage() {
        RegionalSurfaceSource defaultSource = new RegionalSurfaceSource(0);
        RegionalSurfaceSource expanded = new RegionalSurfaceSource(2048, 0);
        HeightfieldSurfacePage.Sample originalSample = new HeightfieldSurfacePage.Sample();
        HeightfieldSurfacePage.Sample expandedSample = new HeightfieldSurfacePage.Sample();
        // The old outer-edge stencil was clamped at 512 metres. Compare the
        // common interior; extending the world intentionally supplies real
        // neighbors where that artificial boundary used to be.
        for (int z : new int[]{0, 511, 1020}) for (int x : new int[]{0, 511, 1020}) {
            defaultSource.sample(x, z, originalSample);
            expanded.sample(x, z, expandedSample);
            equalSample(originalSample, expandedSample);
        }

        // This stride-8 page occupies one coarse page footprint at the outer
        // edge of the 1 km region. Its gutters sample beyond the region edge,
        // exercising the clamped height halo without composing the full world.
        HeightfieldSurfacePage.Revision revision = new HeightfieldSurfacePage.Revision(0, 0, 0);
        HeightfieldSurfacePage outer = new HeightfieldSurfacePage(revision,
                1984, 1984, 8, 8, 1, expanded);
        require(outer.getOriginX() == 1984 && outer.getOriginZ() == 1984
                        && outer.getResolution() == 8 && outer.getBaseStride() == 8,
                "expanded coarse page has the expected canonical footprint");
        require(outer.getOriginX() + (long) outer.getResolution() * outer.getBaseStride() == 2048
                        && outer.getOriginZ() + (long) outer.getResolution() * outer.getBaseStride() == 2048,
                "outer coarse page reaches the 1 km region boundary");
        for (int z = -HeightfieldSurfacePage.GUTTER;
                z < outer.getInteriorSize(0) + HeightfieldSurfacePage.GUTTER; z++) {
            for (int x = -HeightfieldSurfacePage.GUTTER;
                    x < outer.getInteriorSize(0) + HeightfieldSurfacePage.GUTTER; x++) {
                for (int channel = 0; channel < 3; channel++) {
                    require(Float.isFinite(outer.getAlbedo(0, x, z, channel)),
                            "outer coarse page halo has finite albedo");
                }
            }
        }
    }

    private static HeightfieldSurfacePage page(HeightfieldSurfacePage.Revision revision,
            long ox, long oz, int stride, RegionalSurfaceSource source) {
        return new HeightfieldSurfacePage(revision, ox, oz, 8, stride, 3, source);
    }

    private static void compareSharedTexels(HeightfieldSurfacePage west, HeightfieldSurfacePage east) {
        for (int mip = 0; mip < 3; mip++) {
            for (int z = -HeightfieldSurfacePage.GUTTER;
                    z < west.getInteriorSize(mip) + HeightfieldSurfacePage.GUTTER; z++) {
                for (int eastX = -HeightfieldSurfacePage.GUTTER; eastX <= 1; eastX++) {
                    int westX = west.getInteriorSize(mip) + eastX;
                    equalTexel(west, mip, westX, z, east, mip, eastX, z);
                }
            }
        }
    }

    private static void compareFootprint(HeightfieldSurfacePage strideOne,
            HeightfieldSurfacePage strideTwo) {
        for (int mip = 0; mip < 2; mip++) {
            int side = strideOne.getInteriorSize(mip + 1);
            for (int z = -HeightfieldSurfacePage.GUTTER; z < side + HeightfieldSurfacePage.GUTTER; z++) {
                for (int x = -HeightfieldSurfacePage.GUTTER; x < side + HeightfieldSurfacePage.GUTTER; x++) {
                    equalTexel(strideOne, mip + 1, x, z, strideTwo, mip, x, z);
                }
            }
        }
    }

    private static void equalTexel(HeightfieldSurfacePage a, int mipA, int ax, int az,
            HeightfieldSurfacePage b, int mipB, int bx, int bz) {
        for (int c = 0; c < 3; c++) {
            require(a.getAlbedo(mipA, ax, az, c) == b.getAlbedo(mipB, bx, bz, c), "shared albedo differed");
            require(a.getNormalMoment(mipA, ax, az, c) == b.getNormalMoment(mipB, bx, bz, c), "shared normal differed");
        }
        require(a.getRoughnessSquared(mipA, ax, az) == b.getRoughnessSquared(mipB, bx, bz), "roughness differed");
        require(a.getDetailWeight(mipA, ax, az) == b.getDetailWeight(mipB, bx, bz), "detail weight differed");
        require(a.getSemanticId(mipA, ax, az) == b.getSemanticId(mipB, bx, bz), "semantic ID differed");
    }

    private static boolean sameSample(HeightfieldSurfacePage.Sample a, HeightfieldSurfacePage.Sample b) {
        return a.albedoR == b.albedoR && a.albedoG == b.albedoG && a.albedoB == b.albedoB
                && a.normalX == b.normalX && a.normalY == b.normalY && a.normalZ == b.normalZ
                && a.roughness == b.roughness && a.semanticId == b.semanticId
                && a.detailWeight == b.detailWeight;
    }

    private static void equalSample(HeightfieldSurfacePage.Sample a, HeightfieldSurfacePage.Sample b) {
        require(sameSample(a, b), "equal revisions produced different material values");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
