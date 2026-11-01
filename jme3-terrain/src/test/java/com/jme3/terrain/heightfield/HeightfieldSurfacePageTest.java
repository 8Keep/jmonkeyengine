/*
 * Copyright (c) 2009-2024 jMonkeyEngine
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
 *
 * Neither the name of jMonkeyEngine nor the names of its contributors may be
 * used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
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
package com.jme3.terrain.heightfield;

import com.jme3.math.FastMath;
import com.jme3.texture.Image;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeightfieldSurfacePageTest {
    private static final HeightfieldSurfacePage.Revision REVISION =
            new HeightfieldSurfacePage.Revision(3, 5, 7);

    private static HeightfieldSurfacePage page(long x, long z, int resolution, int stride,
            int levels, HeightfieldSurfacePage.Source source) {
        return new HeightfieldSurfacePage(REVISION, x, z, resolution, stride, levels, source);
    }

    private static final HeightfieldSurfacePage.Source WORLD = (x, z, s) -> {
        s.albedoR = (float) ((Math.floorMod(x * 17 + z * 3, 101)) / 100.0);
        s.albedoG = (float) (Math.floorMod(x * 5 - z * 11, 97) / 96.0);
        s.albedoB = .25f;
        s.normalX = 0; s.normalY = 1; s.normalZ = 0;
        s.roughness = .5f;
        s.semanticId = (int) Math.floorMod(x + z * 7, 256);
    };

    @Test
    void adjacentPagesAndIndependentlyFilteredMipGuttersUseSameWorldCells() {
        HeightfieldSurfacePage left = page(0, 0, 8, 2, 3, WORLD);
        HeightfieldSurfacePage right = page(16, 0, 8, 2, 3, WORLD);
        for (int mip = 0; mip < 3; mip++) {
            int n = left.getInteriorSize(mip);
            for (int z = -2; z < n + 2; z++) {
                assertEquals(left.getAlbedo(mip, n, z, 0), right.getAlbedo(mip, 0, z, 0));
                assertEquals(left.getSemanticId(mip, n, z), right.getSemanticId(mip, 0, z));
            }
            // Each level is composed from its own canonical footprint; its gutter
            // agrees with the matching neighboring interior texel.
            assertEquals(left.getAlbedo(mip, n, 0, 1), right.getAlbedo(mip, 0, 0, 1));
        }
        assertEquals(left.getAlbedo(0, 8, 0, 0), right.getAlbedo(0, 0, 0, 0));
    }

    @Test
    void parentAndChildPagesAgreeOnTheSameCanonicalFootprint() {
        HeightfieldSurfacePage parent = page(0, 0, 16, 2, 2, WORLD);
        HeightfieldSurfacePage child = page(0, 0, 8, 4, 2, WORLD);
        for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
            assertEquals(parent.getAlbedo(1, x, z, 0), child.getAlbedo(0, x, z, 0));
            assertEquals(parent.getSemanticId(1, x, z), child.getSemanticId(0, x, z));
        }
    }

    @Test
    void preservesNormalMomentsRoughnessSquaredAndCategoricalCenter() {
        HeightfieldSurfacePage constant = page(0, 0, 8, 1, 2, (x, z, s) -> {
            s.albedoR = .2f; s.albedoG = .4f; s.albedoB = .6f;
            s.normalX = 0; s.normalY = 0; s.normalZ = 1;
            s.roughness = .5f; s.semanticId = (int) (x & 255);
        });
        assertEquals(1, constant.getNormalMoment(1, 0, 0, 2), 1e-6f);
        assertEquals(.25f, constant.getRoughnessSquared(1, 0, 0), 1e-6f);
        assertEquals(0, constant.getSemanticId(1, 0, 0)); // lower-center x for a 2x2 footprint

        HeightfieldSurfacePage mixed = page(0, 0, 8, 1, 2, (x, z, s) -> {
            s.albedoR = s.albedoG = s.albedoB = 0;
            s.normalX = 0; s.normalY = (x & 1) == 0 ? 1 : -1; s.normalZ = 0;
            s.roughness = 0; s.semanticId = (int) Math.floorMod(x, 2);
        });
        assertEquals(0, mixed.getNormalMoment(1, 0, 0, 1), 1e-6f);
        assertEquals(0, mixed.getSemanticId(1, 0, 0));
    }

    @Test
    void detailWeightDefaultsToOneAndFiltersThroughMipsAndGutters() {
        HeightfieldSurfacePage legacy = page(0, 0, 8, 1, 2, WORLD);
        assertEquals(1f, legacy.getDetailWeight(0, 0, 0));
        assertEquals(1f, legacy.getDetailWeight(1, -1, 0));

        HeightfieldSurfacePage weighted = page(0, 0, 8, 1, 2, (x, z, s) -> {
            s.albedoR = s.albedoG = s.albedoB = .5f;
            s.normalY = 1;
            s.roughness = .5f;
            s.semanticId = 1;
            // The odd-x callback intentionally leaves the field untouched.
            // It must see the compatibility default, not the prior callback's 0.
            if (Math.floorMod(x, 2) == 0) {
                s.detailWeight = 0;
            }
        });
        assertEquals(0f, weighted.getDetailWeight(0, 0, 0));
        assertEquals(1f, weighted.getDetailWeight(0, 1, 0));
        assertEquals(.5f, weighted.getDetailWeight(1, 0, 0));
        assertEquals(.5f, weighted.getDetailWeight(1, -1, 0));

        ByteBuffer encoded = weighted.createAlbedoImage(1).getData(0);
        int alphaOffset = (weighted.getTextureSize(1) * 2 + 2) * 8 + 6;
        assertEquals(.5f, FastMath.convertHalfToFloat(encoded.getShort(alphaOffset)), 1e-3f);
    }

    @Test
    void reportsWorkAndOwnsInputsAndReturnedImages() {
        HeightfieldSurfacePage p = page(0, 0, 8, 1, 2, WORLD);
        assertEquals(12L * 12 + 8L * 8 * 4, p.getSourceSampleCount());
        assertEquals((12L * 12 + 8L * 8) * 25, p.getEncodedByteCount());
        assertEquals((12L * 12 + 8L * 8) * 33, p.getCpuArrayByteCount());
        Image first = p.createAlbedoImage(0);
        assertEquals(Image.Format.RGBA16F, first.getFormat());
        Image second = p.createAlbedoImage(0);
        assertNotSame(first.getData(0), second.getData(0));
        ByteBuffer a = first.getData(0);
        byte original = a.get(0);
        a.put(0, (byte) (original ^ 0x7f));
        assertEquals(original, second.getData(0).get(0));
        assertEquals(original, p.createAlbedoImage(0).getData(0).get(0));
        assertEquals(Image.Format.RGBA32F, p.createNormalImage(1).getFormat());
        assertEquals(Image.Format.Luminance8, p.createSemanticImage(1).getFormat());
    }

    @Test
    void rejectsInvalidValuesDimensionsAndCoordinateOverflow() {
        assertThrows(IllegalArgumentException.class, () -> page(0, 0, 7, 1, 1, WORLD));
        assertThrows(IllegalArgumentException.class, () -> page(1, 0, 8, 1, 1, WORLD));
        assertEquals(3, page(0, 0, 8, 8, 3, WORLD).getLevelCount());
        assertThrows(IllegalArgumentException.class, () -> page(0, 0, 8, 8, 4, WORLD));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(ArithmeticException.class, () -> page(Long.MAX_VALUE - 7, 0, 8, 1, 1,
                (x, z, s) -> calls.incrementAndGet()));
        assertEquals(0, calls.get(), "coordinate extents must be checked before sampling");
        assertThrows(ArithmeticException.class, () -> page(Long.MAX_VALUE - 7, 0, 8, 1, 4,
                (x, z, s) -> calls.incrementAndGet()));
        assertEquals(0, calls.get(), "coarsest mip gutter must be preflighted before sampling");
        assertThrows(IllegalArgumentException.class, () -> page(0, 0, 8, 1, 1, (x, z, s) -> {
            s.albedoR = Float.NaN; s.normalY = 1; s.roughness = .5f;
        }));
        assertThrows(IllegalArgumentException.class, () -> page(0, 0, 8, 1, 1, (x, z, s) -> {
            s.normalX = Float.POSITIVE_INFINITY; s.roughness = 1;
        }));
        assertThrows(IllegalArgumentException.class, () -> page(0, 0, 8, 1, 1, (x, z, s) -> {
            s.normalY = 1; s.roughness = .5f; s.semanticId = 256;
        }));
        assertThrows(IllegalArgumentException.class, () -> page(0, 0, 8, 1, 1, (x, z, s) -> {
            s.normalY = 1; s.roughness = .5f; s.detailWeight = Float.NaN;
        }));
        assertThrows(IllegalArgumentException.class, () -> page(0, 0, 8, 1, 1, (x, z, s) -> {
            s.normalY = 1; s.roughness = .5f; s.detailWeight = 1.01f;
        }));
    }
}
