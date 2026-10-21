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
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.bounding.BoundingBox;
import com.jme3.material.Material;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Limits;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.instancing.InstancedGeometry;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldLod;
import com.jme3.terrain.heightfield.HeightfieldProjection;
import com.jme3.util.BufferUtils;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Bounded CPU selection and ordinary-instancing reference for heightfield GPU batches. */
final class HeightfieldCpuPatchBatch implements AutoCloseable {
    private static final int NODE_CAPACITY = 85;
    private static final int LEAF_CAPACITY = 64;
    private static final int RECORD_VECTORS = 1 + NODE_CAPACITY * 4;
    private static final int RECORD_BYTES = RECORD_VECTORS * 16;

    private final Renderer renderer;
    private final HeightfieldHierarchy hierarchy;
    private final List<HeightfieldHierarchy.Node> nodes;
    private final Map<HeightfieldHierarchy.Node, Integer> nodeIndices = new IdentityHashMap<>();
    private final HeightfieldLod lod;
    private final InstancedGeometry geometry;
    private final Geometry[] instances = new Geometry[LEAF_CAPACITY];
    private final BufferObject patchRecords;
    private int activeInstances;
    private boolean closed;

    HeightfieldCpuPatchBatch(Renderer renderer, HeightfieldHierarchy hierarchy,
            Mesh mesh, Material material) {
        if (renderer == null || hierarchy == null || mesh == null || material == null) {
            throw new IllegalArgumentException("renderer, hierarchy, mesh, and material are required");
        }
        if (!renderer.getCaps().contains(Caps.MeshInstancing)
                || !renderer.getCaps().contains(Caps.UniformBufferObject)) {
            throw new IllegalStateException("CPU heightfield batching requires mesh instancing and UBO support");
        }
        if (renderer.getLimits().get(Limits.UniformBufferObjectMaxBlockSize) < RECORD_BYTES) {
            throw new IllegalStateException("renderer UBO block limit is below the 341-vec4 patch record ABI");
        }
        this.renderer = renderer;
        this.hierarchy = hierarchy;
        nodes = hierarchy.getNodes();
        if (nodes.isEmpty() || nodes.size() > NODE_CAPACITY) {
            throw new IllegalArgumentException("heightfield hierarchy must contain 1 to 85 nodes");
        }
        lod = new HeightfieldLod(hierarchy);
        for (int i = 0; i < nodes.size(); i++) nodeIndices.put(nodes.get(i), i);

        patchRecords = new BufferObject();
        patchRecords.setData(BufferUtils.createByteBuffer(RECORD_BYTES).order(ByteOrder.nativeOrder()));
        patchRecords.setUpdateNeeded();

        geometry = new InstancedGeometry("CPU selected heightfield");
        geometry.setMesh(mesh);
        geometry.setMaterial(material);
        // InstancedGeometry grows its backing array just before filling its last
        // slot, so reserve one spare slot for a 64-patch maximum cover.
        geometry.setMaxNumInstances(LEAF_CAPACITY + 1);
        BoundingBox rootBound = hierarchy.getRoot().getBound();
        for (int i = 0; i < instances.length; i++) {
            Geometry instance = new Geometry("heightfield patch instance " + i, mesh);
            instance.setModelBound((BoundingBox) rootBound.clone());
            instance.updateGeometricState();
            instances[i] = instance;
        }

        material.setBoolean("UseHeightfieldInstances", true);
        material.setBoolean("UseHeightfieldInstanceMorphs", false);
        material.setBoolean("UseHeightfieldNodePages", false);
        material.setUniformBufferObject("PatchRecords", patchRecords);
        material.setInt("PatchRecordBase", 0);
        material.setInt("PatchRecordVectors", NODE_CAPACITY * 4);
        material.setInt("PageCells", hierarchy.getCells());
        material.setInt("PatchGridCells", hierarchy.getPatchCells());
        material.setFloat("SampleSpacing", hierarchy.getSampleSpacing());
    }

    InstancedGeometry getGeometry() {
        ensureOpen();
        return geometry;
    }

    /** Select a whole-page CPU cover and update its records and identity instances. */
    void dispatch(Camera camera, double targetPixels, int revisionLabel, int maxLeaves,
            Predicate<HeightfieldHierarchy.Node> ready) {
        ensureOpen();
        if (camera == null || ready == null) {
            throw new IllegalArgumentException("camera and readiness predicate are required");
        }
        if (!Double.isFinite(targetPixels) || targetPixels <= 0 || targetPixels > Float.MAX_VALUE) {
            throw new IllegalArgumentException("targetPixels must be positive and finite");
        }
        float target = (float) targetPixels;
        if ((double) target > targetPixels) target = Math.nextDown(target);
        if (!Float.isFinite(target) || target <= 0) {
            throw new IllegalArgumentException("targetPixels must remain positive after float conversion");
        }
        if (revisionLabel < 0 || revisionLabel > 16777216) {
            throw new IllegalArgumentException("revisionLabel must be an exactly representable integer from 0 to 2^24");
        }
        if (maxLeaves < 1 || maxLeaves > LEAF_CAPACITY) {
            throw new IllegalArgumentException("maxLeaves must be in [1, 64]");
        }

        HeightfieldLod.Selection selection = lod.select(camera, target, maxLeaves, ready);
        List<HeightfieldHierarchy.Node> cover = new ArrayList<>(selection.getLeaves());
        Collections.sort(cover, (a, b) -> Integer.compare(nodeIndices.get(a), nodeIndices.get(b)));
        List<HeightfieldHierarchy.Node> visible = new ArrayList<>(cover.size());
        for (HeightfieldHierarchy.Node node : cover) {
            if (visible(camera, node)) visible.add(node);
        }
        int flags = (selection.isBudgetLimited() ? 1 : 0)
                | (selection.isReadinessLimited() ? 2 : 0) | (selection.isSourceLimited() ? 4 : 0);
        packRecords(camera, cover, visible, revisionLabel, flags);
        updateInstances(cover.size());
    }

    private void updateInstances(int requested) {
        while (activeInstances > requested) {
            geometry.deleteInstance(instances[--activeInstances]);
        }
        while (activeInstances < requested) {
            geometry.addInstance(instances[activeInstances++]);
        }
        geometry.updateInstances(null);
        // The owning scene graph updates this batch after its parent lights and
        // transforms; updating an attached child here would violate that order.
    }

    private void packRecords(Camera camera, List<HeightfieldHierarchy.Node> cover,
            List<HeightfieldHierarchy.Node> visible, int revisionLabel, int flags) {
        FloatBuffer floats = patchRecords.getData().order(ByteOrder.nativeOrder()).asFloatBuffer();
        floats.clear();
        floats.put(0, visible.size());
        floats.put(1, flags);
        floats.put(2, cover.size());
        floats.put(3, revisionLabel);
        // Header vec4, then 85 cover pairs and 85 compact visible pairs.
        for (int i = 0; i < cover.size(); i++) {
            writeRecord(floats, 4 + i * 8, cover.get(i), camera, cover, revisionLabel);
        }
        for (int i = 0; i < visible.size(); i++) {
            writeRecord(floats, 4 + (NODE_CAPACITY + i) * 8,
                    visible.get(i), camera, cover, revisionLabel);
        }
        patchRecords.setUpdateNeeded();
    }

    private void writeRecord(FloatBuffer floats, int base, HeightfieldHierarchy.Node node,
            Camera camera, List<HeightfieldHierarchy.Node> cover, int revisionLabel) {
        int edgeMask = 0;
        for (HeightfieldHierarchy.Node other : cover) {
            if (other == node || other.getLevel() >= node.getLevel() || !touches(node, other)) continue;
            int side = touchingSide(node, other);
            if (side >= 0) edgeMask |= 1 << side;
        }
        double stable = HeightfieldProjection.pixels(camera, node.getBound(), node.getStableError());
        double morph = HeightfieldProjection.pixels(camera, node.getBound(), node.getMorphError());
        boolean hasCoarser = hasCoarserNeighbor(node, cover);
        float error = ceilFloat(hasCoarser ? morph : stable);
        int nodeIndex = nodeIndices.get(node);
        floats.put(base, nodeIndex);
        floats.put(base + 1, node.getOriginX());
        floats.put(base + 2, node.getOriginZ());
        floats.put(base + 3, node.getStride());
        floats.put(base + 4, node.getSpan());
        floats.put(base + 5, edgeMask | (hasCoarser ? 16 : 0));
        floats.put(base + 6, error);
        floats.put(base + 7, revisionLabel);
    }

    private static boolean hasCoarserNeighbor(HeightfieldHierarchy.Node node,
            List<HeightfieldHierarchy.Node> cover) {
        for (HeightfieldHierarchy.Node other : cover) {
            if (other != node && other.getLevel() < node.getLevel() && touches(node, other)) return true;
        }
        return false;
    }

    private static int touchingSide(HeightfieldHierarchy.Node a, HeightfieldHierarchy.Node b) {
        boolean zOverlap = Math.max(a.getOriginZ(), b.getOriginZ())
                < Math.min(a.getOriginZ() + a.getSpan(), b.getOriginZ() + b.getSpan());
        boolean xOverlap = Math.max(a.getOriginX(), b.getOriginX())
                < Math.min(a.getOriginX() + a.getSpan(), b.getOriginX() + b.getSpan());
        if (zOverlap && a.getOriginX() == b.getOriginX() + b.getSpan()) return 0;
        if (zOverlap && a.getOriginX() + a.getSpan() == b.getOriginX()) return 1;
        if (xOverlap && a.getOriginZ() == b.getOriginZ() + b.getSpan()) return 2;
        if (xOverlap && a.getOriginZ() + a.getSpan() == b.getOriginZ()) return 3;
        return -1;
    }

    private static boolean touches(HeightfieldHierarchy.Node a, HeightfieldHierarchy.Node b) {
        return a != b && a.getOriginX() <= b.getOriginX() + b.getSpan()
                && b.getOriginX() <= a.getOriginX() + a.getSpan()
                && a.getOriginZ() <= b.getOriginZ() + b.getSpan()
                && b.getOriginZ() <= a.getOriginZ() + a.getSpan();
    }

    private static boolean visible(Camera camera, HeightfieldHierarchy.Node node) {
        int planeState = camera.getPlaneState();
        camera.setPlaneState(0);
        try {
            return camera.contains(node.getBound()) != Camera.FrustumIntersect.Outside;
        } finally {
            camera.setPlaneState(planeState);
        }
    }

    private static float ceilFloat(double value) {
        if (!Double.isFinite(value) || value > Float.MAX_VALUE) return Float.POSITIVE_INFINITY;
        if (value <= 0) return 0;
        float rounded = (float) value;
        return (double) rounded < value ? Math.nextUp(rounded) : rounded;
    }

    /** Release this helper's UBO and identity-transform buffer after GPU use ends. */
    @Override
    public void close() {
        if (closed) return;
        VertexBuffer transforms = geometry.getTransformUserInstanceData();
        renderer.deleteBuffer(transforms);
        BufferUtils.destroyDirectBuffer(transforms.getData());
        BufferUtils.destroyDirectBuffer(patchRecords.getData());
        renderer.deleteBuffer(patchRecords);
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("CPU heightfield patch batch is closed");
    }
}
