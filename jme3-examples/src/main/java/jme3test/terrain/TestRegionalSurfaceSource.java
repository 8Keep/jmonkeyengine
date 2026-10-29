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
        require(source.getCachedBytes() <= 9L * 1024 * 1024, "cache exceeded its bounded payload");
        require(source.getPeakCachedBytes() <=
                        (65L * 64 * 64 * 8 + 65L * 64 * 64) * Float.BYTES,
                "peak cache exceeded the conservative resident-plus-loader bound");

        RegionalSurfaceSource original = new RegionalSurfaceSource(0);
        RegionalSurfaceSource editA = new RegionalSurfaceSource(1);
        RegionalSurfaceSource editB = new RegionalSurfaceSource(3);
        HeightfieldSurfacePage.Sample a = new HeightfieldSurfacePage.Sample();
        HeightfieldSurfacePage.Sample b = new HeightfieldSurfacePage.Sample();
        HeightfieldSurfacePage.Sample c = new HeightfieldSurfacePage.Sample();
        boolean foundEdit = false;
        for (int z = 432; z < 448; z++) for (int x = 552; x < 568; x++) {
            original.sample(x, z, a);
            editA.sample(x, z, b);
            editB.sample(x, z, c);
            equalSample(b, c);
            foundEdit |= !sameSample(a, b);
        }
        require(foundEdit, "edited source revision did not change the local material");

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
        require(a.getSemanticId(mipA, ax, az) == b.getSemanticId(mipB, bx, bz), "semantic ID differed");
    }

    private static boolean sameSample(HeightfieldSurfacePage.Sample a, HeightfieldSurfacePage.Sample b) {
        return a.albedoR == b.albedoR && a.albedoG == b.albedoG && a.albedoB == b.albedoB
                && a.normalX == b.normalX && a.normalY == b.normalY && a.normalZ == b.normalZ
                && a.roughness == b.roughness && a.semanticId == b.semanticId;
    }

    private static void equalSample(HeightfieldSurfacePage.Sample a, HeightfieldSurfacePage.Sample b) {
        require(sameSample(a, b), "equal revisions produced different material values");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
