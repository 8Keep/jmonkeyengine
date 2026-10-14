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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeightfieldReconstructionTest {
    private static HeightfieldPage page(int cells, float spacing, HeightFunction source) {
        int side = cells + 3;
        float[] samples = new float[side * side];
        for (int z = -1; z <= cells + 1; z++) {
            for (int x = -1; x <= cells + 1; x++) {
                samples[(z + 1) * side + x + 1] = source.height(x, z);
            }
        }
        return new HeightfieldPage(cells, spacing, samples);
    }

    private static double referenceTriangle(HeightfieldPage page, double x, double z,
            int stride) {
        int ix = x == page.getCells() ? page.getCells() - stride
                : (int) Math.floor(x / stride) * stride;
        int iz = z == page.getCells() ? page.getCells() - stride
                : (int) Math.floor(z / stride) * stride;
        double u = (x - ix) / stride;
        double v = (z - iz) / stride;
        double a = page.getHeight(ix, iz);
        double b = page.getHeight(ix + stride, iz);
        double c = page.getHeight(ix, iz + stride);
        double d = page.getHeight(ix + stride, iz + stride);
        if (u >= v) {
            return a * (1 - u) + b * (u - v) + d * v;
        }
        return a * (1 - v) + c * (v - u) + d * u;
    }

    @Test void followsTheGridDiagonalInsteadOfBilinearInterpolation() {
        HeightfieldPage saddle = page(2, 1, (x, z) -> x == 1 && z == 1 ? 1 : 0);
        assertEquals(.25, HeightfieldReconstruction.height(saddle, .75, .25, 1), 1e-12);
        assertEquals(.25, referenceTriangle(saddle, .75, .25, 1), 1e-12);
        assertTrue(Math.abs(.25 - (.75 * .25)) > .05, "bilinear interpolation differs on the saddle");

        for (int stride : new int[]{1, 2, 4}) {
            HeightfieldPage nonlinear = page(8, .75f, (x, z) -> x * x - 3 * z + x * z * .5f);
            for (double z = 0; z <= 8; z += .25) {
                for (double x = 0; x <= 8; x += .25) {
                    assertEquals(referenceTriangle(nonlinear, x, z, stride),
                            HeightfieldReconstruction.height(nonlinear, x, z, stride), 1e-12);
                }
            }
        }
    }

    @Test void maximumErrorSeesFineVerticesMissedByCoarseSamples() {
        HeightfieldPage spike = page(8, 1, (x, z) -> x == 3 && z == 3 ? 12 : 0);
        assertEquals(12, HeightfieldReconstruction.maximumError(spike, 0, 0, 4, 4), 0);
        assertEquals(0, HeightfieldReconstruction.maximumError(spike, 4, 4, 4, 4), 0);
        assertEquals(0, HeightfieldReconstruction.maximumError(spike, 0, 0, 4, 1), 0);
    }

    @Test void validatesPageCoordinatesStrideRegionsAndMorphInputs() {
        HeightfieldPage flat = page(8, 2, (x, z) -> x + z);
        assertThrows(IllegalArgumentException.class, () -> HeightfieldReconstruction.height(null, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldReconstruction.height(flat, -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldReconstruction.height(flat, 9, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldReconstruction.height(flat, Double.NaN, 0, 1));
        for (int stride : new int[]{0, -1, 3, 16}) {
            assertThrows(IllegalArgumentException.class, () -> HeightfieldReconstruction.height(flat, 0, 0, stride));
        }
        assertThrows(IllegalArgumentException.class, () -> HeightfieldReconstruction.maximumError(flat, 1, 0, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldReconstruction.maximumError(flat, 0, 0, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldReconstruction.maximumError(flat, 6, 0, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldReconstruction.maximumError(flat, 0, 0, 4, 3));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldReconstruction.morphVertex(flat, 1, 0, 2, .5f, null));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldReconstruction.morphVertex(flat, 0, 9, 1, .5f, null));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldReconstruction.morphVertex(flat, 0, 0, 1, Float.NaN, null));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldReconstruction.morphVertex(flat, 0, 0, 1, 1.01f, null));
        assertEquals(16, HeightfieldReconstruction.height(flat, 8, 8, 4), 0);
    }

    @Test void fullMorphCollapsesOntoExactParentTriangleSurface() {
        HeightfieldPage nonlinear = page(8, .5f, (x, z) -> x * x + 2 * z * z - 3 * x * z);
        Vector3f store = new Vector3f();
        for (int stride : new int[]{1, 2}) {
            int parentStride = stride * 2;
            for (int z = 0; z <= 8; z += stride) {
                for (int x = 0; x <= 8; x += stride) {
                    Vector3f result = HeightfieldReconstruction.morphVertex(
                            nonlinear, x, z, stride, 1, store);
                    assertSame(store, result);
                    double px = (x / parentStride) * parentStride;
                    double pz = (z / parentStride) * parentStride;
                    assertEquals(px * .5, result.x, 0);
                    assertEquals(pz * .5, result.z, 0);
                    assertEquals(nonlinear.getHeight((int) px, (int) pz), result.y, 0);
                    assertEquals(HeightfieldReconstruction.height(nonlinear, result.x / .5,
                            result.z / .5, parentStride), result.y, 1e-5);
                }
            }
        }
        assertNotSame(store, HeightfieldReconstruction.morphVertex(nonlinear, 0, 0, 1, 0, null));
    }

    @Test void morphInterpolationIsFiniteEndpointPreservingAndRangeBounded() {
        float almostMaximum = Math.nextDown(Float.MAX_VALUE);
        HeightfieldPage nearMaximum = page(8, 1, (x, z) -> x == 3 ? almostMaximum : Float.MAX_VALUE);
        float tinyAlpha = 0x1.0p-25f;
        Vector3f result = HeightfieldReconstruction.morphVertex(
                nearMaximum, 3, 0, 1, tinyAlpha, null);
        assertTrue(Float.isFinite(result.y), "near-maximum interpolation must not overflow");
        assertTrue(result.y >= almostMaximum && result.y <= Float.MAX_VALUE,
                "interpolation must stay inside its endpoint hull");

        Vector3f exactStart = HeightfieldReconstruction.morphVertex(
                nearMaximum, 3, 0, 1, 0f, null);
        Vector3f exactEnd = HeightfieldReconstruction.morphVertex(
                nearMaximum, 3, 0, 1, 1f, null);
        assertEquals(Float.floatToRawIntBits(almostMaximum), Float.floatToRawIntBits(exactStart.y));
        assertEquals(Float.floatToRawIntBits(Float.MAX_VALUE), Float.floatToRawIntBits(exactEnd.y));

        HeightfieldPage maximumFlat = page(8, 1, (x, z) -> Float.MAX_VALUE);
        Vector3f flat = HeightfieldReconstruction.morphVertex(
                maximumFlat, 3, 0, 1, tinyAlpha, null);
        assertTrue(Float.isFinite(flat.y));
        assertEquals(Float.floatToRawIntBits(Float.MAX_VALUE), Float.floatToRawIntBits(flat.y),
                "equal endpoints remain bit-exact for small alpha");

        float largeFinite = Float.MAX_VALUE * .5f;
        HeightfieldPage opposite = page(8, 1, (x, z) -> x == 3 ? -largeFinite : largeFinite);
        Vector3f midpoint = HeightfieldReconstruction.morphVertex(
                opposite, 3, 0, 1, .5f, null);
        assertEquals(0f, midpoint.y, 0f);
        assertTrue(Float.isFinite(midpoint.y));

        HeightfieldPage subnormal = page(8, 1,
                (x, z) -> x == 3 ? Float.MIN_VALUE : 0f);
        Vector3f subnormalMix = HeightfieldReconstruction.morphVertex(
                subnormal, 3, 0, 1, .5f, null);
        assertTrue(Float.isFinite(subnormalMix.y));
        assertTrue(subnormalMix.y >= 0f && subnormalMix.y <= Float.MIN_VALUE,
                "subnormal interpolation remains within its endpoint hull");
        assertEquals(Float.MIN_VALUE, HeightfieldReconstruction.morphVertex(
                subnormal, 3, 0, 1, 0f, null).y, 0f);
        assertEquals(0f, HeightfieldReconstruction.morphVertex(
                subnormal, 3, 0, 1, 1f, null).y, 0f);
    }

    @Test void signedZeroEndpointsKeepTheirOwnBitPatterns() {
        for (float first : new float[]{0f, -0f}) {
            for (float second : new float[]{0f, -0f}) {
                HeightfieldPage zero = page(8, 1, (x, z) -> x == 3 ? first : second);
                assertEquals(Float.floatToRawIntBits(first), Float.floatToRawIntBits(
                        HeightfieldReconstruction.morphVertex(zero, 3, 0, 1, 0f, null).y));
                assertEquals(Float.floatToRawIntBits(second), Float.floatToRawIntBits(
                        HeightfieldReconstruction.morphVertex(zero, 3, 0, 1, 1f, null).y));
            }
        }
    }

    @Test void edgeFlagsPreserveOrientationAndKeepMorphDeviationWithinErrorEnvelope() {
        HeightfieldPage nonlinear = page(8, 1, (x, z) -> .7f * x * x - 1.3f * z * z + 2.1f * x * z);
        int stride = 2;
        double parentError = HeightfieldReconstruction.maximumError(nonlinear, 0, 0, 8, stride * 2);
        Vector3f[][] result = new Vector3f[5][5];
        for (int zi = 0; zi <= 4; zi++) {
            for (int xi = 0; xi <= 4; xi++) {
                int x = xi * stride;
                int z = zi * stride;
                float interior = .37f;
                // Per-edge flags take max(edge values, interior morph amount).
                float alpha = interior;
                if (xi == 0) alpha = Math.max(alpha, .61f);
                if (xi == 4) alpha = Math.max(alpha, .82f);
                if (zi == 0) alpha = Math.max(alpha, .73f);
                if (zi == 4) alpha = Math.max(alpha, .54f);
                result[zi][xi] = HeightfieldReconstruction.morphVertex(nonlinear,
                        x, z, stride, alpha, null);
                double movedX = result[zi][xi].x;
                double movedZ = result[zi][xi].z;
                double parentHeight = HeightfieldReconstruction.height(nonlinear,
                        movedX, movedZ, stride * 2);
                double fineHeight = HeightfieldReconstruction.height(nonlinear,
                        movedX, movedZ, 1);
                assertTrue(Math.abs(result[zi][xi].y - parentHeight) <= parentError + 1e-4);
                assertTrue(Math.abs(result[zi][xi].y - fineHeight) <= 2 * parentError + 1e-4);
            }
        }
        for (int zi = 0; zi < 4; zi++) {
            for (int xi = 0; xi < 4; xi++) {
                Vector3f a = result[zi][xi];
                Vector3f d = result[zi + 1][xi + 1];
                Vector3f b = result[zi][xi + 1];
                Vector3f c = result[zi + 1][xi];
                assertTrue(orientedAreaXZ(a, d, b) >= -1e-6);
                assertTrue(orientedAreaXZ(a, c, d) >= -1e-6);
                assertEnvelope(nonlinear, parentError, a, d, b, stride * 2);
                assertEnvelope(nonlinear, parentError, a, c, d, stride * 2);
            }
        }
    }

    @Test void adjacentPagesShareIntermediateMorphSeamsAndCornersAtSeveralLods() {
        int cells = 8;
        float spacing = .25f;
        for (int level : new int[]{1, 2, 4}) {
            HeightfieldPage[][] pages = new HeightfieldPage[2][2];
            for (int pz = 0; pz < 2; pz++) {
                for (int px = 0; px < 2; px++) {
                    final int pageX = px;
                    final int pageZ = pz;
                    pages[pz][px] = page(cells, spacing, (x, z) -> {
                        int wx = pageX * cells + x;
                        int wz = pageZ * cells + z;
                        return wx * wx + 3 * wz * wz - 2 * wx * wz;
                    });
                }
            }
            float alpha = .43f;
            for (int k = 0; k <= cells; k += level) {
                Vector3f west = HeightfieldReconstruction.morphVertex(pages[0][0], cells, k,
                        level, alpha, null);
                Vector3f east = HeightfieldReconstruction.morphVertex(pages[0][1], 0, k,
                        level, alpha, null);
                assertEquals(west.y, east.y, 0);
                assertEquals(west.z, east.z, 0);
                Vector3f north = HeightfieldReconstruction.morphVertex(pages[0][0], k, cells,
                        level, alpha, null);
                Vector3f south = HeightfieldReconstruction.morphVertex(pages[1][0], k, 0,
                        level, alpha, null);
                assertEquals(north.y, south.y, 0);
                assertEquals(north.x, south.x, 0);
            }
            for (int pz = 0; pz <= 1; pz++) {
                for (int px = 0; px <= 1; px++) {
                    HeightfieldPage page = pages[pz][px];
                    int x = px == 0 ? cells : 0;
                    int z = pz == 0 ? cells : 0;
                    Vector3f corner = HeightfieldReconstruction.morphVertex(page, x, z,
                            level, alpha, null);
                    assertEquals((px * cells + x) * spacing,
                            corner.x + px * cells * spacing, 1e-6);
                    assertEquals((pz * cells + z) * spacing,
                            corner.z + pz * cells * spacing, 1e-6);
                    assertEquals(pages[0][0].getHeight(cells, cells), corner.y, 0);
                }
            }
        }

        // A fine edge forced to alpha=1 collapses exactly to its coarser
        // neighbor's stride-2 boundary samples and line segments.
        HeightfieldPage fine = page(cells, spacing, (x, z) -> x * x + z * z * 2 + x * z);
        HeightfieldPage coarse = page(cells, spacing, (x, z) -> x * x + z * z * 2 + x * z);
        for (int k = 0; k <= cells; k++) {
            Vector3f fineEdge = HeightfieldReconstruction.morphVertex(fine, 0, k, 1, 1, null);
            double coarseHeight = HeightfieldReconstruction.height(coarse, 0,
                    fineEdge.z / spacing, 2);
            assertEquals(coarseHeight, fineEdge.y, 0);
        }
    }

    @Test void exhaustiveBoundaryFlagsKeepThePatchCoveredAndUnflipped() {
        HeightfieldPage nonlinear = page(8, 1, (x, z) -> x * x + 2 * z * z - 3 * x * z);
        float[] alphaChoices = {0, .25f, .5f, .75f, 1};
        // Edge order: west, east, south, north. Choice 5 means a coarser
        // neighbor, which forces that edge alpha to one.
        for (float interior : alphaChoices) {
            for (int configuration = 0; configuration < 6 * 6 * 6 * 6; configuration++) {
                int remaining = configuration;
                float[] edgeAlpha = new float[4];
                for (int edge = 0; edge < edgeAlpha.length; edge++) {
                    int choice = remaining % 6;
                    remaining /= 6;
                    edgeAlpha[edge] = choice == 5 ? 1
                            : Math.max(interior, alphaChoices[choice]);
                }

                Vector3f[][] vertices = new Vector3f[9][9];
                for (int z = 0; z <= 8; z++) {
                    for (int x = 0; x <= 8; x++) {
                        float alpha = interior;
                        if (x == 0) alpha = Math.max(alpha, edgeAlpha[0]);
                        if (x == 8) alpha = Math.max(alpha, edgeAlpha[1]);
                        if (z == 0) alpha = Math.max(alpha, edgeAlpha[2]);
                        if (z == 8) alpha = Math.max(alpha, edgeAlpha[3]);
                        vertices[z][x] = HeightfieldReconstruction.morphVertex(
                                nonlinear, x, z, 1, alpha, null);
                    }
                }

                double patchArea = 0;
                for (int parentZ = 0; parentZ < 8; parentZ += 2) {
                    for (int parentX = 0; parentX < 8; parentX += 2) {
                        double lowerArea = 0;
                        double upperArea = 0;
                        for (int z = parentZ; z < parentZ + 2; z++) {
                            for (int x = parentX; x < parentX + 2; x++) {
                                Vector3f a = vertices[z][x];
                                Vector3f b = vertices[z][x + 1];
                                Vector3f c = vertices[z + 1][x];
                                Vector3f d = vertices[z + 1][x + 1];
                                double firstArea = orientedAreaXZ(a, d, b);
                                double secondArea = orientedAreaXZ(a, c, d);
                                assertTrue(firstArea >= -1e-6,
                                        "a,d,b flipped for interior=" + interior + " config=" + configuration);
                                assertTrue(secondArea >= -1e-6,
                                        "a,c,d flipped for interior=" + interior + " config=" + configuration);
                                patchArea += firstArea + secondArea;

                                // The two source triangles stay on their side
                                // of this parent cell's diagonal. Each parent
                                // half must retain its full determinant area.
                                if (x + 2.0 / 3 - parentX >= z + 1.0 / 3 - parentZ) {
                                    lowerArea += firstArea;
                                } else {
                                    upperArea += firstArea;
                                }
                                if (x + 1.0 / 3 - parentX >= z + 2.0 / 3 - parentZ) {
                                    lowerArea += secondArea;
                                } else {
                                    upperArea += secondArea;
                                }
                            }
                        }
                        assertEquals(4, lowerArea, 1e-5,
                                "lower parent triangle lost area at " + parentX + "," + parentZ);
                        assertEquals(4, upperArea, 1e-5,
                                "upper parent triangle lost area at " + parentX + "," + parentZ);
                    }
                }
                assertEquals(128, patchArea, 1e-4,
                        "patch area changed for interior=" + interior + " config=" + configuration);
            }
        }
    }

    private static double orientedAreaXZ(Vector3f a, Vector3f b, Vector3f c) {
        return (b.z - a.z) * (c.x - a.x) - (b.x - a.x) * (c.z - a.z);
    }

    private static void assertEnvelope(HeightfieldPage page, double parentError,
            Vector3f a, Vector3f b, Vector3f c, int parentStride) {
        for (int stepA = 0; stepA <= 4; stepA++) {
            for (int stepB = 0; stepB <= 4 - stepA; stepB++) {
                int stepC = 4 - stepA - stepB;
                double wa = stepA / 4.0;
                double wb = stepB / 4.0;
                double wc = stepC / 4.0;
                double x = wa * a.x + wb * b.x + wc * c.x;
                double z = wa * a.z + wb * b.z + wc * c.z;
                double y = wa * a.y + wb * b.y + wc * c.y;
                double parentHeight = HeightfieldReconstruction.height(page,
                        x, z, parentStride);
                double fineHeight = HeightfieldReconstruction.height(page, x, z, 1);
                assertTrue(Math.abs(y - parentHeight) <= parentError + 1e-4);
                assertTrue(Math.abs(y - fineHeight) <= 2 * parentError + 1e-4);
            }
        }
    }

    private interface HeightFunction {
        float height(int x, int z);
    }
}
