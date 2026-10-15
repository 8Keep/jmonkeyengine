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
package jme3test.terrain;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.terrain.heightfield.HeightfieldHierarchy.Node;
import com.jme3.terrain.heightfield.HeightfieldLod.Patch;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldReconstruction;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureArray;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;

/**
 * Example-only native readback check for the shared HeightfieldSampling LOD
 * position helper. Call {@link #verify(List, FrameBuffer)} and {@link #close()} on the
 * render/GPU thread. It checks page-coordinate XYZ, not camera transforms or the
 * renderer's frame scheduling.
 */
public final class HeightfieldLodGpuProbe implements AutoCloseable {
    private static final String MATERIAL_PATH = "jme3test/terrain/HeightfieldLodGpuProbe.j3md";
    private static final float ERROR_FLOOR = 1e-6f;
    private static final int ULP_BUDGET = 8;

    private final RenderManager renderManager;
    private final Renderer renderer;
    private final HeightfieldPage page;
    private final TextureArray heightPages;
    private final int heightLayer;
    private final boolean ownsHeightPages;
    private final Material material;
    private final com.jme3.scene.Node scene = new com.jme3.scene.Node("heightfield-lod-probe-scene");

    private ViewPort viewPort;
    private FrameBuffer target;
    private Mesh pointGrid;
    private Geometry geometry;
    private ByteBuffer readback;
    private int gridCells = -1;
    private boolean closed;

    /** Creates and owns a one-layer source array from {@code page}. */
    public HeightfieldLodGpuProbe(AssetManager assetManager, RenderManager renderManager,
            Renderer renderer, HeightfieldPage page) {
        this(assetManager, renderManager, renderer, page, createPageArray(page), 0, true);
    }

    /** Uses the indicated layer of a caller-owned source array. */
    public HeightfieldLodGpuProbe(AssetManager assetManager, RenderManager renderManager,
            Renderer renderer, HeightfieldPage page, TextureArray heightPages, int heightLayer) {
        this(assetManager, renderManager, renderer, page, heightPages, heightLayer, false);
    }

    private HeightfieldLodGpuProbe(AssetManager assetManager, RenderManager renderManager,
            Renderer renderer, HeightfieldPage page, TextureArray heightPages, int heightLayer,
            boolean ownsHeightPages) {
        if (assetManager == null || renderManager == null || renderer == null || page == null
                || heightPages == null || renderer != renderManager.getRenderer()) {
            throw new IllegalArgumentException("matching managers, renderer, page, and texture array are required");
        }
        Image image = heightPages.getImage();
        if (image == null || image.getWidth() != page.getTextureSize()
                || image.getHeight() != page.getTextureSize() || heightLayer < 0
                || (image.getData() != null && heightLayer >= image.getData().size())) {
            throw new IllegalArgumentException("texture array layer must match the supplied page dimensions");
        }
        this.renderManager = renderManager;
        this.renderer = renderer;
        this.page = page;
        this.heightPages = heightPages;
        this.heightLayer = heightLayer;
        this.ownsHeightPages = ownsHeightPages;
        material = new Material(assetManager, MATERIAL_PATH);
        material.setTexture("HeightPages", heightPages);
        material.setInt("HeightLayer", heightLayer);
        material.setFloat("SampleSpacing", page.getSampleSpacing());
        material.setBoolean("UseHeightfieldLod", true);
        material.setInt("PageCells", page.getCells());
        material.setVector2("PageLatticeOrigin", new Vector2f(0, 0));
    }

    private static TextureArray createPageArray(HeightfieldPage page) {
        if (page == null) {
            throw new IllegalArgumentException("page is required");
        }
        TextureArray result = new TextureArray(Collections.singletonList(page.createImage()));
        result.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        result.setMagFilter(Texture.MagFilter.Nearest);
        result.setWrap(Texture.WrapMode.EdgeClamp);
        return result;
    }

    /**
     * Render every supplied patch into a point-to-pixel grid and compare all
     * reconstructed XYZ values with {@link HeightfieldReconstruction}.
     * This blocks for readback and is intended for selected frames only.
     *
     * @param patches current CPU-selected LOD metadata for this page
     * @param resumeTarget explicit caller-owned framebuffer to bind afterward;
     * null deliberately selects the renderer's configured default target
     * @return sample count and maximum absolute/allowed errors
     */
    public Verification verify(List<Patch> patches, FrameBuffer resumeTarget) {
        return verifyPatches(patches, resumeTarget, -1);
    }

    /**
     * Check four-vec4 GPU morph records against independent CPU patch metadata.
     * Patches must follow record order. Each record is drawn separately through
     * the shared instance decoder; readback remains a diagnostic operation.
     */
    public Verification verifyMorphRecords(List<Patch> patches,
            com.jme3.shader.bufferobject.BufferObject records, int recordBase,
            FrameBuffer resumeTarget) {
        requireOpen();
        if (patches == null || records == null || recordBase < 0
                || (long) recordBase + patches.size() * 4L > 340
                || records.getData() == null || records.getData().limit() < 341 * 16) {
            throw new IllegalArgumentException("matching morph records and ordered patches are required");
        }
        material.setBoolean("UseHeightfieldInstances", true);
        material.setBoolean("UseHeightfieldInstanceMorphs", true);
        material.setUniformBufferObject("PatchRecords", records);
        try {
            return verifyPatches(patches, resumeTarget, recordBase);
        } finally {
            material.setBoolean("UseHeightfieldInstances", false);
            material.setBoolean("UseHeightfieldInstanceMorphs", false);
            material.clearParam("PatchRecords");
            material.setInt("PatchRecordBase", 0);
        }
    }

    private Verification verifyPatches(List<Patch> patches, FrameBuffer resumeTarget, int recordBase) {
        requireOpen();
        if (patches == null) {
            throw new IllegalArgumentException("patch metadata is required");
        }
        if (patches.isEmpty()) {
            return new Verification(0, 0, 0);
        }
        requireFloatReadbackSupport();
        int cells = gridCells(patches.get(0));
        for (Patch patch : patches) {
            if (gridCells(patch) != cells) {
                throw new IllegalArgumentException("all patches in one probe pass need the same point-grid size");
            }
        }
        Vector2f[] pageOrigins = {new Vector2f(0, 0),
            new Vector2f(page.getCells(), -page.getCells())};
        for (Vector2f origin : pageOrigins) {
            validatePageOrigin(patches, origin);
        }
        ensureTarget(cells);

        int side = cells + 1;
        int compared = 0;
        float maxError = 0;
        float maxTolerance = 0;
        Vector3f expectedPosition = new Vector3f();
        for (Vector2f pageOrigin : pageOrigins) {
            material.setVector2("PageLatticeOrigin", pageOrigin);
            for (int patchIndex = 0; patchIndex < patches.size(); patchIndex++) {
                Patch patch = patches.get(patchIndex);
                configure(patch, cells);
                if (recordBase >= 0) material.setInt("PatchRecordBase", recordBase + patchIndex * 4);
                // Resume an explicit caller target. The engine's current-FBO
                // query can report a main override rather than the physical
                // offscreen binding, so this probe must not infer it.
                FloatBuffer actual;
                try {
                    renderManager.renderViewPort(viewPort, 0f);
                    readback.clear();
                    renderer.readFrameBufferWithFormat(target, readback, Image.Format.RGBA32F);
                    readback.rewind();
                    actual = readback.asFloatBuffer();
                } finally {
                    renderer.setFrameBuffer(resumeTarget);
                }
                Node node = patch.getNode();
                int stride = node.getStride();
                for (int z = 0; z <= cells; z++) {
                    for (int x = 0; x <= cells; x++) {
                        int offset = (z * side + x) * 4;
                        float alpha = morphForVertex(patch, x, z, cells);
                        int pageX = node.getOriginX() + x * stride;
                        int pageZ = node.getOriginZ() + z * stride;
                        HeightfieldReconstruction.morphVertex(page, pageX, pageZ,
                                stride, alpha, expectedPosition);
                        float[] expected = {expectedPosition.x,
                            expectedPosition.y, expectedPosition.z, 1f};
                        for (int channel = 0; channel < 4; channel++) {
                            float value = actual.get(offset + channel);
                            float tolerance = tolerance(expected[channel], value);
                            if (!Float.isFinite(value)) {
                                throw mismatch(patch, x, z, channel, expected[channel], value,
                                        tolerance, "non-finite framebuffer value");
                            }
                            float error = Math.abs(value - expected[channel]);
                            maxError = Math.max(maxError, error);
                            maxTolerance = Math.max(maxTolerance, tolerance);
                            compared++;
                            if (channel == 3 && value != 1f) {
                                throw mismatch(patch, x, z, channel, expected[channel], value,
                                        tolerance, "alpha must prove the point was drawn");
                            }
                            if (error > tolerance) {
                                throw mismatch(patch, x, z, channel, expected[channel], value,
                                        tolerance, "GPU/CPU reconstruction differs");
                            }
                        }
                    }
                }
            }
        }
        return new Verification(compared, maxError, maxTolerance);
    }

    /**
     * Check a decimated node page in a caller-owned streaming atlas against the
     * canonical source supplied to this probe. Each node has its own complete
     * grid and halo; its page origin is expressed in that node's sample lattice.
     * The shader outputs local coordinates, so the node translation is added
     * here before comparison with the canonical CPU oracle. This checks actual
     * layer contents and uniforms after incremental upload or slot reuse.
     */
    public Verification verifyNodePage(Patch patch, HeightfieldPage payload,
            TextureArray atlas, int layer, FrameBuffer resumeTarget) {
        requireOpen();
        requireFloatReadbackSupport();
        int cells = gridCells(patch);
        Node node = patch.getNode();
        if (payload == null || atlas == null || payload.getCells() != cells
                || atlas.getImage().getWidth() != payload.getTextureSize()
                || atlas.getImage().getHeight() != payload.getTextureSize()
                || layer < 0 || layer >= atlas.getImage().getData().size()
                || payload.getSampleSpacing() != page.getSampleSpacing() * node.getStride()
                || node.getOriginX() % node.getStride() != 0
                || node.getOriginZ() % node.getStride() != 0) {
            throw new IllegalArgumentException("node page, layer and spacing must match the canonical patch");
        }
        ensureTarget(cells);
        material.setTexture("HeightPages", atlas);
        material.setInt("HeightLayer", layer);
        material.setFloat("SampleSpacing", payload.getSampleSpacing());
        material.setInt("PageCells", cells);
        configure(patch, cells);
        material.setVector2("PageLatticeOrigin", new Vector2f(
                node.getOriginX() / node.getStride(), node.getOriginZ() / node.getStride()));
        material.setVector2("PatchLatticeOrigin", new Vector2f(0, 0));
        material.setInt("SampleStride", 1);
        FloatBuffer actual;
        try {
            renderManager.renderViewPort(viewPort, 0f);
            readback.clear();
            renderer.readFrameBufferWithFormat(target, readback, Image.Format.RGBA32F);
            readback.rewind();
            actual = readback.asFloatBuffer();
        } finally {
            renderer.setFrameBuffer(resumeTarget);
        }
        float originX = node.getOriginX() * page.getSampleSpacing();
        float originZ = node.getOriginZ() * page.getSampleSpacing();
        Vector3f expected = new Vector3f();
        int compared = 0;
        float maximum = 0, allowed = 0;
        for (int z = 0; z <= cells; z++) for (int x = 0; x <= cells; x++) {
            HeightfieldReconstruction.morphVertex(page,
                    node.getOriginX() + x * node.getStride(),
                    node.getOriginZ() + z * node.getStride(), node.getStride(),
                    morphForVertex(patch, x, z, cells), expected);
            int offset = (z * (cells + 1) + x) * 4;
            float[] values = {actual.get(offset) + originX, actual.get(offset + 1),
                actual.get(offset + 2) + originZ, actual.get(offset + 3)};
            float[] oracle = {expected.x, expected.y, expected.z, 1};
            for (int channel = 0; channel < 4; channel++) {
                float value = values[channel];
                float limit = tolerance(oracle[channel], value);
                float error = Math.abs(value - oracle[channel]);
                if (!Float.isFinite(value) || error > limit || (channel == 3 && value != 1)) {
                    throw mismatch(patch, x, z, channel, oracle[channel], value, limit,
                            "streamed node page differs from canonical source");
                }
                maximum = Math.max(maximum, error);
                allowed = Math.max(allowed, limit);
                compared++;
            }
        }
        // Leave the ordinary resident verification API usable on this probe.
        material.setTexture("HeightPages", heightPages);
        material.setInt("HeightLayer", heightLayer);
        material.setFloat("SampleSpacing", page.getSampleSpacing());
        material.setInt("PageCells", page.getCells());
        return new Verification(compared, maximum, allowed);
    }

    private void configure(Patch patch, int cells) {
        Node node = patch.getNode();
        if (node.getOriginX() < 0 || node.getOriginZ() < 0
                || node.getOriginX() + node.getSpan() > page.getCells()
                || node.getOriginZ() + node.getSpan() > page.getCells()
                || node.getSpan() != cells * node.getStride()) {
            throw new IllegalArgumentException("patch must fit its page and match the probe point grid");
        }
        validateMorph(patch.getMorph());
        for (int edge = 0; edge < 4; edge++) {
            validateMorph(patch.getEdgeMorph(edge));
        }
        material.setInt("PatchGridCells", cells);
        material.setVector2("PatchLatticeOrigin",
                new Vector2f(node.getOriginX(), node.getOriginZ()));
        material.setInt("SampleStride", node.getStride());
        material.setFloat("InteriorMorph", patch.getMorph());
        // Patch uses left/right/back/front. In shader terms, z=0 is South.
        material.setFloat("EdgeMorphWest", patch.getEdgeMorph(0));
        material.setFloat("EdgeMorphEast", patch.getEdgeMorph(1));
        material.setFloat("EdgeMorphSouth", patch.getEdgeMorph(2));
        material.setFloat("EdgeMorphNorth", patch.getEdgeMorph(3));
    }

    private int gridCells(Patch patch) {
        if (patch == null || patch.getNode() == null || patch.getNode().getStride() <= 0
                || patch.getNode().getSpan() % patch.getNode().getStride() != 0) {
            throw new IllegalArgumentException("patch node must have a positive integral stride");
        }
        return patch.getNode().getSpan() / patch.getNode().getStride();
    }

    private void validatePageOrigin(List<Patch> patches, Vector2f pageOrigin) {
        int originX = (int) pageOrigin.x;
        int originZ = (int) pageOrigin.y;
        if (pageOrigin.x != originX || pageOrigin.y != originZ
                || Math.abs(originX) > (1 << 24) || Math.abs(originZ) > (1 << 24)) {
            throw new IllegalArgumentException("probe page origins must be exactly representable integer floats");
        }
        for (Patch patch : patches) {
            int parentStride = patch.getNode().getStride() * 2;
            if (Math.floorMod(originX, parentStride) != 0
                    || Math.floorMod(originZ, parentStride) != 0) {
                throw new IllegalArgumentException("probe page origin must align to every patch parent stride");
            }
        }
    }

    private static void validateMorph(float value) {
        if (!Float.isFinite(value) || value < 0f || value > 1f) {
            throw new IllegalArgumentException("patch morph values must be finite and in [0,1]");
        }
    }

    private static float morphForVertex(Patch patch, int x, int z, int cells) {
        if ((x == 0 || x == cells) && (z == 0 || z == cells)) {
            return 0f;
        }
        float alpha = patch.getMorph();
        if (x == 0) alpha = Math.max(alpha, patch.getEdgeMorph(0));
        if (x == cells) alpha = Math.max(alpha, patch.getEdgeMorph(1));
        if (z == 0) alpha = Math.max(alpha, patch.getEdgeMorph(2));
        if (z == cells) alpha = Math.max(alpha, patch.getEdgeMorph(3));
        return alpha;
    }

    private void ensureTarget(int cells) {
        if (gridCells == cells) {
            return;
        }
        if (geometry != null) {
            scene.detachChild(geometry);
            deleteMeshBuffers();
        }
        if (target != null) {
            viewPort.setOutputFrameBuffer(null);
            renderer.deleteFrameBuffer(target);
        }

        gridCells = cells;
        int side = cells + 1;
        pointGrid = new Mesh();
        pointGrid.setMode(Mesh.Mode.Points);
        float[] positions = new float[side * side * 3];
        int next = 0;
        for (int z = 0; z <= cells; z++) {
            for (int x = 0; x <= cells; x++) {
                positions[next++] = x;
                positions[next++] = 0;
                positions[next++] = z;
            }
        }
        pointGrid.setBuffer(Type.Position, 3, positions);
        pointGrid.setStatic();
        pointGrid.updateBound();
        geometry = new Geometry("heightfield-lod-probe-points", pointGrid);
        geometry.setMaterial(material);
        geometry.setCullHint(Spatial.CullHint.Never);
        geometry.setQueueBucket(RenderQueue.Bucket.Opaque);
        scene.attachChild(geometry);
        scene.setCullHint(Spatial.CullHint.Never);
        scene.updateGeometricState();

        target = new FrameBuffer(side, side, 1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        if (viewPort == null) {
            Camera camera = new Camera(side, side);
            // Unregistered: this diagnostic renders only when verify() asks,
            // rather than adding an otherwise unused pass to every frame.
            viewPort = new ViewPort(
                    "Heightfield LOD GPU Probe " + Integer.toHexString(System.identityHashCode(this)), camera);
            viewPort.attachScene(scene);
            viewPort.setBackgroundColor(new ColorRGBA(0, 0, 0, 0));
            viewPort.setClearFlags(true, false, false);
        }
        viewPort.setOutputFrameBuffer(target);
        readback = BufferUtils.createByteBuffer(side * side * 4 * Float.BYTES)
                .order(ByteOrder.nativeOrder());
    }

    private void deleteMeshBuffers() {
        if (pointGrid != null) {
            for (VertexBuffer buffer : pointGrid.getBufferList().getArray()) {
                renderer.deleteBuffer(buffer);
            }
            pointGrid = null;
        }
    }

    private void requireFloatReadbackSupport() {
        for (Caps cap : new Caps[]{Caps.TextureArray, Caps.VertexTextureFetch,
                Caps.FloatTexture, Caps.FloatColorBufferRGBA}) {
            if (!renderer.getCaps().contains(cap)) {
                throw new UnsupportedOperationException("heightfield GPU probe requires " + cap);
            }
        }
        if (renderer.getLimits().get(Limits.VertexTextureUnits) < 1) {
            throw new UnsupportedOperationException("heightfield GPU probe requires a vertex texture unit");
        }
        if (!renderer.getCaps().contains(Caps.OpenGLES30)
                && !renderer.getCaps().contains(Caps.GLSL150)) {
            throw new UnsupportedOperationException("heightfield GPU probe requires GLSL 150 or OpenGL ES 3");
        }
    }

    private static float tolerance(float expected, float actual) {
        float ulp = Math.max(Math.ulp(expected), Math.ulp(actual));
        return Math.max(ERROR_FLOOR, ULP_BUDGET * ulp);
    }

    private static IllegalStateException mismatch(Patch patch, int x, int z, int channel,
            float expected, float actual, float tolerance, String reason) {
        Node node = patch.getNode();
        return new IllegalStateException(reason + " at patch " + node.getOriginX() + ","
                + node.getOriginZ() + " vertex " + x + "," + z + " channel " + channel
                + ": expected=" + expected + " actual=" + actual + " tolerance=" + tolerance);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("probe is closed");
        }
        if (renderer != renderManager.getRenderer()) {
            throw new IllegalStateException("probe renderer no longer matches its RenderManager");
        }
    }

    /** Must be called on the render/GPU thread; the supplied texture array is never disposed. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (viewPort != null) {
            viewPort.clearScenes();
            viewPort = null;
        }
        if (target != null) {
            renderer.deleteFrameBuffer(target);
            target = null;
        }
        deleteMeshBuffers();
        geometry = null;
        if (ownsHeightPages) {
            renderer.deleteImage(heightPages.getImage());
        }
        readback = null;
    }

    /** Aggregate result; every patch vertex contributes four checked channels. */
    public static final class Verification {
        private final int comparedChannels;
        private final float maximumAbsoluteError;
        private final float maximumAllowedError;

        private Verification(int comparedChannels, float maximumAbsoluteError,
                float maximumAllowedError) {
            this.comparedChannels = comparedChannels;
            this.maximumAbsoluteError = maximumAbsoluteError;
            this.maximumAllowedError = maximumAllowedError;
        }

        public int getComparedChannels() {
            return comparedChannels;
        }

        public float getMaximumAbsoluteError() {
            return maximumAbsoluteError;
        }

        public float getMaximumAllowedError() {
            return maximumAllowedError;
        }

        @Override
        public String toString() {
            return "HeightfieldLodGpuProbe.Verification{channels=" + comparedChannels
                    + ", maxAbsError=" + maximumAbsoluteError
                    + ", maxAllowedError=" + maximumAllowedError + "}";
        }
    }
}
