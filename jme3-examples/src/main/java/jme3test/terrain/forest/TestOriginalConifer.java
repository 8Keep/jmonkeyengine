/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
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
package jme3test.terrain.forest;

import com.jme3.bounding.BoundingBox;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Finite headless checks for the original conifer mesh data contract. */
public final class TestOriginalConifer {
    private TestOriginalConifer() {}

    public static void main(String[] args) {
        long[] seeds = {17, 2026};
        for (long seed : seeds) {
            OriginalConifer.Asset first = OriginalConifer.build(seed);
            OriginalConifer.Asset repeat = OriginalConifer.build(seed);
            OriginalConifer.Asset other = OriginalConifer.build(seed + 1);
            check(first.getSeed() == seed, "seed retained");
            checkSameMesh(first.getNearMesh(), repeat.getNearMesh(), "near deterministic");
            checkSameMesh(first.getMidMesh(), repeat.getMidMesh(), "mid deterministic");
            check(!sameBuffer(first.getNearMesh(), other.getNearMesh(), VertexBuffer.Type.Position),
                    "seed changes placement");
            checkCommonBounds(first.getBounds(), repeat.getBounds());
            inspect(first.getNearMesh(), first.getBounds(), 1000, 6000, "near");
            inspect(first.getMidMesh(), first.getBounds(), 450, 2500, "mid");
            check(first.getNearMesh().getTriangleCount() > first.getMidMesh().getTriangleCount(),
                    "near crown spends more geometry on overlapping sprays than mid");
            print(seed, "near", first.getNearMesh());
            print(seed, "mid", first.getMidMesh());
        }
    }

    private static void inspect(Mesh mesh, BoundingBox envelope,
            int minTriangles, int maxTriangles, String label) {
        int vertices = mesh.getVertexCount();
        check(vertices > 0, label + " vertices");
        check(mesh.getTriangleCount() >= minTriangles && mesh.getTriangleCount() <= maxTriangles,
                label + " triangle budget: " + mesh.getTriangleCount());
        FloatBuffer positions = floats(mesh, VertexBuffer.Type.Position);
        FloatBuffer normals = floats(mesh, VertexBuffer.Type.Normal);
        FloatBuffer uv = floats(mesh, VertexBuffer.Type.TexCoord);
        FloatBuffer colors = floats(mesh, VertexBuffer.Type.Color);
        check(positions.limit() == vertices * 3, label + " position shape");
        check(normals.limit() == vertices * 3, label + " normal shape");
        check(uv.limit() == vertices * 3, label + " UV.z material tag shape");
        check(colors.limit() == vertices * 4, label + " vertex color shape");
        IntBuffer indices = (IntBuffer) mesh.getBuffer(VertexBuffer.Type.Index).getData();
        check(indices.limit() % 3 == 0, label + " triangle index count");
        for (int i = 0; i < indices.limit(); i++) {
            int index = indices.get(i);
            check(index >= 0 && index < vertices, label + " index range");
        }
        checkWinding(positions, normals, indices, label);
        boolean wood = false, foliage = false;
        float foliageMinY = Float.POSITIVE_INFINITY, foliageMaxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vertices; i++) {
            float x = positions.get(i * 3), y = positions.get(i * 3 + 1), z = positions.get(i * 3 + 2);
            float nx = normals.get(i * 3), ny = normals.get(i * 3 + 1), nz = normals.get(i * 3 + 2);
            float u = uv.get(i * 3), v = uv.get(i * 3 + 1), tag = uv.get(i * 3 + 2);
            float alpha = colors.get(i * 4 + 3);
            check(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z), label + " finite position");
            check(Float.isFinite(nx) && Float.isFinite(ny) && Float.isFinite(nz), label + " finite normal");
            check(Math.abs(nx * nx + ny * ny + nz * nz - 1f) < 2e-4f, label + " unit normal");
            check(Float.isFinite(u) && Float.isFinite(v), label + " finite UV");
            check(tag == 0f || tag == 1f, label + " UV.z tag");
            check(alpha == 1f, label + " opaque vertex color");
            check(y >= 0f, label + " no geometry below ground");
            check(Math.abs(x) <= envelope.getXExtent() + 1e-4f
                    && Math.abs(z) <= envelope.getZExtent() + 1e-4f
                    && y <= OriginalConifer.HEIGHT + 1e-4f, label + " within shared envelope");
            if (tag == 0f) wood = true;
            else {
                foliage = true;
                foliageMinY = Math.min(foliageMinY, y);
                foliageMaxY = Math.max(foliageMaxY, y);
            }
        }
        check(wood && foliage, label + " contains wood and fronds");
        check(foliageMinY <= 2.0f, label + " foliage fills the lower crown");
        check(foliageMaxY >= 17.5f, label + " foliage reaches the tapered leader");
        check(mesh.getBound() instanceof BoundingBox, label + " mesh bound");
        BoundingBox meshBounds = (BoundingBox) mesh.getBound();
        check(Float.isFinite(meshBounds.getMin(null).x) && Float.isFinite(meshBounds.getMin(null).y)
                && Float.isFinite(meshBounds.getMin(null).z) && Float.isFinite(meshBounds.getMax(null).x)
                && Float.isFinite(meshBounds.getMax(null).y) && Float.isFinite(meshBounds.getMax(null).z),
                label + " finite bounds");
        check(meshBounds.getMin(null).y >= -1e-5f, label + " bound above ground");
    }

    private static void checkWinding(FloatBuffer positions, FloatBuffer normals,
            IntBuffer indices, String label) {
        for (int i = 0; i < indices.limit(); i += 3) {
            int a = indices.get(i), b = indices.get(i + 1), c = indices.get(i + 2);
            float ax = positions.get(3 * a), ay = positions.get(3 * a + 1), az = positions.get(3 * a + 2);
            float abx = positions.get(3 * b) - ax, aby = positions.get(3 * b + 1) - ay;
            float abz = positions.get(3 * b + 2) - az;
            float acx = positions.get(3 * c) - ax, acy = positions.get(3 * c + 1) - ay;
            float acz = positions.get(3 * c + 2) - az;
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            float sx = normals.get(3 * a) + normals.get(3 * b) + normals.get(3 * c);
            float sy = normals.get(3 * a + 1) + normals.get(3 * b + 1) + normals.get(3 * c + 1);
            float sz = normals.get(3 * a + 2) + normals.get(3 * b + 2) + normals.get(3 * c + 2);
            check(nx * sx + ny * sy + nz * sz > 0f, label + " triangle winding agrees with normals");
        }
    }

    private static void checkCommonBounds(BoundingBox a, BoundingBox b) {
        check(a.getCenter().equals(b.getCenter()), "bounds aligned");
        check(a.getXExtent() == b.getXExtent() && a.getYExtent() == b.getYExtent()
                && a.getZExtent() == b.getZExtent(), "near/mid share envelope");
        check(a.getCenter().y - a.getYExtent() == 0f, "base pivot at ground");
        check(a.getCenter().y + a.getYExtent() == OriginalConifer.HEIGHT, "18m envelope");
    }

    private static void checkSameMesh(Mesh a, Mesh b, String label) {
        for (VertexBuffer.Type type : new VertexBuffer.Type[]{VertexBuffer.Type.Position,
                VertexBuffer.Type.Normal, VertexBuffer.Type.TexCoord, VertexBuffer.Type.Color,
                VertexBuffer.Type.Index}) {
            check(sameBuffer(a, b, type), label + " " + type);
        }
    }

    private static boolean sameBuffer(Mesh a, Mesh b, VertexBuffer.Type type) {
        VertexBuffer av = a.getBuffer(type), bv = b.getBuffer(type);
        if (av == null || bv == null || av.getNumElements() != bv.getNumElements()) return false;
        if (type == VertexBuffer.Type.Index) {
            IntBuffer x = (IntBuffer) av.getData(), y = (IntBuffer) bv.getData();
            if (x.limit() != y.limit()) return false;
            for (int i = 0; i < x.limit(); i++) if (x.get(i) != y.get(i)) return false;
        } else {
            FloatBuffer x = (FloatBuffer) av.getData(), y = (FloatBuffer) bv.getData();
            if (x.limit() != y.limit()) return false;
            for (int i = 0; i < x.limit(); i++) {
                if (Float.floatToIntBits(x.get(i)) != Float.floatToIntBits(y.get(i))) return false;
            }
        }
        return true;
    }

    private static FloatBuffer floats(Mesh mesh, VertexBuffer.Type type) {
        return (FloatBuffer) mesh.getBuffer(type).getData();
    }

    private static void print(long seed, String label, Mesh mesh) {
        BoundingBox b = (BoundingBox) mesh.getBound();
        System.out.printf("seed=%d lod=%s vertices=%d triangles=%d bounds=[%.3f..%.3f, %.3f..%.3f, %.3f..%.3f]%n",
                seed, label, mesh.getVertexCount(), mesh.getTriangleCount(),
                b.getMin(null).x, b.getMax(null).x, b.getMin(null).y, b.getMax(null).y,
                b.getMin(null).z, b.getMax(null).z);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
