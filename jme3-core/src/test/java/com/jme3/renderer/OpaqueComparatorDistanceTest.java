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
package com.jme3.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jme3.bounding.BoundingBox;
import com.jme3.material.Material;
import com.jme3.material.MaterialDef;
import com.jme3.material.TechniqueDef;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.GeometryList;
import com.jme3.renderer.queue.OpaqueComparator;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import org.junit.jupiter.api.Test;

class OpaqueComparatorDistanceTest {

    @Test
    void sortsByProjectedDistanceUsingTheCameraSelectedForTheQueue() {
        Camera camera = new Camera(1, 1);
        camera.lookAtDirection(Vector3f.UNIT_Z, Vector3f.UNIT_Y);
        OpaqueComparator comparator = new OpaqueComparator();
        GeometryList list = new GeometryList(comparator);
        MaterialDef materialDef = new MaterialDef(null, "test");
        materialDef.addTechniqueDef(new TechniqueDef(TechniqueDef.DEFAULT_TECHNIQUE_NAME, 0));
        Material material = new Material(materialDef);
        Geometry near = geometry("near", 2f, material);
        Geometry far = geometry("far", 8f, material);

        list.add(far);
        list.add(near);
        list.setCamera(camera);
        list.sort();

        assertEquals(near, list.get(0));
        assertEquals(far, list.get(1));
        assertEquals(2f, near.queueDistance);
        assertEquals(8f, far.queueDistance);

        // RenderQueue clears this cache after rendering each geometry before a
        // subsequent queue/camera can sort it again.
        near.queueDistance = Float.NEGATIVE_INFINITY;
        far.queueDistance = Float.NEGATIVE_INFINITY;
        camera.setLocation(new Vector3f(0f, 0f, 5f));
        list.setCamera(camera);
        list.sort();

        assertEquals(near, list.get(0));
        assertEquals(-3f, near.queueDistance);
        assertEquals(3f, far.queueDistance);
    }

    @Test
    void distanceUsesLiveCameraPositionAfterSetCamera() {
        Camera camera = new Camera(1, 1);
        camera.lookAtDirection(Vector3f.UNIT_Z, Vector3f.UNIT_Y);
        OpaqueComparator comparator = new OpaqueComparator(); comparator.setCamera(camera);
        camera.setLocation(new Vector3f(0, 0, 5));
        Geometry geometry = new Geometry("fresh", new com.jme3.scene.Mesh());
        geometry.setLocalTranslation(0, 0, 8); geometry.updateGeometricState();
        assertEquals(3f, comparator.distanceToCam(geometry), 0f);
    }

    @Test
    void distanceUsesLiveCameraRotationAfterSetCamera() {
        Camera camera = new Camera(1, 1);
        camera.lookAtDirection(Vector3f.UNIT_Z, Vector3f.UNIT_Y);
        OpaqueComparator comparator = new OpaqueComparator(); comparator.setCamera(camera);
        camera.lookAtDirection(Vector3f.UNIT_X, Vector3f.UNIT_Y);
        Geometry geometry = new Geometry("fresh", new com.jme3.scene.Mesh());
        geometry.setLocalTranslation(3, 0, 8); geometry.updateGeometricState();
        assertEquals(3f, comparator.distanceToCam(geometry), .00001f);
    }

    @Test
    void projectedDistanceMatchesLegacyArithmeticAndCacheSentinels() {
        Camera camera = new Camera(1, 1);
        OpaqueComparator comparator = new OpaqueComparator(); comparator.setCamera(camera);
        java.util.Random random = new java.util.Random(715991);
        for (int i = 0; i < 128; i++) {
            camera.setLocation(new Vector3f(random.nextFloat()*20-10, random.nextFloat()*20-10, random.nextFloat()*20-10));
            camera.lookAtDirection(new Vector3f(random.nextFloat()+.1f, random.nextFloat()+.1f, random.nextFloat()+.1f), Vector3f.UNIT_Y);
            Geometry geometry = new Geometry("fresh", new com.jme3.scene.Mesh());
            geometry.setLocalTranslation(random.nextFloat()*30-15, random.nextFloat()*30-15, random.nextFloat()*30-15);
            geometry.updateGeometricState();
            float expected = geometry.getWorldTranslation().subtract(camera.getLocation(), new Vector3f()).dot(camera.getDirection(new Vector3f()));
            assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(comparator.distanceToCam(geometry)));
            camera.setLocation(new Vector3f(100, 200, 300));
            assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(comparator.distanceToCam(geometry)));
            geometry.queueDistance = Float.NaN;
            assertEquals(Float.floatToIntBits(Float.NaN), Float.floatToIntBits(comparator.distanceToCam(geometry)));
        }
    }

    private static Geometry geometry(String name, float z, Material material) {
        Geometry result = new Geometry(name, new Box(1f, 1f, 1f));
        result.setMaterial(material);
        result.setModelBound(new BoundingBox(Vector3f.ZERO, 1f, 1f, 1f));
        result.setLocalTranslation(0f, 0f, z);
        result.updateGeometricState();
        return result;
    }
}
