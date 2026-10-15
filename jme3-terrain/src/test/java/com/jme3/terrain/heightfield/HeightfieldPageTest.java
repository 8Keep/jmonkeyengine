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
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.texture.Image;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeightfieldPageTest {
    private static float[] ramp(int cells) {
        int side = cells + 3;
        float[] data = new float[side * side];
        for (int z = -1; z <= cells + 1; z++) {
            for (int x = -1; x <= cells + 1; x++) {
                data[(z + 1) * side + x + 1] = 20 + 2 * x + 3 * z;
            }
        }
        return data;
    }

    @Test void completeHaloAndIndependentCpuStorage() {
        float[] input = ramp(4);
        HeightfieldPage page = new HeightfieldPage(4, 2, input);
        input[0] = -900;
        assertEquals(15, page.getHeight(-1, -1));
        assertEquals(45, page.getHeight(5, 5));
        Image image = page.createImage();
        assertEquals(7, image.getWidth());
        assertEquals(Image.Format.Luminance32F, image.getFormat());
        assertTrue(image.getData(0).isDirect());
        assertEquals(15, image.getData(0).order(ByteOrder.nativeOrder()).getFloat(0));
        image.getData(0).putFloat(0, -800);
        assertEquals(15, page.getHeight(-1, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> page.getHeight(6, 0));
    }

    @Test void optionalSourceGradientsAreInterleavedValidatedAndUploadedAsRg32f() {
        int cells = 4, side = cells + 3;
        float[] heights = ramp(cells);
        float[] gradients = new float[heights.length * 2];
        int center = ((1 + 1) * side + 1 + 1) * 2;
        gradients[center] = (heights[(1 + 1) * side + 0 + 1] * .5f)
                - (heights[(1 + 1) * side + 2 + 1] * .5f);
        gradients[center + 1] = (heights[(0 + 1) * side + 1 + 1] * .5f)
                - (heights[(2 + 1) * side + 1 + 1] * .5f);
        gradients[0] = 9;
        HeightfieldPage page = new HeightfieldPage(cells, 2, heights, gradients);
        gradients[0] = -100;
        assertTrue(page.hasSourceGradients());
        assertEquals(-2, page.getSourceGradientX(1, 1));
        assertEquals(-3, page.getSourceGradientZ(1, 1));
        assertEquals(9, page.getSourceGradientX(-1, -1));
        assertEquals((long) heights.length * 3 * Float.BYTES, page.getPayloadBytes());

        Image image = page.createGradientImage();
        assertEquals(Image.Format.RG32F, image.getFormat());
        assertEquals(side, image.getWidth());
        assertEquals(side, image.getHeight());
        assertTrue(image.getData(0).isDirect());
        assertEquals(9, image.getData(0).order(ByteOrder.nativeOrder()).getFloat(0));
        image.getData(0).putFloat(0, -200);
        assertEquals(9, page.getSourceGradientX(-1, -1));

        HeightfieldPage withoutGradients = new HeightfieldPage(cells, 2, heights);
        assertTrue(!withoutGradients.hasSourceGradients());
        assertEquals((long) heights.length * Float.BYTES, withoutGradients.getPayloadBytes());
        assertThrows(IllegalStateException.class, withoutGradients::createGradientImage);
        assertThrows(IllegalArgumentException.class,
                () -> new HeightfieldPage(cells, 2, heights, new float[heights.length]));
        float[] invalid = new float[heights.length * 2];
        invalid[invalid.length - 1] = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> new HeightfieldPage(cells, 2, heights, invalid));
    }

    @Test void boundsCoverDisplacedInteriorAndIgnoreHaloOutliers() {
        float[] values = ramp(4);
        values[0] = 10000;
        HeightfieldPage page = new HeightfieldPage(4, 2, values);
        BoundingBox box = page.createBound();
        assertEquals(new Vector3f(4, 30, 4), box.getCenter());
        assertEquals(4, box.getXExtent());
        assertEquals(10, box.getYExtent());
        assertEquals(4, box.getZExtent());
    }

    @Test void sharedBuffersRetainIndependentDisplacedAndTransformedBounds() {
        HeightfieldGrid grid = new HeightfieldGrid(4);
        Mesh first = grid.createPatchMesh(new HeightfieldPage(4, 2, ramp(4)));
        float[] high = ramp(4);
        for (int i = 0; i < high.length; i++) high[i] += 100;
        Mesh second = grid.createPatchMesh(new HeightfieldPage(4, 2, high));
        assertSame(first.getBuffer(Type.Position), second.getBuffer(Type.Position));
        assertSame(first.getBuffer(Type.Index), second.getBuffer(Type.Index));
        assertNotSame(first.getBound(), second.getBound());
        assertEquals(30, first.getBound().getCenter().y);
        assertEquals(130, second.getBound().getCenter().y);
        Geometry geometry = new Geometry("high", second);
        geometry.setLocalTranslation(100, 5, 200);
        geometry.setLocalScale(2, 3, 4);
        geometry.updateGeometricState();
        assertEquals(new Vector3f(108, 395, 216), geometry.getWorldBound().getCenter());
        assertEquals(25, first.getVertexCount());
        assertEquals(32, first.getTriangleCount());
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f();
        first.getTriangle(0, a, b, c);
        assertTrue(b.subtract(a).cross(c.subtract(a)).y > 0);
    }

    @Test void boundsRemainConservativeWhenCenterRoundsToAnEndpoint() {
        float[] values = new float[25];
        Arrays.fill(values, 1);
        values[12] = Math.nextUp(1f);
        BoundingBox box = new HeightfieldPage(2, 1, values).createBound();
        assertTrue(box.getCenter().y + box.getYExtent() >= Math.nextUp(1f));
        assertTrue(box.getCenter().y - box.getYExtent() <= 1);
    }

    @Test void horizontalBoundsCoverRoundedSubnormalEndpoints() {
        for (float spacing : new float[]{Float.MIN_VALUE, Math.nextDown(Float.MIN_NORMAL),
                Float.MIN_NORMAL, .25f, 3.1f}) {
            HeightfieldPage page = new HeightfieldPage(5, spacing, new float[64]);
            BoundingBox box = page.createBound();
            float endpoint = 5f * spacing;
            assertTrue(box.getCenter().x - box.getXExtent() <= 0);
            assertTrue(box.getCenter().x + box.getXExtent() >= endpoint);
            assertTrue(box.getCenter().z - box.getZExtent() <= 0);
            assertTrue(box.getCenter().z + box.getZExtent() >= endpoint);
        }
    }

    @Test void adjacentNonlinearPagesShareEdgeAndCornerNormalSamples() {
        int cells = 4;
        HeightfieldPage[][] pages = new HeightfieldPage[2][2];
        for (int pz = 0; pz < 2; pz++) {
            for (int px = 0; px < 2; px++) {
                int side = cells + 3;
                float[] data = new float[side * side];
                for (int z = -1; z <= cells + 1; z++) {
                    for (int x = -1; x <= cells + 1; x++) {
                        int worldX = px * cells + x;
                        int worldZ = pz * cells + z;
                        data[(z + 1) * side + x + 1] = 20 + worldX * worldX
                                + worldZ * worldZ * 3 + worldX * worldZ;
                    }
                }
                pages[pz][px] = new HeightfieldPage(cells, .5f, data);
            }
        }
        // Compare the actual inputs to central differences, including the
        // shared corner and halo: equal heights alone cannot prove seam normals.
        for (int i = -1; i <= cells + 1; i++) {
            for (int offset = -1; offset <= 1; offset++) {
                for (int row = 0; row < 2; row++) {
                    assertEquals(pages[row][0].getHeight(cells + offset, i),
                            pages[row][1].getHeight(offset, i));
                }
                for (int column = 0; column < 2; column++) {
                    assertEquals(pages[0][column].getHeight(i, cells + offset),
                            pages[1][column].getHeight(i, offset));
                }
            }
        }
    }

    @Test void invalidPayloadsRejectBeforeGpuUse() {
        assertThrows(IllegalArgumentException.class, () -> new HeightfieldPage(1, 1, new float[16]));
        assertThrows(IllegalArgumentException.class, () -> new HeightfieldPage(4, 0, ramp(4)));
        assertThrows(IllegalArgumentException.class, () -> new HeightfieldPage(4, Float.NaN, ramp(4)));
        assertThrows(IllegalArgumentException.class, () -> new HeightfieldPage(4, Float.MAX_VALUE, ramp(4)));
        assertThrows(IllegalArgumentException.class, () -> new HeightfieldPage(4, 1, new float[25]));
        float[] invalid = ramp(4); invalid[0] = Float.POSITIVE_INFINITY;
        assertThrows(IllegalArgumentException.class, () -> new HeightfieldPage(4, 1, invalid));
        HeightfieldGrid grid = new HeightfieldGrid(4);
        assertThrows(IllegalArgumentException.class, () -> grid.createPatchMesh(new HeightfieldPage(8, 1, ramp(8))));
    }
}
