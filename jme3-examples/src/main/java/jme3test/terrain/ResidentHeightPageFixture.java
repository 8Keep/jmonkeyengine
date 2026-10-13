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
package jme3test.terrain;

import com.jme3.math.Vector3f;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Builds deterministic, asset-free data for the resident height-page example.
 */
final class ResidentHeightPageFixture {

    static final int CELLS = 16;
    static final int HALO = 1;
    static final int TEXTURE_SIZE = 19;
    static final int PATCH_X = 6;
    static final int PATCH_Z = 6;
    static final int PATCH_WIDTH = 3;
    static final int PATCH_HEIGHT = 4;

    private ResidentHeightPageFixture() {
    }

    static float height(int layer, int x, int z, boolean edited) {
        if (x < -HALO || x > CELLS + HALO || z < -HALO || z > CELLS + HALO) {
            throw new IllegalArgumentException("Height coordinates must include at most one halo texel");
        }
        int globalX = layer * CELLS + x;
        float result = 1.5f + 0.015f * globalX + 0.025f * z
                + 0.55f * (float) (Math.sin(0.18 * globalX) * Math.cos(0.22 * z));
        if (edited && layer == 0 && x >= PATCH_X && x < PATCH_X + PATCH_WIDTH
                && z >= PATCH_Z && z < PATCH_Z + PATCH_HEIGHT) {
            result += 0.75f;
        }
        return result;
    }

    static Image page(int layer, boolean edited) {
        ByteBuffer data = BufferUtils.createByteBuffer(TEXTURE_SIZE * TEXTURE_SIZE * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (int z = 0; z < TEXTURE_SIZE; z++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                data.putFloat(height(layer, x - HALO, z - HALO, edited));
            }
        }
        data.flip();
        return new Image(Image.Format.Luminance32F, TEXTURE_SIZE, TEXTURE_SIZE, data, ColorSpace.Linear);
    }

    static Image editPatch() {
        ByteBuffer data = BufferUtils.createByteBuffer(PATCH_WIDTH * PATCH_HEIGHT * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (int z = 0; z < PATCH_HEIGHT; z++) {
            for (int x = 0; x < PATCH_WIDTH; x++) {
                data.putFloat(height(0, PATCH_X + x, PATCH_Z + z, true));
            }
        }
        data.flip();
        return new Image(Image.Format.Luminance32F, PATCH_WIDTH, PATCH_HEIGHT, data, ColorSpace.Linear);
    }

    static Mesh grid() {
        return mesh(0, false, false);
    }

    static Mesh cpuReference(int layer, boolean edited) {
        return mesh(layer, edited, true);
    }

    static Vector3f normal(int layer, int x, int z, boolean edited) {
        float left = height(layer, x - 1, z, edited);
        float right = height(layer, x + 1, z, edited);
        float back = height(layer, x, z - 1, edited);
        float front = height(layer, x, z + 1, edited);
        return new Vector3f(-(right - left) * 0.5f, 1f, -(front - back) * 0.5f).normalizeLocal();
    }

    static void verify() {
        for (int layer = 0; layer <= 1; layer++) {
            for (int z = 0; z <= CELLS; z++) {
                for (int x = -HALO; x <= CELLS + HALO; x++) {
                    float value = height(layer, x, z, false);
                    if (Float.isNaN(value) || Float.isInfinite(value)) {
                        throw new IllegalStateException("Non-finite height in fixture");
                    }
                }
            }
        }
        for (int z = 0; z <= CELLS; z++) {
            float leftHeight = height(0, CELLS, z, false);
            float rightHeight = height(1, 0, z, false);
            if (leftHeight != rightHeight) {
                throw new IllegalStateException("Adjacent pages disagree at their shared edge");
            }
            Vector3f leftNormal = normal(0, CELLS, z, false);
            Vector3f rightNormal = normal(1, 0, z, false);
            if (!leftNormal.equals(rightNormal)) {
                throw new IllegalStateException("Adjacent page normals disagree at their shared edge");
            }
            if (height(0, CELLS, z, true) != leftHeight
                    || height(1, 0, z, true) != rightHeight
                    || !normal(0, CELLS, z, true).equals(leftNormal)
                    || !normal(1, 0, z, true).equals(rightNormal)) {
                throw new IllegalStateException("Edit changed the page boundary");
            }
        }
        for (int layer = 0; layer <= 1; layer++) {
            for (int z = 0; z < TEXTURE_SIZE; z++) {
                for (int x = 0; x < TEXTURE_SIZE; x++) {
                    float original = height(layer, x - HALO, z - HALO, false);
                    float edited = height(layer, x - HALO, z - HALO, true);
                    boolean inPatch = layer == 0 && x - HALO >= PATCH_X
                            && x - HALO < PATCH_X + PATCH_WIDTH && z - HALO >= PATCH_Z
                            && z - HALO < PATCH_Z + PATCH_HEIGHT;
                    if (Float.isNaN(original) || Float.isInfinite(original)
                            || (inPatch ? edited != original + 0.75f : edited != original)) {
                        throw new IllegalStateException("Edit or halo coverage is inconsistent");
                    }
                }
            }
            ByteBuffer samples = page(layer, false).getData(0).order(ByteOrder.nativeOrder());
            for (int z = 0; z < TEXTURE_SIZE; z++) {
                for (int x = 0; x < TEXTURE_SIZE; x++) {
                    float expected = height(layer, x - HALO, z - HALO, false);
                    float stored = samples.getFloat((z * TEXTURE_SIZE + x) * Float.BYTES);
                    if (stored != expected) {
                        throw new IllegalStateException("Page image does not contain its complete halo");
                    }
                }
            }
        }
        for (int z = 0; z <= CELLS; z++) {
            for (int x = 0; x <= CELLS; x++) {
                Vector3f n = normal(0, x, z, false);
                if (Float.isNaN(n.x) || Float.isInfinite(n.x) || Float.isNaN(n.y)
                        || Float.isInfinite(n.y) || Float.isNaN(n.z) || Float.isInfinite(n.z)
                        || Math.abs(n.length() - 1f) > 1e-5f) {
                    throw new IllegalStateException("Reference normal is not finite and unit length");
                }
            }
        }
    }

    private static Mesh mesh(int layer, boolean edited, boolean withHeights) {
        int side = CELLS + 1;
        float[] positions = new float[side * side * 3];
        float[] normals = new float[side * side * 3];
        short[] indices = new short[CELLS * CELLS * 6];
        int vertex = 0;
        for (int z = 0; z <= CELLS; z++) {
            for (int x = 0; x <= CELLS; x++) {
                int offset = vertex * 3;
                positions[offset] = x;
                positions[offset + 1] = withHeights ? height(layer, x, z, edited) : 0f;
                positions[offset + 2] = z;
                Vector3f n = withHeights ? normal(layer, x, z, edited) : Vector3f.UNIT_Y;
                normals[offset] = n.x;
                normals[offset + 1] = n.y;
                normals[offset + 2] = n.z;
                vertex++;
            }
        }
        int index = 0;
        for (int z = 0; z < CELLS; z++) {
            for (int x = 0; x < CELLS; x++) {
                short a = (short) (z * side + x);
                short b = (short) (a + 1);
                short c = (short) (a + side);
                short d = (short) (c + 1);
                // Counterclockwise from above, matching the upward normals.
                indices[index++] = a;
                indices[index++] = d;
                indices[index++] = b;
                indices[index++] = a;
                indices[index++] = c;
                indices[index++] = d;
            }
        }
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, positions);
        mesh.setBuffer(VertexBuffer.Type.Normal, 3, normals);
        mesh.setBuffer(VertexBuffer.Type.Index, 3, indices);
        mesh.updateBound();
        mesh.setStatic();
        return mesh;
    }
}
