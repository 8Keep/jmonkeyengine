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

import com.jme3.renderer.Caps;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Type;

/**
 * Reusable immutable integer-lattice topology for GPU-displaced height pages.
 * Per-patch Mesh wrappers share the same VertexBuffers/GPU allocations but own
 * independent displaced bounds. Use Heightfield.j3md and match SampleSpacing
 * and HeightLayer to the page. Do not mutate shared buffers or recompute flat
 * model bounds. Mesh ray collision sees the flat lattice, not GPU heights;
 * callers must query their authoritative height field for picking/collision.
 */
public final class HeightfieldGrid {
    private final int cells;
    private final Mesh topology;

    public HeightfieldGrid(int cells) {
        if (cells < 2 || cells > 256) {
            throw new IllegalArgumentException("cells must be between 2 and 256");
        }
        this.cells = cells;
        int side = cells + 1;
        float[] positions = new float[side * side * 3];
        for (int z = 0; z <= cells; z++) {
            for (int x = 0; x <= cells; x++) {
                int vertex = (z * side + x) * 3;
                positions[vertex] = x;
                positions[vertex + 2] = z;
            }
        }
        int[] indices = new int[cells * cells * 6];
        int next = 0;
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                int a = z * side + x;
                int b = a + 1;
                int c = a + side;
                int d = c + 1;
                indices[next++] = a;
                indices[next++] = d;
                indices[next++] = b;
                indices[next++] = a;
                indices[next++] = c;
                indices[next++] = d;
            }
        }
        topology = new Mesh();
        topology.setBuffer(Type.Position, 3, positions);
        topology.setBuffer(Type.Index, 3, indices);
        topology.setStatic();
    }

    public Mesh createPatchMesh(HeightfieldPage page) {
        if (page == null || page.getCells() != cells) {
            throw new IllegalArgumentException("page dimensions must match the shared topology");
        }
        Mesh patch = new Mesh();
        for (VertexBuffer buffer : topology.getBufferList().getArray()) {
            patch.setBuffer(buffer);
        }
        patch.setBound(page.createBound());
        return patch;
    }

    /** Check the supported GPU path once before attaching displaced geometry. */
    public static void requireSupported(Renderer renderer) {
        if (!renderer.getCaps().contains(Caps.TextureArray)
                || !renderer.getCaps().contains(Caps.VertexTextureFetch)
                || !renderer.getCaps().contains(Caps.FloatTexture)
                || !(renderer.getCaps().contains(Caps.OpenGLES30)
                || renderer.getCaps().contains(Caps.GLSL150))) {
            throw new UnsupportedOperationException("GPU heightfields require GL3/ES3 array vertex fetch and float textures");
        }
    }
}
