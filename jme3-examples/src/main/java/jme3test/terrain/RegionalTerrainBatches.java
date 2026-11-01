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
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.material.Material;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.terrain.heightfield.HeightfieldCpuBatch;
import com.jme3.terrain.heightfield.HeightfieldGrid;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldLod.Patch;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** Spatial draw grouping for the regional example, independent of LOD selection. */
final class RegionalTerrainBatches implements AutoCloseable {
    // 512 metres in the canonical half-metre source. Coarser nodes get their
    // own spatial level so a distant root does not widen a near cluster's bound.
    private static final int GROUP_CELLS = 1024;
    private final Renderer renderer;
    private final HeightfieldHierarchy hierarchy;
    private final HeightfieldGrid grid;
    private final int capacity;
    private final Node parent;
    private final Supplier<Material> materials;
    private final Consumer<Material> releaseMaterial;
    private final TreeMap<Long, List<Patch>> groups = new TreeMap<>();
    private final List<Draw> pool = new ArrayList<>();
    private int active;

    RegionalTerrainBatches(Renderer renderer, HeightfieldHierarchy hierarchy,
            HeightfieldGrid grid, int capacity, Node parent,
            Supplier<Material> materials, Consumer<Material> releaseMaterial) {
        this.renderer = renderer; this.hierarchy = hierarchy; this.grid = grid;
        this.capacity = capacity; this.parent = parent; this.materials = materials;
        this.releaseMaterial = releaseMaterial;
    }

    void update(List<Patch> patches, ToIntFunction<HeightfieldHierarchy.Node> layers,
            Consumer<Material> bindFrame) {
        for (List<Patch> group : groups.values()) group.clear();
        for (Patch patch : patches) {
            HeightfieldHierarchy.Node node = patch.getNode();
            int span = Math.max(GROUP_CELLS, node.getSpan());
            long key = ((long) span << 40) | ((long) (node.getOriginZ() / span) << 20)
                    | (node.getOriginX() / span);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(patch);
        }
        int next = 0;
        for (List<Patch> group : groups.values()) {
            for (int first = 0; first < group.size(); first += HeightfieldCpuBatch.MAX_INSTANCES) {
                if (next == pool.size()) {
                    if (pool.size() >= capacity) throw new IllegalStateException("batch pool exceeded page capacity");
                    HeightfieldCpuBatch batch = new HeightfieldCpuBatch(renderer, hierarchy, grid, capacity);
                    Material material = null;
                    try {
                        material = materials.get();
                        batch.configureMaterial(material);
                        Geometry geometry = new Geometry("terrain-cpu-batch", batch.getMesh());
                        geometry.setMaterial(material);
                        geometry.setShadowMode(ShadowMode.CastAndReceive);
                        geometry.setQueueBucket(RenderQueue.Bucket.Opaque);
                        pool.add(new Draw(batch, geometry));
                    } catch (RuntimeException | Error failure) {
                        if (material != null) releaseMaterial.accept(material);
                        batch.close();
                        throw failure;
                    }
                }
                Draw draw = pool.get(next++);
                draw.batch.update(group.subList(first, Math.min(group.size(), first + HeightfieldCpuBatch.MAX_INSTANCES)), layers);
                bindFrame.accept(draw.geometry.getMaterial());
                if (draw.geometry.getParent() == null) parent.attachChild(draw.geometry);
                // Batch wrapper bounds changed; publish them to spatial culling.
                draw.geometry.setModelBound(draw.batch.getMesh().getBound());
            }
        }
        for (int i = next; i < active; i++) pool.get(i).geometry.removeFromParent();
        active = next;
    }

    void validateWorldOrigin(float origin, int expectedPatches) {
        int count = 0;
        for (int i = 0; i < active; i++) {
            Draw draw = pool.get(i);
            Geometry geometry = draw.geometry;
            if (geometry.getParent() != parent || geometry.getWorldTranslation().x != origin
                    || geometry.getWorldTranslation().y != 0 || geometry.getWorldTranslation().z != origin) {
                throw new IllegalStateException("batch source frame must apply world origin exactly once");
            }
            count += draw.batch.getInstanceCount();
        }
        if (count != expectedPatches) throw new IllegalStateException("batch instances must cover every displayed patch");
    }

    int getDrawCount() { return active; }
    int getAllocatedCount() { return pool.size(); }
    /** Upper bound for owned UBO/ordinal storage; excludes shared grid and textures. */
    long getBufferCapacityBytes() {
        long bytes = 0;
        for (Draw draw : pool) {
            bytes += draw.batch.getRecordBufferBytes() + draw.batch.getPageBindingBufferBytes()
                    + HeightfieldCpuBatch.MAX_INSTANCES * Float.BYTES;
        }
        return bytes;
    }
    void detach() { for (Draw draw : pool) draw.geometry.removeFromParent(); active = 0; }
    @Override public void close() {
        detach();
        for (Draw draw : pool) {
            releaseMaterial.accept(draw.geometry.getMaterial());
            draw.batch.close();
        }
        pool.clear(); groups.clear();
    }
    private static final class Draw {
        final HeightfieldCpuBatch batch;
        final Geometry geometry;
        Draw(HeightfieldCpuBatch batch, Geometry geometry) { this.batch = batch; this.geometry = geometry; }
    }
}
