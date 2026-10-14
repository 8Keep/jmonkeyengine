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
import com.jme3.renderer.Camera;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeightfieldProjectionTest {
    @Test void densePerspectiveSamplesStayUnderBoundIncludingOffAxisAndSubViewports() {
        Camera camera = perspective(1280, 720);
        camera.setViewPortLeft(.13f);
        camera.setViewPortRight(.82f);
        camera.setViewPortBottom(.09f);
        camera.setViewPortTop(.91f);
        assertDense(camera, new BoundingBox(new Vector3f(1.1f, .3f, -8), 1.7f, 1.1f, 2), .22);
    }

    @Test void asymmetricPerspectiveUsesActualProjectionMatrix() {
        Camera camera = new Camera(900, 600);
        camera.setFrustum(1, 80, -.6f, 1.8f, -.7f, 1.1f);
        camera.setLocation(new Vector3f(2, -1, 3));
        camera.lookAt(new Vector3f(0, 0, -5), Vector3f.UNIT_Y);
        assertDense(camera, new BoundingBox(new Vector3f(.3f, -.2f, -7), 1.5f, 1.2f, 1.8f), .3);
    }

    @Test void orthographicSamplesAndViewportSubdivisionStayUnderBound() {
        Camera camera = new Camera(1024, 768);
        camera.setParallelProjection(true);
        camera.setFrustum(1, 100, -12, 20, 11, -9);
        camera.setLocation(new Vector3f(0, 0, 0));
        camera.lookAt(new Vector3f(0, 0, -1), Vector3f.UNIT_Y);
        camera.setViewPortLeft(.25f);
        camera.setViewPortRight(.75f);
        camera.setViewPortBottom(.2f);
        camera.setViewPortTop(.8f);
        assertDense(camera, new BoundingBox(new Vector3f(2, 1, -20), 5, 4, 3), .5);
    }

    @Test void nearPlaneCrossingsAreUnboundedAndOutsideBoxesCullToZero() {
        Camera camera = perspective(800, 600);
        assertEquals(Double.POSITIVE_INFINITY, HeightfieldProjection.pixels(camera,
                new BoundingBox(new Vector3f(0, 0, -1.05f), .1f, .1f, .1f), .1));
        assertEquals(0, HeightfieldProjection.pixels(camera,
                new BoundingBox(new Vector3f(1000, 0, -10), 1, 1, 1), .5));
        assertEquals(Double.POSITIVE_INFINITY, HeightfieldProjection.pixels(camera,
                new BoundingBox(new Vector3f(0, 0, -10), 1, 1, 1), Double.POSITIVE_INFINITY));
    }

    @Test void rejectsInvalidErrorBoundsCameraMatrixAndViewport() {
        Camera camera = perspective(800, 600);
        BoundingBox box = new BoundingBox(new Vector3f(0, 0, -10), 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> HeightfieldProjection.pixels(camera, box, -1));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldProjection.pixels(camera, box, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldProjection.pixels(null, box, 1));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldProjection.pixels(camera, null, 1));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldProjection.pixels(camera,
                new BoundingBox(new Vector3f(), Float.NaN, 1, 1), 1));
        Camera invalidSize = perspective(800, 600);
        invalidSize.resize(0, 600, false);
        assertThrows(IllegalArgumentException.class, () -> HeightfieldProjection.pixels(invalidSize, box, 1));
        camera.getViewProjectionMatrix().m00 = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> HeightfieldProjection.pixels(camera, box, 1));
    }

    private static Camera perspective(int width, int height) {
        Camera camera = new Camera(width, height);
        camera.setFrustumPerspective(55, width / (float) height, 1, 100);
        camera.setLocation(new Vector3f(0, 0, 0));
        camera.lookAt(new Vector3f(0, 0, -1), Vector3f.UNIT_Y);
        return camera;
    }

    private static void assertDense(Camera camera, BoundingBox box, double error) {
        double bound = HeightfieldProjection.pixels(camera, box, error);
        assertTrue(Double.isFinite(bound) && bound > 0, "expected finite positive bound: " + bound);
        Vector3f center = box.getCenter();
        float[][] directions = {
            {1, 0, 0}, {0, 1, 0}, {0, 0, 1},
            {1, 1, 1}, {-1, 1, 1}, {1, -1, 1}, {1, 1, -1},
            {-1, -1, 1}, {-1, 1, -1}, {1, -1, -1}, {-1, -1, -1}
        };
        for (int iz = 0; iz <= 10; iz++) {
            for (int iy = 0; iy <= 10; iy++) {
                for (int ix = 0; ix <= 10; ix++) {
                    Vector3f point = new Vector3f(
                            center.x - box.getXExtent() + 2 * box.getXExtent() * ix / 10f,
                            center.y - box.getYExtent() + 2 * box.getYExtent() * iy / 10f,
                            center.z - box.getZExtent() + 2 * box.getZExtent() * iz / 10f);
                    for (float[] direction : directions) {
                        float x = direction[0];
                        float y = direction[1];
                        float z = direction[2];
                        float length = (float) Math.sqrt(x * x + y * y + z * z);
                        Vector3f offset = new Vector3f((float) (error * x / length),
                                (float) (error * y / length), (float) (error * z / length));
                        Vector3f a = camera.getScreenCoordinates(point);
                        Vector3f b = camera.getScreenCoordinates(point.add(offset));
                        // The viewport origin cancels when subtracting screen points;
                        // their difference uses only the viewport's physical pixel scale.
                        double dx = a.x - b.x;
                        double dy = a.y - b.y;
                        assertTrue(Math.hypot(dx, dy) <= bound * 1.000001,
                                () -> "projected displacement " + Math.hypot(dx, dy) + " exceeded " + bound);
                    }
                }
            }
        }
    }
}
