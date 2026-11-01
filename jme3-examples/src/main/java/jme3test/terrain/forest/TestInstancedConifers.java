/*
 * Copyright (c) 2009-2026 jMonkeyEngine
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
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDER AND CONTRIBUTORS "AS IS"
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
package jme3test.terrain.forest;

import com.jme3.material.Material;
import com.jme3.material.MaterialDef;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.VarType;
import com.jme3.system.NullRenderer;
import java.nio.FloatBuffer;

/** Finite CPU checks for grouping, growth, LOD swaps, and cleanup contracts. */
public final class TestInstancedConifers {
    private TestInstancedConifers() {}

    public static void main(String[] args) {
        NullRenderer renderer = new NullRenderer();
        renderer.initialize();
        MaterialDef definition = new MaterialDef(null, "conifer-instancing-test");
        definition.addMaterialParam(VarType.Boolean, "UseConiferInstances", false);
        definition.addMaterialParam(VarType.Boolean, "UseConiferLodFade", false);
        Material template = new Material(definition);
        Node parent = new Node("conifer instances");
        InstancedConifers batch = new InstancedConifers(renderer,
                OriginalConifer.build(41), template, parent, 16);
        try {
            float[] five = {
                1, 2, 3, 1, 1, 0, .8f,
                2, 2, 3, 1, 1, 0, .9f,
                3, 2, 3, 1, 1, 0, 1f,
                4, 2, 3, 1, 1, 0, .7f,
                5, 2, 3, 1, 1, 0, .6f
            };
            batch.update(five, new int[]{0, 0, 0, 0, 0}, 5);
            check(batch.getInstanceCount() == 5, "instance count after growth");
            check(batch.getGroupCount() == 1 && parent.getQuantity() == 1, "one 128m cell group");
            Geometry geometry = (Geometry) parent.getChild(0);
            check(geometry.getQueueBucket() == RenderQueue.Bucket.Opaque, "opaque queue bucket");
            check(geometry.getShadowMode() == RenderQueue.ShadowMode.CastAndReceive, "casts and receives shadows");
            checkRecords(geometry, five, 5);
            checkFades(geometry, new float[]{1, 1, 1, 1, 1}, 5);
            long grownBytes = batch.getInstanceBufferBytes();

            float[] split = {
                1, 2, 3, 1, 1, 0, .8f,
                2, 2, 3, 1, 1, 0, .9f,
                129, 2, 3, 1, 0, 1, .75f
            };
            batch.update(split, new int[]{1, 1, 0}, 3);
            check(batch.getGroupCount() == 2 && parent.getQuantity() == 2, "split across cells and LODs");
            check(batch.getInstanceCount() == 3, "count after LOD and cell update");
            check(batch.getInstanceBufferBytes() >= grownBytes, "buffer capacity retained across reuse");

            float[] weighted = {
                1, 2, 3, 1, 1, 0, .8f,
                2, 2, 3, 1, 1, 0, .9f,
                3, 2, 3, 1, 1, 0, 1f,
                4, 2, 3, 1, 1, 0, .7f,
                5, 2, 3, 1, 1, 0, .6f,
                6, 2, 3, 1, 1, 0, .5f,
                7, 2, 3, 1, 1, 0, .4f,
                8, 2, 3, 1, 1, 0, .3f,
                9, 2, 3, 1, 1, 0, .2f
            };
            float[] weights = {0f, .125f, .25f, .375f, .5f, .625f, .75f, .875f, 1f};
            batch.update(weighted, new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0}, weights, 9);
            check(Boolean.TRUE.equals(batch.getMaterial().getParam("UseConiferLodFade").getValue()),
                    "weighted update enables mesh fade variant");
            geometry = (Geometry) parent.getChild(0);
            checkFades(geometry, weights, 9);
            long weightedBytes = batch.getInstanceBufferBytes();

            boolean rejected = false;
            try {
                batch.update(weighted, new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0},
                        new float[]{1, 1, 1, 1, 1, 1, 1, 1, Float.NaN}, 9);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "non-finite fade weight rejected");
            check(batch.getInstanceCount() == 9 && batch.getInstanceBufferBytes() == weightedBytes,
                    "invalid fade update leaves published set unchanged");

            batch.update(new float[0], new int[0], 0);
            check(Boolean.FALSE.equals(batch.getMaterial().getParam("UseConiferLodFade").getValue()),
                    "legacy update restores mesh fade variant");
            check(batch.getInstanceCount() == 0 && batch.getGroupCount() == 0 && parent.getQuantity() == 0,
                    "empty update detaches all groups");
            check(batch.getInstanceBufferBytes() == grownBytes || batch.getInstanceBufferBytes() > grownBytes,
                    "empty update retains growable buffers");
        } finally {
            batch.close();
        }
        check(parent.getQuantity() == 0, "close detaches all groups");
        check(batch.getInstanceBufferBytes() == 0, "close releases owned buffers");
        System.out.println("INSTANCED_CONIFERS_CPU_PASS");
    }

    private static void checkRecords(Geometry geometry, float[] source, int count) {
        FloatBuffer positions = (FloatBuffer) geometry.getMesh().getBuffer(VertexBuffer.Type.TexCoord2).getData();
        FloatBuffer rotations = (FloatBuffer) geometry.getMesh().getBuffer(VertexBuffer.Type.TexCoord3).getData();
        check(positions.limit() == count * 4, "position/scale data length");
        check(rotations.limit() == count * 3, "yaw/tint data length");
        for (int i = 0; i < count; i++) {
            int sourceOffset = i * 7;
            int p = i * 4;
            int r = i * 3;
            check(positions.get(p) == source[sourceOffset]
                    && positions.get(p + 1) == source[sourceOffset + 1]
                    && positions.get(p + 2) == source[sourceOffset + 2]
                    && positions.get(p + 3) == source[sourceOffset + 3], "preserved position growth record " + i);
            check(rotations.get(r) == source[sourceOffset + 4]
                    && rotations.get(r + 1) == source[sourceOffset + 5]
                    && rotations.get(r + 2) == source[sourceOffset + 6], "preserved yaw growth record " + i);
        }
    }

    private static void checkFades(Geometry geometry, float[] source, int count) {
        FloatBuffer fades = (FloatBuffer) geometry.getMesh().getBuffer(VertexBuffer.Type.Size).getData();
        check(fades.limit() == count, "fade data length");
        for (int i = 0; i < count; i++) {
            check(fades.get(i) == source[i], "fade weight record " + i);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
