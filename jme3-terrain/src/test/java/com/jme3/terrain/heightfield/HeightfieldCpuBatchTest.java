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
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
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
import com.jme3.renderer.Camera;
import com.jme3.system.NullRenderer;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.util.BufferUtils;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HeightfieldCpuBatchTest {

    @Test
    void remapsOnePatchAndKeepsSharedTopologyAndPriorBatchOnInvalidUpdate() {
        HeightfieldHierarchy hierarchy = hierarchy();
        HeightfieldGrid grid = new HeightfieldGrid(16);
        NullRenderer renderer = new NullRenderer();
        renderer.initialize();
        try (HeightfieldCpuBatch batch = new HeightfieldCpuBatch(renderer, hierarchy, grid, 8)) {
            assertEquals(0, batch.getInstanceCount(), "no records are published before the first update");
            assertEquals(1, batch.getMesh().getInstanceCount(),
                    "Mesh applies the renderer's one-instance minimum even with an empty ordinal buffer");
            List<HeightfieldLod.Patch> rootOnly = new HeightfieldLod.Transition(hierarchy).getPatches();
            batch.update(rootOnly, node -> 3);

            assertEquals(1, batch.getInstanceCount());
            assertEquals(5456, batch.getRecordBufferBytes());
            assertEquals(1360, batch.getPageBindingBufferBytes());
            assertEquals(1, batch.getMesh().getBuffer(VertexBuffer.Type.Size).getData().limit());
            assertSame(grid.createLodPatchMesh(hierarchy.getRoot()).getBuffer(VertexBuffer.Type.Position),
                    batch.getMesh().getBuffer(VertexBuffer.Type.Position));
            assertNotSame(hierarchy.getRoot().getBound(), batch.getMesh().getBound());

            FloatBuffer records = batch.getPatchRecords().getData()
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            int first = 4; // UBO header occupies the first vec4.
            assertEquals(0, records.get(first));
            assertEquals(hierarchy.getRoot().getOriginX(), records.get(first + 1));
            assertEquals(hierarchy.getRoot().getOriginZ(), records.get(first + 2));
            assertEquals(hierarchy.getRoot().getStride(), records.get(first + 3));
            assertEquals(hierarchy.getRoot().getSpan(), records.get(first + 4));

            IntBuffer bindings = batch.getPageBindings().getData()
                    .order(ByteOrder.nativeOrder()).asIntBuffer();
            assertEquals(3, bindings.get(0));
            assertEquals(3, bindings.get(1), "CPU parent gradients use the patch's own canonical page");
            assertEquals(0, bindings.get(2));
            assertEquals(0, bindings.get(3));

            BufferObject before = batch.getPatchRecords();
            assertThrows(IllegalArgumentException.class,
                    () -> batch.update(rootOnly, node -> -1));
            assertSame(before, batch.getPatchRecords());
            assertEquals(1, batch.getInstanceCount());
            assertEquals(3, batch.getPageBindings().getData()
                    .order(ByteOrder.nativeOrder()).asIntBuffer().get(0));
            assertThrows(IllegalArgumentException.class,
                    () -> batch.update(Collections.emptyList(), node -> 0));
            assertThrows(IllegalArgumentException.class,
                    () -> new HeightfieldCpuBatch(renderer, hierarchy, new HeightfieldGrid(8), 8));

            List<HeightfieldLod.Patch> split = splitPatches(hierarchy);
            assertEquals(4, split.size());
            batch.update(split, node -> node.getIndex() % 8);
            assertEquals(4, batch.getInstanceCount());
            batch.update(rootOnly, node -> 3);
            assertEquals(1, batch.getInstanceCount());
            batch.update(split, node -> node.getIndex() % 8);
            assertEquals(4, batch.getInstanceCount(), "ordinal buffer limit must grow after a shrink");
            batch.getPatchRecords().clearUpdateNeeded();
            batch.getPageBindings().clearUpdateNeeded();
            batch.getMesh().getBuffer(VertexBuffer.Type.Size).clearUpdateNeeded();
            batch.update(split, node -> node.getIndex() % 8);
            assertFalse(batch.getPatchRecords().isUpdateNeeded());
            assertFalse(batch.getPageBindings().isUpdateNeeded());
            assertFalse(batch.getMesh().getBuffer(VertexBuffer.Type.Size).isUpdateNeeded());
        }
    }

    @Test
    void morphRecordStoresSouthBeforeNorthAsShaderExpects() {
        FloatBuffer record = BufferUtils.createFloatBuffer(16);
        HeightfieldCpuBatch.writeMorphRecord(record, 0, .1f, .2f, .3f, .4f, .5f);
        assertEquals(.1f, record.get(8), 0f);
        assertEquals(.2f, record.get(9), 0f);
        assertEquals(.3f, record.get(10), 0f);
        assertEquals(.4f, record.get(11), 0f);
        assertEquals(.5f, record.get(12), 0f);
    }

    private static List<HeightfieldLod.Patch> splitPatches(HeightfieldHierarchy hierarchy) {
        Camera camera = new Camera(800, 600);
        camera.setFrustumPerspective(60, 800f / 600, .1f, 1000);
        camera.setLocation(new Vector3f(32, 45, 32));
        camera.lookAt(new Vector3f(32, 0, 32), Vector3f.UNIT_Y);
        HeightfieldLod lod = new HeightfieldLod(hierarchy);
        HeightfieldLod.Selection desired = lod.select(camera, 1, 256, node -> true);
        HeightfieldLod.Transition transition = new HeightfieldLod.Transition(hierarchy);
        transition.advance(desired, 0);
        return transition.getPatches();
    }

    private static HeightfieldHierarchy hierarchy() {
        int cells = 64;
        int side = cells + 3;
        float[] samples = new float[side * side];
        for (int z = -1; z <= cells + 1; z++) {
            for (int x = -1; x <= cells + 1; x++) {
                samples[(z + 1) * side + x + 1] = x == 32 && z == 32 ? 100f : 0f;
            }
        }
        return new HeightfieldHierarchy(new HeightfieldPage(cells, .5f, samples), 16, 0);
    }
}
