/*
 * Copyright (c) 2026 jMonkeyEngine
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
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.scene;

import com.jme3.math.Matrix4f;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.shape.Box;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeometryTest {

    @Test
    void computeWorldMatrixMatchesRightMultipliedScaleMatrix() {
        Geometry geometry = createTransformedGeometry();

        geometry.computeWorldMatrix();

        assertWorldMatrixMatchesLegacyComputation(geometry);
    }

    @Test
    void computeWorldMatrixMatchesRightMultipliedScaleMatrixWithParent() {
        Node parent = new Node("parent");
        parent.setLocalTranslation(-2f, 4f, 6f);
        parent.setLocalRotation(new Quaternion().fromAngles(-0.8f, 0.3f, 1.1f));
        parent.setLocalScale(1.5f, -0.75f, 2f);
        Geometry geometry = createTransformedGeometry();
        parent.attachChild(geometry);
        parent.updateGeometricState();

        assertWorldMatrixMatchesLegacyComputation(geometry);
    }

    @Test
    void finiteWorldMatricesMatchLegacyAcrossRepeatedParentUpdates() {
        Node parent = new Node("parent");
        parent.setLocalTranslation(-2, 4, 6);
        parent.setLocalRotation(new Quaternion().fromAngles(-.8f, .3f, 1.1f));
        parent.setLocalScale(1.5f, -.75f, 2);
        Geometry geometry = createTransformedGeometry();
        parent.attachChild(geometry);
        Matrix4f cached = geometry.getWorldMatrix();
        Random random = new Random(8471);
        Vector3f[] scales = {new Vector3f(1, 1, 1), new Vector3f(-2, .25f, 3.5f),
            new Vector3f(0, -0f, -1), new Vector3f(.001f, 1000, -.5f)};
        for (int i = 0; i < 128; i++) {
            parent.setLocalTranslation(i % 7, -i % 3, i * .1f);
            geometry.setLocalTranslation(random.nextFloat() * 20 - 10,
                    random.nextFloat() * 20 - 10, random.nextFloat() * 20 - 10);
            geometry.setLocalRotation(i % 4 == 0 ? new Quaternion()
                    : new Quaternion().fromAngles(random.nextFloat() * 6,
                            random.nextFloat() * 6, random.nextFloat() * 6));
            geometry.setLocalScale(scales[i % scales.length]);
            if (i % 2 == 0) {
                parent.updateGeometricState();
            } else {
                geometry.computeWorldMatrix();
            }
            Matrix4f expected = legacyWorldMatrix(geometry);
            org.junit.jupiter.api.Assertions.assertSame(cached, geometry.getWorldMatrix());
            assertNumericallyEqual(expected, cached);
            // Repeated render-time recomputation must not accumulate scale.
            geometry.computeWorldMatrix();
            assertNumericallyEqual(expected, cached);
        }
    }

    @Test
    void ignoredTransformAndRecomputationOverwriteCallerModifiedCache() {
        Geometry geometry = createTransformedGeometry();
        geometry.computeWorldMatrix();
        Matrix4f cached = geometry.getWorldMatrix();
        geometry.setIgnoreTransform(true);
        cached.m00 = 73;
        cached.m30 = 19;
        geometry.computeWorldMatrix();
        assertEquals(Matrix4f.IDENTITY, cached);
        geometry.setLocalScale(.5f, -2f, 0);
        geometry.setIgnoreTransform(false);
        geometry.computeWorldMatrix();
        assertNumericallyEqual(legacyWorldMatrix(geometry), cached);
        assertEquals(0f, cached.m30, 0f);
        assertEquals(0f, cached.m31, 0f);
        assertEquals(0f, cached.m32, 0f);
        assertEquals(1f, cached.m33, 0f);
    }

    private static void assertNumericallyEqual(Matrix4f expected, Matrix4f actual) {
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                // Equivalent transforms may differ only in the sign of exact zero.
                assertEquals(expected.get(row, column), actual.get(row, column), 0f);
            }
        }
    }

    private static Matrix4f legacyWorldMatrix(Geometry geometry) {
        Matrix4f result = new Matrix4f();
        result.setRotationQuaternion(geometry.getWorldRotation());
        result.setTranslation(geometry.getWorldTranslation());
        Matrix4f scale = new Matrix4f();
        scale.scale(geometry.getWorldScale());
        return result.multLocal(scale);
    }

    private static Geometry createTransformedGeometry() {
        Geometry geometry = new Geometry("geometry", new Box(1f, 2f, 3f));
        geometry.setLocalTranslation(new Vector3f(3f, -5f, 7f));
        geometry.setLocalRotation(new Quaternion().fromAngles(0.4f, -1.2f, 2.3f));
        geometry.setLocalScale(new Vector3f(-2f, 0.25f, 3.5f));
        return geometry;
    }

    private static void assertWorldMatrixMatchesLegacyComputation(Geometry geometry) {
        geometry.computeWorldMatrix();

        Matrix4f expected = new Matrix4f();
        expected.setRotationQuaternion(geometry.getWorldRotation());
        expected.setTranslation(geometry.getWorldTranslation());
        Matrix4f scaleMatrix = new Matrix4f();
        scaleMatrix.scale(geometry.getWorldScale());
        expected.multLocal(scaleMatrix);

        assertEquals(expected, geometry.getWorldMatrix());
    }
}
