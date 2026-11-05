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
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without
 *   specific prior written permission.
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
package com.jme3.scene;

import com.jme3.bounding.BoundingBox;
import com.jme3.collision.CollisionResults;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.math.Ray;
import com.jme3.math.Vector3f;
import com.jme3.scene.instancing.IndirectGeometry;
import com.jme3.scene.shape.Box;
import com.jme3.shader.bufferobject.BufferObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IndirectGeometryTest {
    @Test
    void explicitBoundsFollowTransformsWithoutPickingTheSourceMesh() {
        Mesh mesh = new Box(1, 1, 1);
        BoundingBox supplied = new BoundingBox(Vector3f.ZERO, 10, 20, 30);
        IndirectGeometry geometry = new IndirectGeometry("batch", mesh, new BufferObject(), 0, supplied);
        supplied.setXExtent(100);
        geometry.setLocalTranslation(10, 20, 30);
        geometry.setLocalScale(2);
        geometry.updateGeometricState();
        BoundingBox bound = (BoundingBox) geometry.getWorldBound();
        assertEquals(new Vector3f(10, 20, 30), bound.getCenter());
        assertEquals(20f, bound.getXExtent());
        assertEquals(40f, bound.getYExtent());
        assertEquals(60f, bound.getZExtent());
        assertEquals(1f, ((BoundingBox) mesh.getBound()).getXExtent());
        CollisionResults results = new CollisionResults();
        assertEquals(0, geometry.collideWith(new Ray(new Vector3f(10, 20, 0), Vector3f.UNIT_Z), results));
        assertEquals(0, results.size());
        assertThrows(UnsupportedOperationException.class, geometry::clone);
        assertThrows(IOException.class, () -> BinaryExporter.getInstance().save(geometry, new ByteArrayOutputStream()));
    }
}
