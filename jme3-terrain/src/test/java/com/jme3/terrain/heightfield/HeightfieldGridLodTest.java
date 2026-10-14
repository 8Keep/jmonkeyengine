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
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeightfieldGridLodTest {
    @Test void nodeWrappersShareTopologyButKeepPageRelativeDisplacedBounds() {
        HeightfieldHierarchy hierarchy = new HeightfieldHierarchy(page(), 16, 0);
        HeightfieldHierarchy.Node east = hierarchy.getRoot().getChildren().get(1);
        HeightfieldHierarchy.Node south = hierarchy.getRoot().getChildren().get(2);
        HeightfieldGrid grid = new HeightfieldGrid(16);
        Mesh a = grid.createLodPatchMesh(east);
        Mesh b = grid.createLodPatchMesh(south);
        assertSame(a.getBuffer(VertexBuffer.Type.Position), b.getBuffer(VertexBuffer.Type.Position));
        assertSame(a.getBuffer(VertexBuffer.Type.Index), b.getBuffer(VertexBuffer.Type.Index));
        assertEquals(17 * 17, a.getVertexCount());
        assertEquals(16 * 16 * 2, a.getTriangleCount());
        assertNotSame(a.getBound(), b.getBound());
        BoundingBox bound = (BoundingBox) a.getBound();
        HeightfieldPage page = hierarchy.getPage();
        for (int z = east.getOriginZ(); z <= east.getOriginZ() + east.getSpan(); z++) {
            for (int x = east.getOriginX(); x <= east.getOriginX() + east.getSpan(); x++) {
                assertTrue(bound.intersects(new Vector3f(x * page.getSampleSpacing(),
                        page.getHeight(x, z), z * page.getSampleSpacing())));
            }
        }
        assertTrue(bound.getCenter().x > 3f, "bounds must include the node offset within its source page");
        Vector3f before = east.getBound().getCenter().clone();
        bound.setCenter(new Vector3f());
        assertEquals(before, east.getBound().getCenter(), "wrapper mutation must not alter hierarchy bounds");
    }

    @Test void wrongTopologyAndMissingNodeAreRejectedBeforeDrawing() {
        HeightfieldHierarchy hierarchy = new HeightfieldHierarchy(page(), 16, 0);
        HeightfieldGrid grid = new HeightfieldGrid(8);
        assertThrows(IllegalArgumentException.class, () -> grid.createLodPatchMesh(hierarchy.getRoot()));
        assertThrows(IllegalArgumentException.class, () -> grid.createLodPatchMesh(null));
    }

    private static HeightfieldPage page() {
        int cells = 64;
        int side = cells + 3;
        float[] samples = new float[side * side];
        for (int z = -1; z <= cells + 1; z++) {
            for (int x = -1; x <= cells + 1; x++) samples[(z + 1) * side + x + 1] = 2 * x - z;
        }
        return new HeightfieldPage(cells, .1f, samples);
    }
}
