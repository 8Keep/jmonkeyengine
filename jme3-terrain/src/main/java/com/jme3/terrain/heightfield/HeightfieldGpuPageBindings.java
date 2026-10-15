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
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.terrain.heightfield;

import com.jme3.material.Material;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.Renderer;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Bounded per-node texture-array layer and immediate-parent layer bindings
 * for a {@link HeightfieldGpuSelection} batch. This adapter owns only its
 * 85-entry UBO. The caller owns the texture array, layer contents, residency,
 * frame leases, and GPU-use fences.
 *
 * <p>Each entry is four integers: this node's layer, its immediate parent's
 * layer (the root refers to itself), then two reserved zero values. Nodes
 * without a ready layer have a layer of -1. Readiness requires both layers so
 * collapsed-edge lighting can sample the retained parent page. The bound page
 * payloads must each contain a complete {@code PATCH_CELLS + 3} square grid
 * with a one-texel halo. They are node-local decimated pages, not views into
 * a complete full-resolution texture. Each node samples the same immutable
 * source revision with spacing equal to canonical spacing times its stride;
 * overlapping parent/child samples must agree. The shader derives physical
 * texel coordinates from the selector's canonical origins and strides. This
 * adapter validates layer indices, not page contents or generation ownership.
 *
 * <p>Update and material configuration belong on the renderer thread. Keep
 * every referenced layer alive until all draws using this UBO complete. The
 * caller must wait for outstanding GPU use before closing this object.
 */
public final class HeightfieldGpuPageBindings implements AutoCloseable {
    private static final int NODE_CAPACITY = 85;
    private static final int INTS_PER_NODE = 4;
    private static final int BUFFER_BYTES = NODE_CAPACITY * INTS_PER_NODE * Integer.BYTES;

    private final Renderer renderer;
    private final int layerCapacity;
    private final List<HeightfieldHierarchy.Node> nodes;
    private final Map<HeightfieldHierarchy.Node, Integer> indices = new IdentityHashMap<>();
    private final int[] layers = new int[NODE_CAPACITY];
    private final BufferObject buffer;
    private boolean closed;

    /**
     * Allocate the node binding UBO.
     *
     * @param renderer renderer that will upload and use the buffer
     * @param hierarchy hierarchy whose nodes index the binding entries
     * @param layerCapacity number of valid array layers supplied by the caller
     * @throws IllegalArgumentException if arguments are null, capacity is
     *         invalid, or the hierarchy exceeds the fixed 85-node capacity
     * @throws IllegalStateException if texture-array, UBO, or UBO-size support
     *         is unavailable
     */
    public HeightfieldGpuPageBindings(Renderer renderer,
            HeightfieldHierarchy hierarchy, int layerCapacity) {
        if (renderer == null || hierarchy == null) {
            throw new IllegalArgumentException("renderer and hierarchy are required");
        }
        if (layerCapacity < 1) {
            throw new IllegalArgumentException("layerCapacity must be positive");
        }
        if (!renderer.getCaps().contains(Caps.TextureArray)
                || !renderer.getCaps().contains(Caps.UniformBufferObject)) {
            throw new IllegalStateException("heightfield page bindings require texture arrays and UBO support");
        }
        if (renderer.getLimits().get(Limits.UniformBufferObjectMaxBlockSize) < BUFFER_BYTES
                || renderer.getLimits().get(Limits.UniformBufferObjectMaxVertexBlocks) < 2) {
            throw new IllegalStateException("renderer UBO limits are below the two-block heightfield page binding ABI");
        }
        this.renderer = renderer;
        this.layerCapacity = layerCapacity;
        nodes = hierarchy.getNodes();
        if (nodes.isEmpty() || nodes.size() > NODE_CAPACITY) {
            throw new IllegalArgumentException("heightfield hierarchy must contain 1 to 85 nodes");
        }
        for (int i = 0; i < nodes.size(); i++) {
            indices.put(nodes.get(i), i);
        }
        for (int i = 0; i < layers.length; i++) layers[i] = -1;

        buffer = new BufferObject();
        ByteBuffer data = BufferUtils.createByteBuffer(BUFFER_BYTES).order(ByteOrder.nativeOrder());
        IntBuffer ints = data.asIntBuffer();
        for (int i = 0; i < NODE_CAPACITY; i++) {
            ints.put(i * INTS_PER_NODE, -1);
            ints.put(i * INTS_PER_NODE + 1, -1);
            ints.put(i * INTS_PER_NODE + 2, 0);
            ints.put(i * INTS_PER_NODE + 3, 0);
        }
        buffer.setData(data);
        buffer.setBinding(3);
        buffer.setUpdateNeeded();
    }

    /**
     * Replace the layer snapshot after validating every node mapping. A layer
     * of -1 means unavailable; other values must be below {@code layerCapacity}.
     */
    public void update(ToIntFunction<HeightfieldHierarchy.Node> layerOf) {
        ensureOpen();
        Objects.requireNonNull(layerOf, "layerOf");
        int[] next = new int[NODE_CAPACITY];
        for (int i = 0; i < next.length; i++) next[i] = -1;
        for (int i = 0; i < nodes.size(); i++) {
            int layer = layerOf.applyAsInt(nodes.get(i));
            if (layer < -1 || layer >= layerCapacity) {
                throw new IllegalArgumentException("node layer must be -1 or within the configured layer capacity");
            }
            next[i] = layer;
        }

        ByteBuffer data = buffer.getData().order(ByteOrder.nativeOrder());
        IntBuffer ints = data.asIntBuffer();
        for (int i = 0; i < NODE_CAPACITY; i++) {
            int parent = i < nodes.size() ? parentIndex(i) : -1;
            ints.put(i * INTS_PER_NODE, next[i]);
            ints.put(i * INTS_PER_NODE + 1, parent < 0 ? next[i] : next[parent]);
            ints.put(i * INTS_PER_NODE + 2, 0);
            ints.put(i * INTS_PER_NODE + 3, 0);
        }
        System.arraycopy(next, 0, layers, 0, NODE_CAPACITY);
        buffer.setUpdateNeeded();
    }

    /** True when both this node's page and its immediate parent page are ready. */
    public boolean isReady(HeightfieldHierarchy.Node node) {
        ensureOpen();
        Integer index = indices.get(Objects.requireNonNull(node, "node"));
        if (index == null) throw new IllegalArgumentException("node does not belong to this hierarchy");
        int own = layers[index];
        int parent = parentIndex(index);
        return own >= 0 && layers[parent < 0 ? index : parent] >= 0;
    }

    /** Binding UBO for the matching heightfield material shader. */
    public BufferObject getBuffer() {
        ensureOpen();
        return buffer;
    }

    /** Configure the node-page binding UBO after ordinary selection setup. */
    public void configureMaterial(Material material) {
        ensureOpen();
        if (material == null) throw new IllegalArgumentException("material is required");
        if (material.getParam("UseHeightfieldInstances") == null
                || !Boolean.TRUE.equals(material.getParam("UseHeightfieldInstances").getValue())) {
            throw new IllegalArgumentException("node pages require instance-selection material setup first");
        }
        material.setBoolean("UseHeightfieldNodePages", true);
        material.setUniformBufferObject("HeightfieldPageBindings", buffer);
    }

    /** Release only this object's UBO. Caller must first wait for GPU use. */
    @Override
    public void close() {
        if (closed) return;
        renderer.deleteBuffer(buffer);
        closed = true;
    }

    private int parentIndex(int index) {
        HeightfieldHierarchy.Node parent = nodes.get(index).getParent();
        if (parent == null) return -1;
        Integer result = indices.get(parent);
        if (result == null) throw new IllegalStateException("hierarchy parent is not in its node list");
        return result;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("heightfield page bindings are closed");
    }
}
