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

import com.jme3.asset.AssetManager;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.MemoryBarrier;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.opengl.GLFence;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.util.BufferUtils;
import com.jme3.util.NativeObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * One-slot asynchronous snapshot of the complete node cover written by GPU
 * heightfield selection or temporal transitions. It copies the complete-cover
 * records into a compact 85-node bitset before fencing, so the caller may later
 * use the returned immutable node list even after the source records are reused.
 *
 * <p>There is at most one outstanding submission. While it is pending, the
 * caller must keep the source buffer alive. Later compute work in the same
 * command stream may reuse the records: submission orders that reuse after the
 * snapshot copy. Page retention and whether the snapshot is still the latest
 * cover remain caller responsibilities. This class owns only its feedback SSBO and
 * compute shader; the caller owns source buffers, page leases, rendering
 * transforms, and all other scheduling. Create, submit, poll, and close on the
 * renderer thread.
 */
public final class HeightfieldGpuCoverFeedback implements AutoCloseable {
    private static final int NODE_CAPACITY = 85;
    private static final int MAX_LEAVES = 64;
    private static final int OUTPUT_BYTES = 32;
    private static final String SHADER_PATH =
            "Common/MatDefs/Terrain/Heightfield/HeightfieldCoverFeedback.comp";

    private final Renderer renderer;
    private final List<HeightfieldHierarchy.Node> nodes;
    private final Thread ownerThread;
    private final BufferObject output;
    private final Shader shader;
    private final ByteBuffer readback;
    private GLFence fence;
    private boolean pending;
    private boolean unsafe;
    private boolean signaled;
    private Snapshot completedSnapshot;
    private boolean closed;
    private int submittedSequence;
    private int submittedRevision;

    /** Allocate the one-slot feedback resources. */
    public HeightfieldGpuCoverFeedback(AssetManager assets, Renderer renderer,
            HeightfieldHierarchy hierarchy) throws IOException {
        if (assets == null || renderer == null || hierarchy == null) {
            throw new IllegalArgumentException("asset manager, renderer and hierarchy are required");
        }
        HeightfieldGpuSelection.requireCapabilities(renderer);
        if (renderer.getLimits().get(Limits.ComputeStorageBufferBindings) < 2
                || renderer.getLimits().get(Limits.ComputeStorageBufferBlockSize) < OUTPUT_BYTES) {
            throw new IllegalStateException("renderer storage-buffer limits are below the cover feedback ABI");
        }
        List<HeightfieldHierarchy.Node> hierarchyNodes = hierarchy.getNodes();
        if (hierarchyNodes.isEmpty() || hierarchyNodes.size() > NODE_CAPACITY) {
            throw new IllegalArgumentException("heightfield hierarchy must contain 1 to 85 nodes");
        }
        this.renderer = renderer;
        nodes = hierarchyNodes;
        ownerThread = Thread.currentThread();

        BufferObject buffer = new BufferObject();
        Shader compute = null;
        try {
            buffer.setData(BufferUtils.createByteBuffer(OUTPUT_BYTES).order(ByteOrder.nativeOrder()));
            buffer.setBinding(1);
            buffer.setUpdateNeeded();
            compute = HeightfieldGpuSelection.computeShader(assets, SHADER_PATH,
                    renderer.getCaps().contains(Caps.OpenGLES31) ? "GLSL310" : "GLSL430");
            compute.getUniform("m_NodeCount").setValue(VarType.Int, nodes.size());
        } catch (IOException | RuntimeException | Error error) {
            renderer.deleteBuffer(buffer);
            if (compute != null) renderer.deleteShader(compute);
            throw error;
        }
        output = buffer;
        shader = compute;
        readback = BufferUtils.createByteBuffer(OUTPUT_BYTES).order(ByteOrder.nativeOrder());
    }

    /**
     * Copy the complete cover from records into this object's feedback slot and
     * submit a completion fence. Record stride must be 2 for immediate selection
     * records or 4 for temporal records. The header's total-cover count and
     * revision label are copied/validated by the compute shader.
     *
     * @param records source selection or temporal output buffer, already uploaded and clean
     * @param recordStride vec4 values per node record (2 or 4)
     * @param sequence caller-provided nonnegative snapshot sequence
     * @param revisionLabel expected exact integer source revision in [0, 2^24]
     */
    public void submit(BufferObject records, int recordStride, int sequence, int revisionLabel) {
        checkThread();
        ensureOpen();
        if (pending || unsafe) throw new IllegalStateException("a cover feedback submission is already outstanding");
        if (records == null) throw new IllegalArgumentException("records are required");
        if (recordStride != 2 && recordStride != 4) {
            throw new IllegalArgumentException("recordStride must be 2 or 4 vec4 values");
        }
        if (sequence < 0) throw new IllegalArgumentException("sequence must be nonnegative");
        if (revisionLabel < 0 || revisionLabel > 16777216) {
            throw new IllegalArgumentException("revisionLabel must be an exact integer from 0 to 2^24");
        }
        if (records.getId() == NativeObject.INVALID_ID || records.isUpdateNeeded()) {
            throw new IllegalStateException("source records must have clean uploaded GPU storage");
        }
        if (records.getAccessHint() == BufferObject.AccessHint.CpuOnly) {
            throw new IllegalArgumentException("source records cannot be CPU-only");
        }
        // The shader declares the common full record ABI, even when only its
        // occupied prefix is read.
        long minimumBytes = 341L * 16;
        if (records.getData().remaining() < minimumBytes) {
            throw new IllegalArgumentException("source record buffer is too small for the declared stride");
        }

        shader.getUniform("m_RecordStride").setValue(VarType.Int, recordStride);
        shader.getUniform("m_Sequence").setValue(VarType.Int, sequence);
        renderer.setFixedShaderStorageBufferObject(0, records);
        renderer.setFixedShaderStorageBufferObject(1, output);
        renderer.memoryBarrier(EnumSet.of(MemoryBarrier.ShaderStorage));
        // From this point any failure leaves GPU use uncertain. Keep the slot
        // locked so callers cannot close or overwrite it without a fence.
        unsafe = true;
        renderer.dispatchCompute(shader, 1, 1, 1);
        renderer.memoryBarrier(EnumSet.of(MemoryBarrier.BufferUpdate, MemoryBarrier.ShaderStorage));
        fence = renderer.createFence();
        pending = true;
        unsafe = false;
        submittedSequence = sequence;
        submittedRevision = revisionLabel;
    }

    /**
     * Poll the fence without waiting. Returns null while pending or when no
     * snapshot has been submitted. A returned snapshot consumes the slot.
     */
    public Snapshot poll() {
        checkThread();
        ensureOpen();
        if (unsafe) throw new IllegalStateException("feedback dispatch failed before a completion fence was created");
        if (!pending) return null;
        if (!signaled) {
            if (!renderer.isFenceSignaled(fence)) return null;
            signaled = true;
        }
        if (completedSnapshot == null) completedSnapshot = readSnapshot();
        renderer.deleteFence(fence);
        fence = null;
        pending = false;
        signaled = false;
        Snapshot result = completedSnapshot;
        completedSnapshot = null;
        return result;
    }

    /** True while a submitted snapshot or uncertain dispatch still owns the slot. */
    public boolean isPending() {
        checkThread();
        ensureOpen();
        return pending || unsafe;
    }

    /** Close after completion; an unsignaled or uncertain slot is never freed. */
    @Override
    public void close() {
        checkThread();
        if (closed) return;
        if (unsafe) throw new IllegalStateException("cannot close after an unfenced GPU dispatch failure");
        if (pending) {
            if (!signaled && !renderer.isFenceSignaled(fence)) {
                throw new IllegalStateException("cannot close while cover feedback is still pending");
            }
            renderer.deleteFence(fence);
            fence = null;
            pending = false;
            signaled = false;
            completedSnapshot = null;
        }
        renderer.deleteBuffer(output);
        renderer.deleteShader(shader);
        BufferUtils.destroyDirectBuffer(readback);
        closed = true;
    }

    private Snapshot readSnapshot() {
        readback.clear();
        renderer.readBufferObject(output, 0, readback);
        IntBuffer values = readback.asIntBuffer();
        int sequence = values.get(0);
        int revision = values.get(1);
        int count = values.get(2);
        int valid = values.get(3);
        if (valid != 1 || sequence != submittedSequence || revision != submittedRevision
                || count < 1 || count > MAX_LEAVES) {
            throw new IllegalStateException("GPU cover feedback header is invalid or stale");
        }
        List<HeightfieldHierarchy.Node> selected = new ArrayList<>(count);
        int setBits = 0;
        for (int node = 0; node < NODE_CAPACITY; node++) {
            int mask = values.get(4 + (node >>> 5));
            boolean present = (mask & (1 << (node & 31))) != 0;
            if (present) {
                setBits++;
                if (node >= nodes.size()) {
                    throw new IllegalStateException("GPU cover feedback references a padded node");
                }
                selected.add(nodes.get(node));
            }
        }
        // The four uint words hold 128 bits; all bits after node 84 are reserved.
        for (int node = NODE_CAPACITY; node < 128; node++) {
            int mask = values.get(4 + (node >>> 5));
            if ((mask & (1 << (node & 31))) != 0) {
                throw new IllegalStateException("GPU cover feedback reserved bits are nonzero");
            }
        }
        if (setBits != count) throw new IllegalStateException("GPU cover feedback count does not match its bitset");
        return new Snapshot(submittedSequence, submittedRevision, count, selected);
    }

    private void checkThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("cover feedback must be used on its renderer thread");
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("heightfield GPU cover feedback is closed");
    }

    /** Immutable copied cover in ascending hierarchy node index order. */
    public static final class Snapshot {
        private final int sequence;
        private final int revisionLabel;
        private final int leafCount;
        private final List<HeightfieldHierarchy.Node> nodes;

        private Snapshot(int sequence, int revisionLabel, int leafCount,
                List<HeightfieldHierarchy.Node> nodes) {
            this.sequence = sequence;
            this.revisionLabel = revisionLabel;
            this.leafCount = leafCount;
            this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
        }

        public int getSequence() { return sequence; }
        public int getRevisionLabel() { return revisionLabel; }
        public int getLeafCount() { return leafCount; }
        public List<HeightfieldHierarchy.Node> getNodes() { return nodes; }
    }
}
