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
package com.jme3.terrain.heightfield;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.MemoryBarrier;
import com.jme3.renderer.Renderer;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.util.BufferUtils;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.function.Predicate;

/**
 * Bounded GPU sibling transitions over an immutable heightfield page. Desired
 * selection uses {@link HeightfieldGpuSelection}; current cover and morph state
 * remain on the GPU. One sibling transaction advances per dispatch, following
 * {@link HeightfieldLod.Transition}. Draws include the complete current cover,
 * preserving off-camera casters but not an independent shadow error budget.
 *
 * <p>During convergence the current cover may exceed the requested error or
 * desired patch budget, while remaining within 64 patches. The caller must keep
 * all current and parent source data ready and retain buffers through GPU use.
 * This helper owns no threads, textures, residency leases or frame fences. Use
 * the hierarchy's page-relative camera coordinates and renderer thread.
 */
public final class HeightfieldGpuTransitions implements AutoCloseable {
    private static final int RECORD_VECTORS = 340;
    private static final int MATERIAL_BYTES = (1 + RECORD_VECTORS) * 16;
    // Control vec4 plus 22 ivec4s packing 85 persistent leaf insertion ranks.
    private static final int OUTPUT_BYTES = MATERIAL_BYTES + 16 + 22 * 16;
    private static final String SHADER_PATH =
            "Common/MatDefs/Terrain/Heightfield/HeightfieldTransitions.comp";

    private final Renderer renderer;
    private final HeightfieldGpuSelection desired;
    private final BufferObject output;
    private final Shader transitionShader;
    private boolean reset = true;
    private int previousRevision = -1;
    private boolean closed;

    /**
     * Allocate renderer-local selection and transition resources. GPU use and
     * source lifetime belong to the caller; close only after outstanding draws.
     *
     * @throws IOException if a required compute asset is unavailable
     */
    public HeightfieldGpuTransitions(AssetManager assets, Renderer renderer,
            HeightfieldHierarchy hierarchy) throws IOException {
        if (assets == null || renderer == null || hierarchy == null) {
            throw new IllegalArgumentException("asset manager, renderer and hierarchy are required");
        }
        HeightfieldGpuSelection.requireCapabilities(renderer);
        if (renderer.getLimits().get(Limits.ComputeStorageBufferBlockSize) < OUTPUT_BYTES
                || renderer.getLimits().get(Limits.UniformBufferObjectMaxBlockSize) < MATERIAL_BYTES) {
            throw new IllegalStateException("renderer buffers cannot hold the temporal heightfield records");
        }
        this.renderer = renderer;
        HeightfieldGpuSelection prepared = new HeightfieldGpuSelection(assets, renderer,
                hierarchy, HeightfieldGpuSelection.DrawCover.Complete);
        BufferObject records = new BufferObject();
        Shader shader;
        try {
            records.setData(BufferUtils.createByteBuffer(OUTPUT_BYTES).order(ByteOrder.nativeOrder()));
            records.setBinding(2);
            records.setUpdateNeeded();
            shader = HeightfieldGpuSelection.computeShader(assets, SHADER_PATH,
                    renderer.getCaps().contains(Caps.OpenGLES31) ? "GLSL310" : "GLSL430");
            shader.getUniform("m_NodeCount").setValue(VarType.Int, hierarchy.getNodes().size());
        } catch (IOException | RuntimeException | Error error) {
            renderer.deleteBuffer(records);
            prepared.close();
            throw error;
        }
        desired = prepared;
        output = records;
        transitionShader = shader;
    }

    /**
     * Select a desired ready cover, then advance its current sibling transition.
     * Step is a normalized fraction in [0,1], rather than seconds. A changed
     * revision label resets the current cover; changed heights need a new
     * immutable hierarchy/helper, not only a different label.
     */
    public void dispatch(Camera camera, double targetPixels, int revisionLabel,
            int maxLeaves, float step, Predicate<HeightfieldHierarchy.Node> ready) {
        ensureOpen();
        if (!Float.isFinite(step) || step < 0 || step > 1) {
            throw new IllegalArgumentException("transition step must be finite and in [0,1]");
        }
        desired.select(camera, targetPixels, revisionLabel, maxLeaves, ready);
        transitionShader.getUniform("m_Step").setValue(VarType.Float, step);
        transitionShader.getUniform("m_Reset").setValue(VarType.Boolean,
                reset || previousRevision != revisionLabel);
        renderer.setFixedShaderStorageBufferObject(0, desired.getNodeInput());
        renderer.setFixedShaderStorageBufferObject(1, desired.getPatchRecords());
        renderer.setFixedShaderStorageBufferObject(2, output);
        // Order state writes after the previous frame's material reads.
        renderer.memoryBarrier(EnumSet.of(MemoryBarrier.ShaderStorage));
        renderer.dispatchCompute(transitionShader, 1, 1, 1);
        renderer.memoryBarrier(EnumSet.of(MemoryBarrier.ShaderStorage));
        desired.writeCommandFrom(output);
        previousRevision = revisionLabel;
        reset = false;
    }

    /** Start from the pinned root again on the next dispatch. */
    public void reset() {
        ensureOpen();
        reset = true;
    }

    /** Current complete cover: vec4 header followed by four vec4s per patch. */
    public BufferObject getPatchRecords() {
        ensureOpen();
        return output;
    }

    /** Desired, pre-transition cover; useful for independent diagnostics. */
    public BufferObject getDesiredPatchRecords() {
        ensureOpen();
        return desired.getPatchRecords();
    }

    /** Indexed command generated from the current transition cover. */
    public BufferObject getCommand() {
        ensureOpen();
        return desired.getCommand();
    }

    public HeightfieldHierarchy getHierarchy() {
        return desired.getHierarchy();
    }

    /** Configure the ordinary and caster material to share current morph data. */
    public void configureMaterial(Material material) {
        ensureOpen();
        desired.configureMaterial(material);
        material.setUniformBufferObject("PatchRecords", output);
        material.setInt("PatchRecordBase", 0);
        material.setInt("PatchRecordVectors", RECORD_VECTORS);
        material.setBoolean("UseHeightfieldInstanceMorphs", true);
    }

    @Override
    public void close() {
        if (closed) return;
        renderer.deleteBuffer(output);
        renderer.deleteShader(transitionShader);
        desired.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("GPU heightfield transitions are closed");
    }
}
