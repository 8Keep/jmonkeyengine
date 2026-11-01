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

import com.jme3.bounding.BoundingBox;
import com.jme3.material.Material;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Format;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.VertexBuffer.Usage;
import com.jme3.util.BufferUtils;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Bounded, spatially grouped hardware instances of the original conifer.
 * Packed records contain source-local position, uniform scale, yaw cosine and
 * sine, and a scalar RGB tint. One geometry and one set of instance buffers
 * are used for each active 128-metre cell and LOD.
 */
public final class InstancedConifers implements AutoCloseable {
    public static final float GROUP_SIZE_METRES = 128f;
    private static final int POSITION_COMPONENTS = 4;
    private static final int ROTATION_COMPONENTS = 3;
    private static final float TREE_RADIUS = OriginalConifer.CROWN_RADIUS;
    private static final float TREE_HEIGHT = OriginalConifer.HEIGHT;
    private static final Type[] STATIC_TYPES = {
        Type.Position, Type.Normal, Type.TexCoord, Type.Color, Type.Index
    };

    private final Renderer renderer;
    private final OriginalConifer.Asset asset;
    private final Material material;
    private ConiferImpostor impostor;
    private Material farMaterial;
    private final Node parent;
    private final Group[] groups;
    private final int[] hashSlots;
    private int allocatedGroups;
    private int activeGroups;
    private int activeInstances;
    private long bufferBytes;
    private boolean closed;

    /**
     * Create a bounded batch. The template is cloned exactly once; the clone
     * is configured for instance attributes and shared by all group geometry.
     */
    public InstancedConifers(Renderer renderer, OriginalConifer.Asset sharedAsset,
            Material template, Node parent, int maxInstances) {
        this(renderer, sharedAsset, template, parent, maxInstances, null);
    }

    /** Optional elevation-aware far shape; the caller owns its lifetime. */
    public InstancedConifers(Renderer renderer, OriginalConifer.Asset sharedAsset,
            Material template, Node parent, int maxInstances, ConiferImpostor impostor) {
        if (renderer == null || sharedAsset == null || template == null || parent == null) {
            throw new IllegalArgumentException("renderer, asset, template, and parent are required");
        }
        if (!renderer.getCaps().contains(Caps.MeshInstancing)) {
            throw new IllegalStateException("conifer batches require mesh instancing");
        }
        if (maxInstances < 1 || maxInstances > 1_000_000) {
            throw new IllegalArgumentException("maxInstances must be between 1 and 1000000");
        }
        this.renderer = renderer;
        this.asset = sharedAsset;
        this.parent = parent;
        this.impostor = null;
        farMaterial = null;
        this.groups = new Group[maxInstances];
        int tableSize = 1;
        long wanted = Math.max(2L, (long) maxInstances * 2);
        while (tableSize < wanted) {
            if (tableSize >= (1 << 30)) throw new IllegalArgumentException("instance limit is too large");
            tableSize <<= 1;
        }
        this.hashSlots = new int[tableSize];
        this.material = template.clone();
        this.material.setBoolean("UseConiferInstances", true);
        if (impostor != null) installImpostor(impostor);
    }

    /** Install the shared far asset once. The caller retains ownership of it. */
    public void installImpostor(ConiferImpostor value) {
        ensureOpen();
        if (value == null) throw new IllegalArgumentException("impostor is required");
        if (impostor != null) throw new IllegalStateException("an impostor is already installed");
        impostor = value;
        farMaterial = value.getMaterial().clone();
    }

    /** Return the one cloned material shared by every active forest group. */
    public Material getMaterial() {
        ensureOpen();
        return material;
    }

    /** Dedicated far receiver material, or null when this batch has no far shape. */
    public Material getFarMaterial() { ensureOpen(); return farMaterial; }

    /** Number of instances in the latest update. */
    public int getInstanceCount() { return activeInstances; }

    /** Number of nonempty cell/LOD geometries in the latest update. */
    public int getGroupCount() { return activeGroups; }

    /** GPU instance-buffer capacity in bytes, excluding shared static meshes. */
    public long getInstanceBufferBytes() { return bufferBytes; }

    /**
     * Replace the active set from packed records. Each record is
     * {@code (x,y,z,scale,cosYaw,sinYaw,tint)} and LOD is 0 (near), 1 (mid), or 2 when a far asset was supplied.
     * Buffers and group objects are reused; direct buffers grow only when an
     * active group exceeds its previous high-water count.
     */
    public void update(float[] packed7, int[] lods, int count) {
        update(packed7, lods, null, count);
    }

    /** Replace the active set and attach one fade weight per instance. */
    public void update(float[] packed7, int[] lods, float[] weights, int count) {
        ensureOpen();
        if (packed7 == null || lods == null || count < 0 || count > groups.length
                || packed7.length < (long) count * 7 || lods.length < count) {
            throw new IllegalArgumentException("packed instance arrays do not cover count");
        }
        if (weights != null && weights.length < count) {
            throw new IllegalArgumentException("fade weights do not cover count");
        }
        for (int i = 0, offset = 0; i < count; i++, offset += 7) {
            float x = packed7[offset], y = packed7[offset + 1], z = packed7[offset + 2];
            float scale = packed7[offset + 3];
            float cosYaw = packed7[offset + 4], sinYaw = packed7[offset + 5];
            float tint = packed7[offset + 6];
            validateRecord(x, y, z, scale, cosYaw, sinYaw, tint, lods[i], i);
            if (weights != null && (!Float.isFinite(weights[i]) || weights[i] < 0f || weights[i] > 1f)) {
                throw new IllegalArgumentException("invalid fade weight at record " + i);
            }
        }
        setFadeEnabled(weights != null);

        Arrays.fill(hashSlots, -1);
        for (int i = 0; i < allocatedGroups; i++) groups[i].reset();
        activeGroups = 0;
        activeInstances = 0;

        for (int i = 0, offset = 0; i < count; i++, offset += 7) {
            float x = packed7[offset], y = packed7[offset + 1], z = packed7[offset + 2];
            float scale = packed7[offset + 3];
            float cosYaw = packed7[offset + 4], sinYaw = packed7[offset + 5];
            float tint = packed7[offset + 6];
            int cellX = floorCell(x), cellZ = floorCell(z);
            Group group = findOrCreate(cellX, cellZ, lods[i]);
            group.append(x, y, z, scale, cosYaw, sinYaw, tint, weights == null ? 1f : weights[i]);
            activeInstances++;
        }

        for (int i = 0; i < allocatedGroups; i++) {
            Group group = groups[i];
            if (group.count == 0) {
                if (group.geometry.getParent() != null) parent.detachChild(group.geometry);
                continue;
            }
            activeGroups++;
            group.publish();
            if (group.geometry.getParent() == null) parent.attachChild(group.geometry);
        }
    }

    private Group findOrCreate(int cellX, int cellZ, int lod) {
        int mask = hashSlots.length - 1;
        int slot = mix(cellX, cellZ, lod) & mask;
        while (hashSlots[slot] >= 0) {
            Group existing = groups[hashSlots[slot]];
            if (existing.cellX == cellX && existing.cellZ == cellZ && existing.lod == lod) {
                return existing;
            }
            slot = (slot + 1) & mask;
        }
        Group result = null;
        int index = -1;
        for (int i = 0; i < allocatedGroups; i++) {
            if (groups[i].count == 0) {
                result = groups[i];
                index = i;
                break;
            }
        }
        if (result == null) {
            if (allocatedGroups == groups.length) {
                throw new IllegalStateException("active conifer groups exceed the instance limit");
            }
            index = allocatedGroups++;
            result = new Group();
            groups[index] = result;
        }
        result.configure(cellX, cellZ, lod);
        hashSlots[slot] = index;
        return result;
    }

    private static int mix(int x, int z, int lod) {
        int h = x * 0x9e3779b9;
        h = Integer.rotateLeft(h ^ z * 0x85ebca6b, 13);
        h = Integer.rotateLeft(h ^ lod * 0xc2b2ae35, 11);
        return h ^ (h >>> 16);
    }

    private static int floorCell(float coordinate) {
        double cell = Math.floor(coordinate / GROUP_SIZE_METRES);
        if (cell < Integer.MIN_VALUE || cell > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("instance coordinate exceeds spatial cell range");
        }
        return (int) cell;
    }

    private void validateRecord(float x, float y, float z, float scale,
            float cosYaw, float sinYaw, float tint, int lod, int record) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !Float.isFinite(scale) || scale <= 0
                || !Float.isFinite(cosYaw) || !Float.isFinite(sinYaw)
                || !Float.isFinite(tint) || tint < 0 || lod < 0 || lod > (impostor == null ? 1 : 2)) {
            throw new IllegalArgumentException("invalid conifer instance record " + record);
        }
        float yawLength2 = cosYaw * cosYaw + sinYaw * sinYaw;
        if (Math.abs(yawLength2 - 1f) > .002f) {
            throw new IllegalArgumentException("yaw pair must be normalized at record " + record);
        }
        float radius = lod == 2 ? ConiferImpostor.BOUND_RADIUS * scale
                : TREE_RADIUS * scale * (Math.abs(cosYaw) + Math.abs(sinYaw));
        float bottom = lod == 2 ? y + (ConiferImpostorFrames.CENTER_Y - ConiferImpostor.BOUND_RADIUS) * scale : y;
        float top = lod == 2 ? y + (ConiferImpostorFrames.CENTER_Y + ConiferImpostor.BOUND_RADIUS) * scale : y + TREE_HEIGHT * scale;
        if (!Float.isFinite(radius) || !Float.isFinite(x - radius) || !Float.isFinite(x + radius)
                || !Float.isFinite(z - radius) || !Float.isFinite(z + radius)
                || !Float.isFinite(bottom) || !Float.isFinite(top)) {
            throw new IllegalArgumentException("instance bounds overflow at record " + record);
        }
        double cellX = Math.floor((double) x / GROUP_SIZE_METRES);
        double cellZ = Math.floor((double) z / GROUP_SIZE_METRES);
        if (cellX < Integer.MIN_VALUE || cellX > Integer.MAX_VALUE
                || cellZ < Integer.MIN_VALUE || cellZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("instance coordinates exceed group range at record " + record);
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("conifer batch is closed");
    }

    private void setFadeEnabled(boolean enabled) {
        if (material.getMaterialDef().getMaterialParam("UseConiferLodFade") != null) {
            material.setBoolean("UseConiferLodFade", enabled);
        }
        if (farMaterial != null
                && farMaterial.getMaterialDef().getMaterialParam("UseConiferLodFade") != null) {
            farMaterial.setBoolean("UseConiferLodFade", enabled);
        }
    }

    /**
     * Detach groups and release only this batch's instance buffers. The caller
     * must drain the renderer before closing so no queued draw can reference
     * these buffers.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (int i = 0; i < allocatedGroups; i++) {
            Group group = groups[i];
            if (group.geometry.getParent() != null) parent.detachChild(group.geometry);
            group.release();
            groups[i] = null;
        }
        activeGroups = 0;
        activeInstances = 0;
        bufferBytes = 0;
    }

    private final class Group {
        int cellX;
        int cellZ;
        int lod = -1;
        int count;
        int capacity;
        float[] positions;
        float[] rotations;
        float[] fades;
        FloatBuffer positionData;
        FloatBuffer rotationData;
        FloatBuffer fadeData;
        VertexBuffer positionBuffer;
        VertexBuffer rotationBuffer;
        VertexBuffer fadeBuffer;
        Mesh mesh;
        Geometry geometry;
        final BoundingBox bounds = new BoundingBox();
        float minX, minY, minZ, maxX, maxY, maxZ;

        void reset() { count = 0; }

        void configure(int x, int z, int selectedLod) {
            boolean newShape = mesh == null || lod != selectedLod;
            cellX = x;
            cellZ = z;
            lod = selectedLod;
            if (newShape) {
                createShape();
            }
        }

        void createShape() {
            Mesh shared = lod == 2 ? impostor.getMesh() : lod == 0 ? asset.getNearMesh() : asset.getMidMesh();
            if (mesh == null) mesh = new Mesh();
            for (Type type : STATIC_TYPES) {
                mesh.clearBuffer(type);
                if (shared.getBuffer(type) != null) mesh.setBuffer(shared.getBuffer(type));
            }
            if (capacity == 0) allocateCapacity(1);
            if (mesh.getBuffer(Type.TexCoord2) == null) mesh.setBuffer(positionBuffer);
            if (mesh.getBuffer(Type.TexCoord3) == null) mesh.setBuffer(rotationBuffer);
            mesh.clearBuffer(Type.Size);
            mesh.setBuffer(fadeBuffer);
            if (geometry == null) {
                geometry = new Geometry("conifer-cell-" + cellX + "-" + cellZ + "-lod" + lod, mesh);
            }
            geometry.setMaterial(lod == 2 ? farMaterial : material);
            geometry.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            geometry.setQueueBucket(RenderQueue.Bucket.Opaque);
            geometry.setModelBound(bounds);
        }

        void append(float x, float y, float z, float scale,
                float cosYaw, float sinYaw, float tint, float fade) {
            ensureCapacity(count + 1);
            int p = count * POSITION_COMPONENTS;
            positions[p] = x;
            positions[p + 1] = y;
            positions[p + 2] = z;
            positions[p + 3] = scale;
            int r = count * ROTATION_COMPONENTS;
            rotations[r] = cosYaw;
            rotations[r + 1] = sinYaw;
            rotations[r + 2] = tint;
            fades[count] = fade;
            float radius = lod == 2 ? ConiferImpostor.BOUND_RADIUS * scale
                    : TREE_RADIUS * scale * (Math.abs(cosYaw) + Math.abs(sinYaw));
            float bottom = lod == 2 ? y + (ConiferImpostorFrames.CENTER_Y - ConiferImpostor.BOUND_RADIUS) * scale : y;
            float top = lod == 2 ? y + (ConiferImpostorFrames.CENTER_Y + ConiferImpostor.BOUND_RADIUS) * scale : y + TREE_HEIGHT * scale;
            if (count == 0) {
                minX = x - radius; maxX = x + radius;
                minY = bottom; maxY = top;
                minZ = z - radius; maxZ = z + radius;
            } else {
                minX = Math.min(minX, x - radius); maxX = Math.max(maxX, x + radius);
                minY = Math.min(minY, bottom); maxY = Math.max(maxY, top);
                minZ = Math.min(minZ, z - radius); maxZ = Math.max(maxZ, z + radius);
            }
            count++;
        }

        void ensureCapacity(int needed) {
            if (needed <= capacity) return;
            int next = Math.max(2, capacity);
            while (next < needed) next = Math.min(groups.length, next * 2);
            allocateCapacity(next);
        }

        void allocateCapacity(int newCapacity) {
            float[] oldPositions = positions;
            float[] oldRotations = rotations;
            float[] oldFades = fades;
            FloatBuffer oldPositionData = positionData;
            FloatBuffer oldRotationData = rotationData;
            FloatBuffer oldFadeData = fadeData;
            if (oldPositionData != null) {
                bufferBytes -= (long) (oldPositionData.capacity() + oldRotationData.capacity()
                        + oldFadeData.capacity()) * Float.BYTES;
            }
            capacity = newCapacity;
            positions = new float[capacity * POSITION_COMPONENTS];
            rotations = new float[capacity * ROTATION_COMPONENTS];
            fades = new float[capacity];
            if (oldPositions != null) {
                // Keep all records already appended before this growth.
                System.arraycopy(oldPositions, 0, positions, 0,
                        Math.min(oldPositions.length, positions.length));
                System.arraycopy(oldRotations, 0, rotations, 0,
                        Math.min(oldRotations.length, rotations.length));
                System.arraycopy(oldFades, 0, fades, 0, Math.min(oldFades.length, fades.length));
            }
            positionData = BufferUtils.createFloatBuffer(capacity * POSITION_COMPONENTS);
            rotationData = BufferUtils.createFloatBuffer(capacity * ROTATION_COMPONENTS);
            fadeData = BufferUtils.createFloatBuffer(capacity);
            if (positionBuffer == null) {
                positionBuffer = new VertexBuffer(Type.TexCoord2);
                positionBuffer.setupData(Usage.Stream, POSITION_COMPONENTS, Format.Float, positionData);
                positionBuffer.setInstanced(true);
            } else {
                positionBuffer.updateData(positionData);
            }
            if (rotationBuffer == null) {
                rotationBuffer = new VertexBuffer(Type.TexCoord3);
                rotationBuffer.setupData(Usage.Stream, ROTATION_COMPONENTS, Format.Float, rotationData);
                rotationBuffer.setInstanced(true);
            } else {
                rotationBuffer.updateData(rotationData);
            }
            if (fadeBuffer == null) {
                fadeBuffer = new VertexBuffer(Type.Size);
                fadeBuffer.setupData(Usage.Stream, 1, Format.Float, fadeData);
                fadeBuffer.setInstanced(true);
            } else {
                fadeBuffer.updateData(fadeData);
            }
            if (oldPositionData != null) BufferUtils.destroyDirectBuffer(oldPositionData);
            if (oldRotationData != null) BufferUtils.destroyDirectBuffer(oldRotationData);
            if (oldFadeData != null) BufferUtils.destroyDirectBuffer(oldFadeData);
            bufferBytes += (long) (positionData.capacity() + rotationData.capacity() + fadeData.capacity()) * Float.BYTES;
        }

        void publish() {
            positionData.clear();
            rotationData.clear();
            fadeData.clear();
            positionData.put(positions, 0, count * POSITION_COMPONENTS);
            rotationData.put(rotations, 0, count * ROTATION_COMPONENTS);
            fadeData.put(fades, 0, count);
            positionData.flip();
            rotationData.flip();
            fadeData.flip();
            positionBuffer.updateData(positionData);
            rotationBuffer.updateData(rotationData);
            fadeBuffer.updateData(fadeData);
            mesh.updateCounts();
            bounds.getCenter().set((minX + maxX) * .5f, (minY + maxY) * .5f, (minZ + maxZ) * .5f);
            bounds.setXExtent((maxX - minX) * .5f);
            bounds.setYExtent((maxY - minY) * .5f);
            bounds.setZExtent((maxZ - minZ) * .5f);
            geometry.setModelBound(bounds);
        }

        void releaseBuffers() {
            if (mesh != null) {
                mesh.clearBuffer(Type.TexCoord2);
                mesh.clearBuffer(Type.TexCoord3);
                mesh.clearBuffer(Type.Size);
            }
            if (positionData != null) {
                bufferBytes -= (long) (positionData.capacity() + rotationData.capacity() + fadeData.capacity()) * Float.BYTES;
                if (positionBuffer != null) renderer.deleteBuffer(positionBuffer);
                if (rotationBuffer != null) renderer.deleteBuffer(rotationBuffer);
                if (fadeBuffer != null) renderer.deleteBuffer(fadeBuffer);
                BufferUtils.destroyDirectBuffer(positionData);
                BufferUtils.destroyDirectBuffer(rotationData);
                BufferUtils.destroyDirectBuffer(fadeData);
            }
            positionData = null;
            rotationData = null;
            positionBuffer = null;
            rotationBuffer = null;
            fadeBuffer = null;
            positions = null;
            rotations = null;
            fades = null;
            fadeData = null;
            capacity = 0;
            mesh = null;
        }

        void release() {
            releaseBuffers();
            geometry = null;
        }
    }
}
