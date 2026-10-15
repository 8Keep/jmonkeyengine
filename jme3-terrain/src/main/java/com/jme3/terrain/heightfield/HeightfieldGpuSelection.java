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

import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.material.Material;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Limits;
import com.jme3.renderer.MemoryBarrier;
import com.jme3.renderer.Renderer;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.util.BufferUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Bounded GPU hierarchy selection and indexed-indirect command generation for
 * a heightfield. This reusable prototype has a fixed capacity of 85 hierarchy
 * nodes and 64 output leaves. It performs no GPU readback.
 *
 * <p>The caller owns source-page leases, page textures and residency, frame
 * fences and scheduling, transforms, and view-specific culling. Projection
 * uses the supplied camera directly, so the camera must be expressed in the
 * hierarchy's local coordinates. {@link DrawCover#Complete} is useful for
 * conservative small or multiview batches that must retain off-camera casters;
 * the primary camera still controls selection quality, and this does not
 * provide dedicated shadow quality or streaming. The caller owns GPU view
 * resources. Call {@link #dispatch(Camera, double, int, int, Predicate)} on
 * the renderer thread after the hierarchy's source page is ready for the
 * requested use.
 */
public final class HeightfieldGpuSelection implements AutoCloseable {
    /** Choose whether draw commands include frustum-culled cover patches. */
    public enum DrawCover {
        /** Draw only patches visible to the selection camera. */
        Visible,
        /** Draw the complete balanced cover, including off-camera patches. */
        Complete
    }

    private static final int NODE_CAPACITY = 85;
    private static final int LEAF_CAPACITY = 64;
    private static final int NODE_VECTORS = 6;
    private static final int RECORD_VECTORS = 1 + NODE_CAPACITY * 4;
    private static final int INPUT_BYTES = (NODE_CAPACITY * NODE_VECTORS + 7) * 16;
    private static final int RECORD_BYTES = RECORD_VECTORS * 16;
    private static final int COMMAND_BYTES = 32;
    private static final String SELECT_PATH = "Common/MatDefs/Terrain/Heightfield/HeightfieldSelect.comp";
    private static final String COMMAND_PATH = "Common/MatDefs/Terrain/Heightfield/HeightfieldCommand.comp";

    private final Renderer renderer;
    private final HeightfieldHierarchy hierarchy;
    private final DrawCover drawCover;
    private final List<HeightfieldHierarchy.Node> nodes;
    private final Map<HeightfieldHierarchy.Node, Integer> indices = new IdentityHashMap<>();
    private final BufferObject input;
    private final BufferObject patchRecords;
    private final BufferObject command;
    private final Shader selectShader;
    private final Shader commandShader;
    private boolean closed;

    /**
     * Create the bounded GPU selection resources for a hierarchy.
     * The renderer's GL resources must be created on its render thread.
     *
     * @throws IllegalArgumentException if the hierarchy exceeds fixed capacity
     * @throws IllegalStateException if required renderer capabilities or limits
     *         are unavailable
     * @throws IOException if either compute shader asset cannot be read
     */
    public HeightfieldGpuSelection(AssetManager assetManager, Renderer renderer,
            HeightfieldHierarchy hierarchy) throws IOException {
        this(assetManager, renderer, hierarchy, DrawCover.Visible);
    }

    /**
     * Create the bounded GPU selection resources for a hierarchy and draw cover.
     * {@link DrawCover#Complete} includes the full balanced patch cover in the
     * indirect draw, which can preserve off-camera casters in conservative
     * small or multiview batches. Selection quality still comes from the
     * dispatch camera; this option does not add dedicated shadow quality or
     * streaming behavior. The caller owns GPU view resources.
     *
     * @throws IllegalArgumentException if an argument is null or the hierarchy
     *         exceeds fixed capacity
     * @throws IllegalStateException if required renderer capabilities or limits
     *         are unavailable
     * @throws IOException if either compute shader asset cannot be read
     */
    public HeightfieldGpuSelection(AssetManager assetManager, Renderer renderer,
            HeightfieldHierarchy hierarchy, DrawCover drawCover) throws IOException {
        if (assetManager == null || renderer == null || hierarchy == null) {
            throw new IllegalArgumentException("asset manager, renderer, and hierarchy are required");
        }
        if (drawCover == null) throw new IllegalArgumentException("drawCover is required");
        this.renderer = renderer;
        this.hierarchy = hierarchy;
        this.drawCover = drawCover;
        nodes = hierarchy.getNodes();
        if (nodes.isEmpty() || nodes.size() > NODE_CAPACITY) {
            throw new IllegalArgumentException("heightfield hierarchy must contain 1 to 85 nodes");
        }
        long indexCount = 6L * hierarchy.getPatchCells() * hierarchy.getPatchCells();
        if (indexCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("patch index count exceeds the indirect command range");
        }
        requireCapabilities(renderer);
        for (int i = 0; i < nodes.size(); i++) indices.put(nodes.get(i), i);

        input = createBuffer(INPUT_BYTES, 0);
        patchRecords = createBuffer(RECORD_BYTES, 1);
        command = createBuffer(COMMAND_BYTES, 2);
        boolean es = renderer.getCaps().contains(Caps.OpenGLES31);
        String language = es ? "GLSL310" : "GLSL430";
        selectShader = computeShader(assetManager, SELECT_PATH, language);
        commandShader = computeShader(assetManager, COMMAND_PATH, language);
        selectShader.getUniform("m_NodeCount").setValue(VarType.Int, nodes.size());
        commandShader.getUniform("m_IndexCount").setValue(VarType.Int, (int) indexCount);
        commandShader.getUniform("m_CompleteCover").setValue(VarType.Boolean,
                drawCover == DrawCover.Complete);
    }

    /**
     * Select a balanced patch cover and build its indexed-indirect draw command.
     * A revision label is metadata carried into each output record; it must be
     * an exactly representable integer float label, from 0 through 2^24.
     *
     * @param camera camera in hierarchy-local coordinates
     * @param targetPixels positive finite screen-space error target
     * @param revisionLabel integer page/source revision label in [0, 16777216]
     * @param maxLeaves requested leaf budget in [1, 64]
     * @param isReady readiness predicate for child-page availability
     */
    public void dispatch(Camera camera, double targetPixels, int revisionLabel,
            int maxLeaves, Predicate<HeightfieldHierarchy.Node> isReady) {
        ensureOpen();
        if (camera == null || isReady == null) {
            throw new IllegalArgumentException("camera and readiness predicate are required");
        }
        if (!Double.isFinite(targetPixels) || targetPixels <= 0
                || targetPixels > Float.MAX_VALUE) {
            throw new IllegalArgumentException("targetPixels must be positive, finite, and float-representable");
        }
        float target = (float) targetPixels;
        // Never make the requested quality looser by rounding its threshold up.
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

        packInput(camera, target, revisionLabel, maxLeaves, isReady);
        // A previous frame may still read these records/commands. Order the
        // next storage writes after those GPU consumers, without CPU readback.
        renderer.memoryBarrier(EnumSet.of(MemoryBarrier.ShaderStorage));
        selectShader.getUniform("m_MaxLeaves").setValue(VarType.Int, maxLeaves);
        selectShader.getUniform("m_TargetPixels").setValue(VarType.Float, target);
        renderer.setFixedShaderStorageBufferObject(0, input);
        renderer.setFixedShaderStorageBufferObject(1, patchRecords);
        renderer.dispatchCompute(selectShader, 1, 1, 1);
        renderer.memoryBarrier(EnumSet.of(MemoryBarrier.ShaderStorage));

        renderer.setFixedShaderStorageBufferObject(1, patchRecords);
        renderer.setFixedShaderStorageBufferObject(2, command);
        renderer.dispatchCompute(commandShader, 1, 1, 1);
        renderer.memoryBarrier(EnumSet.of(MemoryBarrier.Command, MemoryBarrier.UniformBuffer,
                MemoryBarrier.ShaderStorage));
    }

    /** Selection output buffer; bind as a UBO for Heightfield.j3md draws. */
    public BufferObject getPatchRecords() {
        ensureOpen();
        return patchRecords;
    }

    /** Indexed indirect command buffer; use with Renderer.renderMeshIndirect. */
    public BufferObject getCommand() {
        ensureOpen();
        return command;
    }

    /** Configure the instance-selection ABI in the standard heightfield material. */
    public void configureMaterial(Material material) {
        ensureOpen();
        if (material == null) throw new IllegalArgumentException("material is required");
        material.setBoolean("UseHeightfieldInstances", true);
        material.setUniformBufferObject("PatchRecords", patchRecords);
        material.setInt("PatchRecordBase", drawCover == DrawCover.Complete ? 0 : NODE_CAPACITY * 2);
        material.setInt("PatchRecordVectors", NODE_CAPACITY * 4);
        material.setInt("PageCells", hierarchy.getPage().getCells());
        material.setInt("PatchGridCells", hierarchy.getPatchCells());
        material.setFloat("SampleSpacing", hierarchy.getPage().getSampleSpacing());
    }

    /** Hierarchy used to construct this selection helper. */
    public HeightfieldHierarchy getHierarchy() {
        return hierarchy;
    }

    /** Release only this helper's buffers and compute shaders. */
    @Override
    public void close() {
        if (closed) return;
        renderer.deleteBuffer(input);
        renderer.deleteBuffer(patchRecords);
        renderer.deleteBuffer(command);
        renderer.deleteShader(selectShader);
        renderer.deleteShader(commandShader);
        closed = true;
    }

    private void packInput(Camera camera, float targetPixels, int revisionLabel,
            int maxLeaves, Predicate<HeightfieldHierarchy.Node> isReady) {
        ByteBuffer bytes = input.getData().order(ByteOrder.nativeOrder());
        FloatBuffer floats = bytes.asFloatBuffer();
        IntBuffer ints = bytes.asIntBuffer();
        // The kernel always scans 85 entries. Initialize every padding slot so
        // no stale buffer content can create a candidate or child reference.
        for (int i = 0; i < NODE_CAPACITY; i++) {
            int base = i * NODE_VECTORS * 4;
            for (int k = 0; k < NODE_VECTORS * 4; k++) ints.put(base + k, 0);
            for (int k = 0; k < 4; k++) ints.put(base + k, -1);
        }
        for (int i = 0; i < nodes.size(); i++) {
            HeightfieldHierarchy.Node node = nodes.get(i);
            int base = i * NODE_VECTORS * 4;
            List<HeightfieldHierarchy.Node> children = node.getChildren();
            for (int k = 0; k < 4; k++) {
                Integer child = k < children.size() ? indices.get(children.get(k)) : null;
                if (k < children.size() && child == null) {
                    throw new IllegalStateException("hierarchy child is not in its node list");
                }
                ints.put(base + k, child == null ? -1 : child);
            }
            ints.put(base + 4, node.getLevel());
            boolean ready = isReady.test(node);
            if (i == 0 && !ready) {
                throw new IllegalStateException("the fallback root must be ready before GPU selection");
            }
            ints.put(base + 5, ready ? 1 : 0);
            floats.put(base + 8, node.getOriginX());
            floats.put(base + 9, node.getOriginZ());
            floats.put(base + 10, node.getSpan());
            floats.put(base + 11, node.getStride());
            floats.put(base + 12, ceilFloat(HeightfieldProjection.pixels(
                    camera, node.getBound(), node.getStableError())));
            floats.put(base + 13, ceilFloat(HeightfieldProjection.pixels(
                    camera, node.getBound(), node.getMorphError())));
            BoundingBox bound = node.getBound();
            Vector3f center = bound.getCenter();
            floats.put(base + 16, center.x - bound.getXExtent());
            floats.put(base + 17, center.y - bound.getYExtent());
            floats.put(base + 18, center.z - bound.getZExtent());
            floats.put(base + 20, center.x + bound.getXExtent());
            floats.put(base + 21, center.y + bound.getYExtent());
            floats.put(base + 22, center.z + bound.getZExtent());
        }

        int planeBase = NODE_CAPACITY * NODE_VECTORS * 4;
        float[][] planes = planes(camera.getViewProjectionMatrix());
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 4; j++) floats.put(planeBase + i * 4 + j, planes[i][j]);
        }
        int info = planeBase + 24;
        floats.put(info, revisionLabel);
        floats.put(info + 1, targetPixels);
        floats.put(info + 2, maxLeaves);
        floats.put(info + 3, nodes.size());
        input.setUpdateNeeded();
    }

    private static float ceilFloat(double value) {
        if (!Double.isFinite(value) || value > Float.MAX_VALUE) return Float.POSITIVE_INFINITY;
        if (value <= 0) return 0;
        float rounded = (float) value;
        return (double) rounded < value ? Math.nextUp(rounded) : rounded;
    }

    private static float[][] planes(Matrix4f matrix) {
        if (matrix == null) throw new IllegalArgumentException("camera view-projection matrix is required");
        float[][] rows = {{matrix.m00, matrix.m01, matrix.m02, matrix.m03},
            {matrix.m10, matrix.m11, matrix.m12, matrix.m13},
            {matrix.m20, matrix.m21, matrix.m22, matrix.m23},
            {matrix.m30, matrix.m31, matrix.m32, matrix.m33}};
        float[][] result = new float[6][4];
        for (int i = 0; i < 4; i++) {
            result[0][i] = rows[3][i] + rows[0][i];
            result[1][i] = rows[3][i] - rows[0][i];
            result[2][i] = rows[3][i] + rows[1][i];
            result[3][i] = rows[3][i] - rows[1][i];
            result[4][i] = rows[3][i] + rows[2][i];
            result[5][i] = rows[3][i] - rows[2][i];
        }
        return result;
    }

    private static BufferObject createBuffer(int size, int binding) {
        BufferObject result = new BufferObject();
        result.setData(BufferUtils.createByteBuffer(size).order(ByteOrder.nativeOrder()));
        result.setBinding(binding);
        result.setUpdateNeeded();
        return result;
    }

    private static Shader computeShader(AssetManager manager, String path, String language)
            throws IOException {
        Shader shader = new Shader();
        shader.addSource(Shader.ShaderType.Compute, path, readAsset(manager, path), "", language);
        return shader;
    }

    private static String readAsset(AssetManager manager, String path) throws IOException {
        AssetInfo asset = manager.locateAsset(new AssetKey<String>(path));
        if (asset == null) throw new IOException("Missing heightfield compute shader asset: " + path);
        try (InputStream input = asset.openStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int count;
            while ((count = input.read(chunk)) != -1) output.write(chunk, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void requireCapabilities(Renderer renderer) {
        if (!renderer.getCaps().contains(Caps.ComputeShader)
                || !renderer.getCaps().contains(Caps.IndexedIndirectDraw)
                || !renderer.getCaps().contains(Caps.UniformBufferObject)) {
            throw new IllegalStateException("heightfield GPU selection requires compute, indexed indirect, and UBO support");
        }
        if (renderer.getLimits().get(Limits.ComputeStorageBufferBindings) < 3
                || renderer.getLimits().get(Limits.ComputeStorageBufferBlockSize) < INPUT_BYTES
                || renderer.getLimits().get(Limits.UniformBufferObjectMaxBlockSize) < RECORD_BYTES
                || renderer.getLimits().get(Limits.ComputeWorkGroupSizeX) < 128
                || renderer.getLimits().get(Limits.ComputeWorkGroupInvocations) < 128) {
            throw new IllegalStateException("renderer storage or uniform buffer limits are below the heightfield selection ABI");
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("heightfield GPU selection is closed");
    }
}
