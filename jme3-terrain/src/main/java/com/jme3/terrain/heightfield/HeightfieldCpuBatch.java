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

import com.jme3.bounding.BoundingBox;
import com.jme3.material.Material;
import com.jme3.math.Vector2f;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Format;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.VertexBuffer.Usage;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.terrain.heightfield.HeightfieldHierarchy.Node;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * CPU-remapped batch of at most 85 heightfield patches. Each draw has its own
 * local patch-record and page-binding tables, so the full hierarchy is not
 * limited to 85 nodes. The caller owns page residency and GPU-use leases.
 *
 * <p>This object owns two UBOs, one instance-ordinal vertex buffer and a mesh
 * wrapper whose topology is shared with the supplied {@link HeightfieldGrid}.
 * Update, material configuration, drawing and close belong on the render
 * thread. Do not attach or draw the mesh before its first successful nonempty
 * {@link #update(List, ToIntFunction)}: the renderer may promote a zero mesh
 * instance count to one. Drain GPU use before closing.
 */
public final class HeightfieldCpuBatch implements AutoCloseable {
    public static final int MAX_INSTANCES = 85;
    private static final int RECORD_VECTORS = 1 + 4 * MAX_INSTANCES;
    private static final int RECORD_BYTES = RECORD_VECTORS * 4 * Float.BYTES;
    private static final int PAGE_BINDING_BYTES = MAX_INSTANCES * 4 * Integer.BYTES;

    private final Renderer renderer;
    private final HeightfieldHierarchy hierarchy;
    private final int layerCapacity;
    private final BufferObject records = new BufferObject();
    private final BufferObject pageBindings = new BufferObject();
    private final VertexBuffer ordinals = new VertexBuffer(Type.Size);
    private final FloatBuffer ordinalData = BufferUtils.createFloatBuffer(MAX_INSTANCES);
    private final Node[] updateNodes = new Node[MAX_INSTANCES];
    private final int[] updateLayers = new int[MAX_INSTANCES];
    private final FloatBuffer recordFloats;
    private final IntBuffer pageInts;
    private final Mesh mesh;
    private int activeInstances;
    private boolean closed;

    /**
     * Create a batch adapter for a shared patch grid and hierarchy.
     *
     * @param renderer renderer used by the matching draws
     * @param hierarchy immutable source hierarchy
     * @param grid shared topology with the hierarchy's patch cell count
     * @param layerCapacity exclusive upper bound for page-array layer indices
     * @throws IllegalArgumentException for invalid arguments or grid mismatch
     * @throws IllegalStateException when required texture-array/UBO support is absent
     */
    public HeightfieldCpuBatch(Renderer renderer, HeightfieldHierarchy hierarchy,
            HeightfieldGrid grid, int layerCapacity) {
        if (renderer == null || hierarchy == null || grid == null) {
            throw new IllegalArgumentException("renderer, hierarchy and grid are required");
        }
        if (layerCapacity < 1) throw new IllegalArgumentException("layerCapacity must be positive");
        if (!Float.isFinite(hierarchy.getSampleSpacing()) || hierarchy.getSampleSpacing() <= 0) {
            throw new IllegalArgumentException("hierarchy spacing must be positive and finite");
        }
        if (!renderer.getCaps().contains(Caps.TextureArray)
                || !renderer.getCaps().contains(Caps.VertexTextureFetch)
                || !renderer.getCaps().contains(Caps.FloatTexture)
                || !renderer.getCaps().contains(Caps.UniformBufferObject)
                || !renderer.getCaps().contains(Caps.MeshInstancing)
                || !(renderer.getCaps().contains(Caps.OpenGLES30)
                || renderer.getCaps().contains(Caps.GLSL150))) {
            throw new IllegalStateException("CPU heightfield batches require GL3/ES3 texture arrays, vertex fetch, instancing and UBOs");
        }
        if (renderer.getLimits().get(Limits.UniformBufferObjectMaxBlockSize) < RECORD_BYTES
                || renderer.getLimits().get(Limits.UniformBufferObjectMaxBlockSize) < PAGE_BINDING_BYTES
                || renderer.getLimits().get(Limits.UniformBufferObjectMaxVertexBlocks) < 2) {
            throw new IllegalStateException("renderer UBO limits are below the heightfield batch ABI");
        }
        Node root = hierarchy.getRoot();
        if (root == null) throw new IllegalArgumentException("hierarchy root is required");

        this.renderer = renderer;
        this.hierarchy = hierarchy;
        this.layerCapacity = layerCapacity;
        mesh = grid.createLodPatchMesh(root);
        ordinalData.limit(0);
        ordinals.setupData(Usage.Stream, 1, Format.Float, ordinalData);
        ordinals.setInstanceSpan(1);
        mesh.setBuffer(ordinals);
        mesh.updateCounts();

        records.setData(BufferUtils.createByteBuffer(RECORD_BYTES).order(ByteOrder.nativeOrder()));
        records.setBinding(2);
        records.setUpdateNeeded();
        pageBindings.setData(BufferUtils.createByteBuffer(PAGE_BINDING_BYTES).order(ByteOrder.nativeOrder()));
        pageBindings.setBinding(3);
        pageBindings.setUpdateNeeded();
        recordFloats = records.getData().order(ByteOrder.nativeOrder()).asFloatBuffer();
        pageInts = pageBindings.getData().order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    /**
     * Replace this draw's patch records and local layer remapping.
     * Validation and layer lookup finish before any GPU-facing buffer changes.
     * The patch list must contain unique nodes from this hierarchy.
     */
    public void update(List<HeightfieldLod.Patch> patches,
            ToIntFunction<Node> ownLayerOf) {
        ensureOpen();
        Objects.requireNonNull(ownLayerOf, "ownLayerOf");
        if (patches == null || patches.isEmpty() || patches.size() > MAX_INSTANCES) {
            throw new IllegalArgumentException("batch must contain 1 to 85 patches");
        }

        // Validate the full replacement first. Callback failures cannot leave
        // half of the prior batch published to the renderer.
        for (int i = 0; i < patches.size(); i++) {
            HeightfieldLod.Patch patch = patches.get(i);
            if (patch == null) throw new IllegalArgumentException("patches must not contain null");
            Node node = patch.getNode();
            if (node == null || node.getIndex() < 0 || node.getIndex() >= hierarchy.getNodes().size()
                    || hierarchy.getNodes().get(node.getIndex()) != node) {
                throw new IllegalArgumentException("patch node does not belong to this hierarchy");
            }
            for (int j = 0; j < i; j++) {
                if (updateNodes[j] == node) throw new IllegalArgumentException("patch nodes must be unique");
            }
            if (node.getSpan() % node.getStride() != 0
                    || !finiteUnit(patch.getMorph())
                    || !finiteUnit(patch.getEdgeMorph(0)) || !finiteUnit(patch.getEdgeMorph(1))
                    || !finiteUnit(patch.getEdgeMorph(2)) || !finiteUnit(patch.getEdgeMorph(3))) {
                throw new IllegalArgumentException("patch grid or morph weights are invalid");
            }
            int layer = ownLayerOf.applyAsInt(node);
            if (layer < 0 || layer >= layerCapacity) {
                throw new IllegalArgumentException("each patch requires a valid own-page layer");
            }
            updateNodes[i] = node;
            updateLayers[i] = layer;
        }

        if (matchesPublishedState(patches)) return;

        // Fixed-size UBO contents are rewritten in place. The shader reads
        // only the first N records; clearing unused entries avoids stale data.
        ordinalData.clear();
        for (int i = 0; i < RECORD_VECTORS * 4; i++) recordFloats.put(i, 0f);
        for (int i = 0; i < MAX_INSTANCES * 4; i++) pageInts.put(i, 0);
        for (int i = 0; i < MAX_INSTANCES; i++) {
            pageInts.put(4 * i, -1);
            pageInts.put(4 * i + 1, -1);
        }
        for (int i = 0; i < patches.size(); i++) {
            Node node = updateNodes[i];
            HeightfieldLod.Patch patch = patches.get(i);
            int record = 4 + i * 16;
            recordFloats.put(record, i);
            recordFloats.put(record + 1, node.getOriginX());
            recordFloats.put(record + 2, node.getOriginZ());
            recordFloats.put(record + 3, node.getStride());
            recordFloats.put(record + 4, node.getSpan());
            writeMorphRecord(recordFloats, record, patch.getMorph(),
                    patch.getEdgeMorph(0), patch.getEdgeMorph(1),
                    patch.getEdgeMorph(2), patch.getEdgeMorph(3));
            pageInts.put(4 * i, updateLayers[i]);
            // CPU batches sample the child's canonical page for both fine and
            // snapped-parent gradients, so no separate parent page is needed.
            pageInts.put(4 * i + 1, updateLayers[i]);
            ordinalData.put(i, (float) i);
        }
        ordinalData.position(0);
        ordinalData.limit(patches.size());
        ordinals.updateData(ordinalData);
        mesh.updateCounts();
        records.setUpdateNeeded();
        pageBindings.setUpdateNeeded();
        updateBounds(patches.size());
        activeInstances = patches.size();
    }

    /** Bind this batch's UBOs and CPU-ordinal shader mode to its dedicated material. */
    public void configureMaterial(Material material) {
        ensureOpen();
        if (material == null) throw new IllegalArgumentException("material is required");
        material.setBoolean("UseHeightfieldLod", true);
        material.setBoolean("UseHeightfieldInstances", true);
        material.setBoolean("UseHeightfieldInstanceMorphs", true);
        material.setBoolean("UseHeightfieldCpuInstances", true);
        material.setBoolean("UseHeightfieldNodePages", true);
        material.setUniformBufferObject("PatchRecords", records);
        material.setUniformBufferObject("HeightfieldPageBindings", pageBindings);
        material.setInt("PatchRecordBase", 0);
        material.setInt("PatchRecordVectors", 4 * MAX_INSTANCES);
        material.setInt("PageCells", hierarchy.getCells());
        material.setInt("PatchGridCells", hierarchy.getPatchCells());
        material.setFloat("SampleSpacing", hierarchy.getSampleSpacing());
        material.setFloat("SourceSampleSpacing", hierarchy.getSampleSpacing());
        material.setVector2("PageLatticeOrigin", new Vector2f(0, 0));
    }

    public Mesh getMesh() { ensureOpen(); return mesh; }
    /** Number of records published by the last successful update; zero before the first update. */
    public int getInstanceCount() { ensureOpen(); return activeInstances; }
    public int getRecordBufferBytes() { ensureOpen(); return RECORD_BYTES; }
    public int getPageBindingBufferBytes() { ensureOpen(); return PAGE_BINDING_BYTES; }
    public BufferObject getPatchRecords() { ensureOpen(); return records; }
    public BufferObject getPageBindings() { ensureOpen(); return pageBindings; }

    /** Store the four-vec4 shader ABI's interior/west/east/south/north weights. */
    static void writeMorphRecord(FloatBuffer records, int base, float interior,
            float west, float east, float south, float north) {
        records.put(base + 8, interior);
        records.put(base + 9, west);
        records.put(base + 10, east);
        records.put(base + 11, south);
        records.put(base + 12, north);
    }

    @Override
    public void close() {
        if (closed) return;
        renderer.deleteBuffer(records);
        renderer.deleteBuffer(pageBindings);
        renderer.deleteBuffer(ordinals);
        closed = true;
    }

    private void updateBounds(int count) {
        BoundingBox bounds = updateNodes[0].getBound();
        for (int i = 1; i < count; i++) bounds.mergeLocal(updateNodes[i].getBound());
        mesh.setBound(bounds);
    }

    private boolean matchesPublishedState(List<HeightfieldLod.Patch> patches) {
        if (patches.size() != activeInstances) return false;
        for (int i = 0; i < activeInstances; i++) {
            HeightfieldLod.Patch patch = patches.get(i);
            Node node = patch.getNode();
            int record = 4 + i * 16;
            if (recordFloats.get(record + 1) != node.getOriginX()
                    || recordFloats.get(record + 2) != node.getOriginZ()
                    || recordFloats.get(record + 3) != node.getStride()
                    || recordFloats.get(record + 8) != patch.getMorph()
                    || recordFloats.get(record + 9) != patch.getEdgeMorph(0)
                    || recordFloats.get(record + 10) != patch.getEdgeMorph(1)
                    || recordFloats.get(record + 11) != patch.getEdgeMorph(2)
                    || recordFloats.get(record + 12) != patch.getEdgeMorph(3)
                    || pageInts.get(4 * i) != updateLayers[i]
                    || pageInts.get(4 * i + 1) != updateLayers[i]) return false;
        }
        return true;
    }

    private static boolean finiteUnit(float value) {
        return Float.isFinite(value) && value >= 0 && value <= 1;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("heightfield CPU batch is closed");
    }
}
