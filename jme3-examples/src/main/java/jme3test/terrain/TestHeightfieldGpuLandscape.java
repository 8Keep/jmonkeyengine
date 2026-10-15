/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
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

import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetKey;
import com.jme3.bounding.BoundingBox;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.light.LightList;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.opengl.GLFence;
import com.jme3.renderer.Limits;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Mesh;
import com.jme3.scene.Geometry;
import com.jme3.scene.instancing.IndirectGeometry;
import com.jme3.scene.instancing.InstancedGeometry;
import com.jme3.shader.Shader;
import com.jme3.shader.ShaderBufferBlock.BufferType;
import com.jme3.shader.VarType;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.system.AppSettings;
import com.jme3.terrain.heightfield.HeightfieldGrid;
import com.jme3.terrain.heightfield.HeightfieldGpuSelection;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldLod;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldProjection;
import com.jme3.terrain.heightfield.HeightfieldResidency;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureArray;
import com.jme3.texture.Texture2D;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Finite GPU-selected indexed-indirect heightfield landscape and CPU image oracle. */
public final class TestHeightfieldGpuLandscape extends SimpleApplication {
    private static final int CELLS = 128, PATCH_CELLS = 16, NODES = 85, LEAF_LIMIT = 64;
    private static final int RECORD_VECS = 1 + NODES * 4;
    private static final int WIDTH = 400, HEIGHT = 300;
    private static final float SPACING = .5f;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Path output;
    private final boolean materialMode;
    private volatile Throwable failure;
    private HeightfieldPage page;
    private HeightfieldHierarchy hierarchy;
    private HeightfieldLod oracle;
    private List<HeightfieldHierarchy.Node> nodes;
    private Map<HeightfieldHierarchy.Node, Integer> indices;
    private int checks;
    private HeightfieldGpuSelection selection;
    private HeightfieldResidency residency;
    private HeightfieldResidency.PageKey residentKey;
    private HeightfieldResidency.Handle rootHandle;

    private TestHeightfieldGpuLandscape(Path output, boolean materialMode) {
        this.output = output;
        this.materialMode = materialMode;
    }

    public static void main(String[] args) throws Exception {
        TestHeightfieldGpuLandscape app = new TestHeightfieldGpuLandscape(Paths.get(
                args.length > 1 ? args[1] : "build/heightfield-gpu-landscape"),
                args.length > 2 && "--materials".equals(args[2]));
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length > 0 ? args[0] : AppSettings.LWJGL_OPENGL45);
        settings.setResolution(WIDTH, HEIGHT);
        settings.setAudioRenderer(null);
        settings.setVSync(false);
        settings.setGraphicsDebug(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (!app.finished.await(90, TimeUnit.SECONDS)) {
            app.stop(false);
            throw new IllegalStateException("GPU landscape capture timed out", app.failure);
        }
        app.stop(true);
        if (app.failure != null) throw new IllegalStateException("GPU landscape failed", app.failure);
        System.out.println("HEIGHTFIELD_GPU_LANDSCAPE_PASS checks=" + app.checks
                + " output=" + app.output.toAbsolutePath());
    }

    @Override public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            runProbe(renderManager.getRenderer());
        } catch (Throwable error) {
            handleError("GPU landscape", error);
        } finally {
            finished.countDown();
        }
    }

    @Override public void handleError(String message, Throwable error) {
        failure = error == null ? new IllegalStateException(message) : error;
        failure.printStackTrace();
        finished.countDown();
    }

    private void runProbe(Renderer renderer) throws Exception {
        require(renderer.getCaps().contains(Caps.ComputeShader)
                && renderer.getCaps().contains(Caps.IndexedIndirectDraw)
                && renderer.getCaps().contains(Caps.UniformBufferObject), "compute, UBO, and indexed-indirect support");
        HeightfieldGrid.requireSupported(renderer);
        boolean es = renderer.getCaps().contains(Caps.OpenGLES31);
        require(renderer.getLimits().get(Limits.ComputeStorageBufferBindings) >= 3, "three compute storage bindings");
        require(renderer.getLimits().get(Limits.ComputeStorageBufferBlockSize) >= RECORD_VECS * 16,
                "selection output storage size");
        require(renderer.getLimits().get(Limits.UniformBufferObjectMaxBlockSize) >= RECORD_VECS * 16,
                "landscape selection UBO size");
        require(renderer.getLimits().get(Limits.VertexTextureUnits) > 0, "vertex texture fetch unit");
        Files.createDirectories(output);

        page = makePage();
        hierarchy = new HeightfieldHierarchy(page, PATCH_CELLS, 0);
        oracle = new HeightfieldLod(hierarchy);
        nodes = hierarchy.getNodes();
        require(nodes.size() == NODES, "canonical 128-cell hierarchy node count");
        indices = new IdentityHashMap<>();
        for (int i = 0; i < nodes.size(); i++) indices.put(nodes.get(i), i);

        float[] emptySamples = new float[(CELLS + 3) * (CELLS + 3)];
        HeightfieldPage emptyPage = new HeightfieldPage(CELLS, SPACING, emptySamples);
        TextureArray heights = new TextureArray(java.util.Collections.singletonList(emptyPage.createImage()));
        heights.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        heights.setMagFilter(Texture.MagFilter.Nearest);
        heights.setWrap(Texture.WrapMode.EdgeClamp);

        // One canonical fine page backs every candidate patch in this bounded proof.
        // The residency mapping is published only after the renderer upload succeeds.
        long pageBytes = (long) (CELLS + 3) * (CELLS + 3) * Float.BYTES;
        residency = new HeightfieldResidency(CELLS, new HeightfieldResidency.Limits(
                1, 1, pageBytes, pageBytes, 1, pageBytes, 0, 1));
        residentKey = new HeightfieldResidency.PageKey(1, 0, 0, 1);
        HeightfieldResidency.Request request = residency.request(residentKey);
        require(request != null && residency.complete(request, page), "prepare canonical height page for residency");
        require(residency.uploadPrepared((slot, uploadPage) -> {
            if (slot != 0) throw new IllegalStateException("single-page texture layer must be zero");
            Image upload = uploadPage.createImage();
            try { renderer.modifyTexture(heights, upload, slot, 0, 0); }
            finally { BufferUtils.destroyDirectBuffer(upload.getData(0)); }
        }) == 1, "renderer uploaded the canonical height page before publishing residency");
        rootHandle = residency.ready(residentKey);
        require(rootHandle != null && rootHandle.getSlot() == 0 && residency.isCurrent(rootHandle),
                "canonical page has a current slot-zero generation");
        require(residency.pin(residentKey), "pin the canonical root page for the probe");

        selection = new HeightfieldGpuSelection(assetManager, renderer, hierarchy);
        BufferObject gpuRecords = selection.getPatchRecords();
        BufferObject cpuRecords = buffer(RECORD_VECS * 16, 0);
        BufferObject command = selection.getCommand();

        Mesh grid = new HeightfieldGrid(PATCH_CELLS).createLodPatchMesh(hierarchy.getRoot());
        grid.setStatic();

        Shader gpuDraw = landscapeShader(es, gpuRecords);
        Shader cpuDraw = landscapeShader(es, cpuRecords);
        RenderState state = new RenderState();
        state.setDepthTest(true);
        state.setDepthWrite(true);
        state.setFaceCullMode(RenderState.FaceCullMode.Back);
        state.setBlendMode(RenderState.BlendMode.Off);

        List<Scenario> scenarios = new ArrayList<>();
        scenarios.add(new Scenario("normal", camera(new Vector3f(32, 30, 72), new Vector3f(32, 0, 32)), 1.25, 1));
        scenarios.add(new Scenario("camera-cut", camera(new Vector3f(-42, 25, 9), new Vector3f(10, 0, 55)), .8, 2));
        scenarios.add(new Scenario("source-revision", camera(new Vector3f(64, 58, -14), new Vector3f(32, 0, 32)), 1.0, 9));

        StringBuilder summary = new StringBuilder();
        summary.append("Synthetic immutable one-page hills; CPU projection errors and synthetic hierarchy readiness.\n")
                .append("One live HeightfieldResidency pool slot (key revision=1,x=0,z=0,stride=1) uploads the shared fine source texture; every candidate patch uses that same page. Scenario revision labels are metadata echoes, not height edits.\n")
                .append("RGBA32F linear readback; PNG uses clamp-to-[0,1] then IEC sRGB transfer; no tone mapper.\n")
                .append("GPU hierarchy selector -> compact records -> compute indirect command -> indexed draw; CPU oracle uses ordinary instancing.\n");
        summary.append("shading=").append(materialMode ? "ordinary Heightfield PBR material, two direct lights and ambient fill" : "diagnostic fine-gradient diffuse").append('\n');
        for (Scenario scenario : scenarios) {
            Capture result = capture(renderer, scenario, gpuRecords, cpuRecords,
                    command, gpuDraw, cpuDraw, grid, heights, state, es);
            summary.append(scenario.name).append(" leaves=").append(result.leaves)
                    .append(" gpuVisible=").append(result.gpuVisible)
                    .append(" cpuVisible=").append(result.cpuVisible)
                    .append(" gpuErrorPixels=").append(result.gpuError)
                    .append(" cpuErrorPixels=").append(result.cpuError)
                    .append(" imageMaxError=").append(result.imageError)
                    .append(" differingPixels=").append(result.differingPixels)
                    .append(" terrainPixels=").append(result.terrainPixels)
                    .append(" leaseKey=").append(residentKey)
                    .append(" slot=").append(result.slot)
                    .append(" generation=").append(result.generation)
                    .append(" frameLeaseCompleted=").append(result.leaseCompleted).append('\n');
        }
        summary.append("renderer=").append(renderer.getCaps()).append('\n')
                .append("limits=").append(renderer.getLimits()).append('\n');
        Files.write(output.resolve("summary.txt"), summary.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        require(residency.unpin(residentKey), "unpin canonical page after all submitted frames completed");
        require(residency.evict(residentKey), "evict canonical page after its GPU fence completed");
        require(!residency.isRetained(rootHandle) && residency.stats().getReadySlots() == 0,
                "eviction retired the exact root generation after lease release");
        selection.close();
        renderer.deleteImage(heights.getImage());
        residency.close();
        renderer.setFrameBuffer(null);
    }

    private Capture capture(Renderer renderer, Scenario scenario, BufferObject gpuRecords, BufferObject cpuRecords, BufferObject command,
            Shader gpuDraw, Shader cpuDraw,
            Mesh grid, TextureArray heights, RenderState state, boolean es) throws Exception {
        HeightfieldResidency.Frame lease = residency.acquire(java.util.Collections.singleton(residentKey));
        require(lease != null, scenario.name + " acquires the canonical source-page lease");
        HeightfieldResidency.Handle frameRoot = lease.get(residentKey);
        require(frameRoot != null && frameRoot.getSlot() == rootHandle.getSlot()
                && frameRoot.getGeneration() == rootHandle.getGeneration()
                && residency.get(frameRoot) == page && residency.isCurrent(frameRoot),
                scenario.name + " retains the exact current root key and generation");
        boolean submitted = false;
        try {
            HeightfieldLod.Selection cpu = oracle.select(scenario.camera, scenario.targetPixels,
                    LEAF_LIMIT, node -> true);
            List<HeightfieldHierarchy.Node> cpuCover = cpu.getLeaves();
            require(!cpuCover.isEmpty() && cpuCover.size() <= LEAF_LIMIT, scenario.name + " CPU cover bounded");
            selection.dispatch(scenario.camera, scenario.targetPixels, scenario.revision,
                    LEAF_LIMIT, node -> residency.isCurrent(frameRoot));

            int cpuVisible = countVisible(scenario, cpuCover);
            packCpuRecords(scenario, cpuCover, cpuRecords);
            cpuDraw.getBufferBlock("m_Selection").setBufferObject(BufferType.UniformBufferObject, cpuRecords);
            gpuDraw.getBufferBlock("m_Selection").setBufferObject(BufferType.UniformBufferObject, gpuRecords);
            FrameBuffer gpuTarget = target(WIDTH, HEIGHT);
            FrameBuffer cpuTarget = target(WIDTH, HEIGHT);
            FrameBuffer gpuShadow = materialMode ? target(WIDTH, HEIGHT) : null;
            FrameBuffer cpuShadow = materialMode ? target(WIDTH, HEIGHT) : null;
            require(residency.isRetained(frameRoot), scenario.name + " page generation remains retained before drawing");
            if (materialMode) {
                drawMaterial(renderer, scenario, gpuRecords, grid, heights, gpuTarget, 0, command, frameRoot.getSlot(), false);
                drawMaterial(renderer, scenario, cpuRecords, grid, heights, cpuTarget, cpuVisible, null, frameRoot.getSlot(), false);
                drawMaterial(renderer, scenario, gpuRecords, grid, heights, gpuShadow, 0, command, frameRoot.getSlot(), true);
                drawMaterial(renderer, scenario, cpuRecords, grid, heights, cpuShadow, cpuVisible, null, frameRoot.getSlot(), true);
            } else {
                drawGpu(renderer, scenario, gpuDraw, gpuRecords, grid, heights, state, gpuTarget, 0, true, command, frameRoot.getSlot());
                drawGpu(renderer, scenario, cpuDraw, cpuRecords, grid, heights, state, cpuTarget, cpuVisible, false, command, frameRoot.getSlot());
            }
            submitFrame(renderer, lease);
            submitted = true;
            waitForFrames();
            // Diagnostic readback follows submission; drawing never needs a CPU count.
            int gpuVisible = decodeCommand(renderer, command, es);
            require(gpuVisible == cpuVisible, scenario.name + " GPU command count matches CPU visible cover count");
            double gpuError = verifyGpuCover(renderer, scenario, gpuRecords, cpuCover, es);
            if (materialMode) verifyDepth(renderer, scenario, gpuTarget, cpuTarget, gpuShadow, cpuShadow, es);
            FloatBuffer gpuPixels = read(renderer, gpuTarget);
            FloatBuffer cpuPixels = read(renderer, cpuTarget);
            Comparison comparison = compare(scenario, gpuPixels, cpuPixels);
            writePng(output.resolve(scenario.name + "-gpu.png"), gpuPixels);
            writePng(output.resolve(scenario.name + "-cpu.png"), cpuPixels);
            System.out.println("HEIGHTFIELD_GPU_LANDSCAPE_COMPARE scenario=" + scenario.name
                    + " gpuVisible=" + gpuVisible + " cpuVisible=" + cpuVisible
                    + " imageMaxError=" + comparison.maxError + " differingPixels=" + comparison.differingPixels
                    + " terrainPixels=" + comparison.terrainPixels);
            require(comparison.terrainPixels > WIDTH * HEIGHT / 12,
                    scenario.name + " native image contains substantial terrain pixels");
            require(comparison.maxError <= 2e-5f, scenario.name + " indirect GPU landscape matches CPU-instanced oracle");

            require(gpuError <= scenario.targetPixels + 4.0 * Math.ulp((float) scenario.targetPixels),
                    scenario.name + " decoded GPU cover meets target");
            require(gpuVisible > 1 && cpuVisible > 1, scenario.name + " actual GPU and CPU draws use multiple patch instances");
            checks += comparison.compared;
            System.out.println("HEIGHTFIELD_GPU_LANDSCAPE scenario=" + scenario.name + " leaves=" + cpuCover.size()
                    + " visible=" + gpuVisible + " terrainPixels=" + comparison.terrainPixels
                    + " imageMaxError=" + comparison.maxError + " checks=" + checks + " gles=" + es);
            return new Capture(cpuCover.size(), gpuVisible, cpuVisible, gpuError, cpu.getMaximumPixels(),
                    comparison.maxError, comparison.differingPixels, comparison.terrainPixels,
                    frameRoot.getSlot(), frameRoot.getGeneration(), true);
        } finally {
            if (!submitted) require(residency.abort(lease), scenario.name + " aborts an unused page lease");
        }
    }

    private void drawGpu(Renderer renderer, Scenario scenario, Shader shader, BufferObject records,
            Mesh grid, TextureArray heights, RenderState state, FrameBuffer target, int instances,
            boolean indirect, BufferObject command, int heightLayer) throws Exception {
        shader.getUniform("m_ViewProjection").setValue(VarType.Matrix4, scenario.camera.getViewProjectionMatrix());
        shader.getUniform("m_HeightPages").setValue(VarType.Int, 0);
        shader.getUniform("m_HeightLayer").setValue(VarType.Int, heightLayer);
        shader.getUniform("m_SampleSpacing").setValue(VarType.Float, SPACING);
        shader.getUniform("m_PageLatticeOrigin").setValue(VarType.Vector2, new com.jme3.math.Vector2f(0, 0));
        shader.getUniform("m_PageCells").setValue(VarType.Int, CELLS);
        renderer.setFrameBuffer(target);
        renderer.setViewPort(0, 0, WIDTH, HEIGHT);
        renderer.setBackgroundColor(new ColorRGBA(.025f, .04f, .065f, 1));
        renderer.clearBuffers(true, true, false);
        renderer.applyRenderState(state);
        renderer.setTexture(0, heights);
        renderer.setShader(shader);
        if (indirect) renderer.renderMeshIndirect(grid, 0, command, 0, null);
        else renderer.renderMesh(grid, 0, instances, null);
    }

    private void submitFrame(Renderer renderer, HeightfieldResidency.Frame frame) {
        GLFence fence = renderer.createFence();
        boolean registered = residency.submit(frame, new HeightfieldResidency.Completion() {
            @Override public boolean isComplete() { return renderer.isFenceSignaled(fence); }
            @Override public void close() { renderer.deleteFence(fence); }
        });
        if (!registered) {
            renderer.deleteFence(fence);
            throw new IllegalStateException("could not register renderer fence for page lease");
        }
    }

    private void drawMaterial(Renderer renderer, Scenario scenario, BufferObject records,
            Mesh grid, TextureArray heights, FrameBuffer target, int cpuInstances,
            BufferObject command, int heightLayer, boolean depthOnly) {
        Geometry batch;
        if (command != null) {
            batch = new IndirectGeometry("GPU terrain", grid, command, 0, hierarchy.getRoot().getBound());
        } else {
            // Exercise the unchanged ordinary instancing/material path as the oracle.
            InstancedGeometry ordinary = new InstancedGeometry("CPU terrain");
            ordinary.setMesh(grid);
            ordinary.setMaxNumInstances(Math.max(1, cpuInstances));
            for (int i = 0; i < cpuInstances; i++) {
                Geometry instance = new Geometry("reference patch", grid);
                instance.updateGeometricState();
                ordinary.addInstance(instance);
            }
            ordinary.updateInstances(null);
            batch = ordinary;
        }
        Material material = new Material(assetManager, "Common/MatDefs/Terrain/Heightfield/Heightfield.j3md");
        material.setTexture("HeightPages", heights);
        material.setInt("HeightLayer", heightLayer);
        material.setFloat("SampleSpacing", SPACING);
        if (records == selection.getPatchRecords()) {
            selection.configureMaterial(material);
        } else {
            material.setBoolean("UseHeightfieldInstances", true);
            material.setUniformBufferObject("PatchRecords", records);
            material.setInt("PatchRecordBase", NODES * 2);
            material.setInt("PatchRecordVectors", NODES * 4);
            material.setInt("PageCells", CELLS);
            material.setInt("PatchGridCells", PATCH_CELLS);
        }
        material.setFloat("SnowLine", 6.8f);
        material.setFloat("SnowFade", .8f);
        material.setFloat("TextureScale", .2f);
        if (depthOnly) material.selectTechnique("PreShadow", renderManager);
        batch.setMaterial(material);
        batch.updateGeometricState();
        require(batch.getWorldBound().contains(hierarchy.getRoot().getBound().getCenter()),
                "material batch retains its source bound");

        LightList lights = new LightList(batch);
        AmbientLight ambient = new AmbientLight();
        ambient.setColor(new ColorRGBA(.28f, .34f, .43f, 1));
        lights.add(ambient);
        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-.55f, -.8f, -.35f).normalizeLocal());
        sun.setColor(new ColorRGBA(1.7f, 1.55f, 1.35f, 1));
        lights.add(sun);
        DirectionalLight fill = new DirectionalLight();
        fill.setDirection(new Vector3f(.7f, -.5f, .4f).normalizeLocal());
        fill.setColor(new ColorRGBA(.15f, .22f, .35f, 1));
        lights.add(fill);
        renderManager.setCamera(scenario.camera, false);
        renderManager.setMaxSinglePassLightBatchSize(1);
        renderer.setFrameBuffer(target);
        renderer.setViewPort(0, 0, WIDTH, HEIGHT);
        renderer.setBackgroundColor(new ColorRGBA(.025f, .04f, .065f, 1));
        renderer.clearBuffers(true, true, false);
        RenderState previous = renderManager.getForcedRenderState();
        try {
            if (depthOnly) {
                // Compare caster geometry without raster bias obscuring position equality.
                RenderState unbiasedDepth = new RenderState();
                unbiasedDepth.setColorWrite(false);
                unbiasedDepth.setDepthTest(true);
                unbiasedDepth.setDepthWrite(true);
                unbiasedDepth.setFaceCullMode(RenderState.FaceCullMode.Back);
                renderManager.setForcedRenderState(unbiasedDepth);
            }
            renderManager.renderGeometry(batch, lights);
        } finally {
            renderManager.setForcedRenderState(previous);
        }
    }

    private void verifyDepth(Renderer renderer, Scenario scenario, FrameBuffer gpu,
            FrameBuffer cpu, FrameBuffer gpuShadow, FrameBuffer cpuShadow, boolean es) throws Exception {
        FloatBuffer[] depth = {readDepth(renderer, gpu, es), readDepth(renderer, cpu, es),
            readDepth(renderer, gpuShadow, es), readDepth(renderer, cpuShadow, es)};
        float maximum = 0;
        for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
            float expected = depth[0].get(pixel * 4);
            for (int pass = 1; pass < depth.length; pass++) {
                float value = depth[pass].get(pixel * 4);
                require(Float.isFinite(value) && value >= 0 && value <= 1, "finite native depth sample");
                maximum = Math.max(maximum, Math.abs(value - expected));
            }
        }
        require(maximum <= 2f / 16777215f, scenario.name + " material and unbiased caster depth match");
        System.out.println("HEIGHTFIELD_GPU_MATERIAL_DEPTH scenario=" + scenario.name + " maxError=" + maximum);
    }

    private FloatBuffer readDepth(Renderer renderer, FrameBuffer source, boolean es) throws Exception {
        Shader decoder = new Shader();
        String language = es ? "GLSL300" : "GLSL150";
        String precision = es ? "precision highp float; precision highp int;\n" : "";
        decoder.addSource(Shader.ShaderType.Vertex, "terrain-depth-read.vert", precision
                + "in vec3 inPosition; void main(){gl_Position=vec4(inPosition.xy-vec2(1),0,1);}", "", language);
        decoder.addSource(Shader.ShaderType.Fragment, "terrain-depth-read.frag", precision
                + "uniform sampler2D m_Depth; out vec4 fragColor;"
                + "void main(){fragColor=vec4(texelFetch(m_Depth,ivec2(gl_FragCoord.xy),0).r);}", "", language);
        decoder.getUniform("m_Depth").setValue(VarType.Int, 0);
        FrameBuffer target = new FrameBuffer(WIDTH, HEIGHT, 1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        RenderState state = new RenderState();
        state.setDepthTest(false); state.setDepthWrite(false); state.setFaceCullMode(RenderState.FaceCullMode.Off);
        renderer.setFrameBuffer(target); renderer.setViewPort(0, 0, WIDTH, HEIGHT);
        renderer.applyRenderState(state);
        renderer.setTexture(0, source.getDepthTarget().getTexture());
        renderer.setShader(decoder);
        renderer.renderMesh(new com.jme3.scene.shape.Quad(2, 2), 0, 1, null);
        return read(renderer, target);
    }

    private void waitForFrames() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (residency.stats().getFrames() != 0 && System.nanoTime() < deadline) {
            residency.poll();
            if (residency.stats().getFrames() != 0) Thread.sleep(1);
        }
        require(residency.stats().getFrames() == 0, "renderer fence completed and released the page lease");
    }

    private Shader landscapeShader(boolean es, BufferObject result) throws Exception {
        Shader shader = new Shader();
        String language = es ? "GLSL300" : "GLSL150";
        String vertex = assetManager.loadAsset(new AssetKey<String>("jme3test/terrain/HeightfieldGpuLandscape.vert"));
        shader.addSource(Shader.ShaderType.Vertex, "HeightfieldGpuLandscape.vert",
                vertex, "#define HEIGHTFIELD_LOD\n", language);
        shader.addSource(Shader.ShaderType.Fragment, "HeightfieldGpuLandscape.frag",
                readAsset("jme3test/terrain/HeightfieldGpuLandscape.frag"), "", language);
        shader.getBufferBlock("m_Selection").setBufferObject(BufferType.UniformBufferObject, result);
        return shader;
    }

    private void packCpuRecords(Scenario scenario, List<HeightfieldHierarchy.Node> cover,
            BufferObject outputBuffer) {
        ByteBuffer bytes = outputBuffer.getData().order(ByteOrder.nativeOrder());
        bytes.clear(); FloatBuffer f = bytes.asFloatBuffer();
        List<HeightfieldHierarchy.Node> visible = new ArrayList<>();
        for (HeightfieldHierarchy.Node node : cover) if (visible(scenario.camera, node)) visible.add(node);
        f.put(0, visible.size()); f.put(1, 0); f.put(2, cover.size()); f.put(3, scenario.revision);
        // Match the selector's std430 ABI: a vec4 header, then 85 two-vec4
        // cover records, then 85 two-vec4 compact visible records.
        for (int i = 0; i < cover.size(); i++) writeRecord(f, 4 + i * 8, cover.get(i), scenario, cover);
        for (int i = 0; i < visible.size(); i++) writeRecord(f, 4 + (85 + i) * 8, visible.get(i), scenario, cover);
        outputBuffer.setUpdateNeeded();
    }

    private int countVisible(Scenario scenario, List<HeightfieldHierarchy.Node> cover) {
        int visible = 0;
        for (HeightfieldHierarchy.Node node : cover) if (visible(scenario.camera, node)) visible++;
        return visible;
    }

    private void writeRecord(FloatBuffer f, int base, HeightfieldHierarchy.Node node,
            Scenario scenario, List<HeightfieldHierarchy.Node> cover) {
        int nodeIndex = indices.get(node);
        int edgeMask = 0;
        for (HeightfieldHierarchy.Node other : cover) {
            if (other == node || other.getLevel() >= node.getLevel() || !touches(node, other)) continue;
            int side = touchingSide(node, other);
            if (side >= 0) edgeMask |= 1 << side;
        }
        double stable = HeightfieldProjection.pixels(scenario.camera, node.getBound(), node.getStableError());
        double morph = HeightfieldProjection.pixels(scenario.camera, node.getBound(), node.getMorphError());
        boolean hasCoarse = hasCoarser(node, cover);
        float error = ceilFloat(hasCoarse ? morph : stable);
        f.put(base, nodeIndex); f.put(base + 1, node.getOriginX()); f.put(base + 2, node.getOriginZ());
        f.put(base + 3, node.getStride()); f.put(base + 4, node.getSpan());
        f.put(base + 5, edgeMask | (hasCoarse ? 16 : 0)); f.put(base + 6, error); f.put(base + 7, scenario.revision);
    }

    private static boolean hasCoarser(HeightfieldHierarchy.Node node, List<HeightfieldHierarchy.Node> cover) {
        for (HeightfieldHierarchy.Node other : cover) {
            if (other != node && other.getLevel() < node.getLevel() && touches(node, other)) return true;
        }
        return false;
    }

    private boolean visible(Camera camera, HeightfieldHierarchy.Node node) {
        int state = camera.getPlaneState(); camera.setPlaneState(0);
        try { return camera.contains(node.getBound()) != Camera.FrustumIntersect.Outside; }
        finally { camera.setPlaneState(state); }
    }

    private static int touchingSide(HeightfieldHierarchy.Node a, HeightfieldHierarchy.Node b) {
        boolean zOverlap = Math.max(a.getOriginZ(), b.getOriginZ()) < Math.min(a.getOriginZ()+a.getSpan(), b.getOriginZ()+b.getSpan());
        boolean xOverlap = Math.max(a.getOriginX(), b.getOriginX()) < Math.min(a.getOriginX()+a.getSpan(), b.getOriginX()+b.getSpan());
        if (zOverlap && a.getOriginX() == b.getOriginX()+b.getSpan()) return 0;
        if (zOverlap && a.getOriginX()+a.getSpan() == b.getOriginX()) return 1;
        if (xOverlap && a.getOriginZ() == b.getOriginZ()+b.getSpan()) return 2;
        if (xOverlap && a.getOriginZ()+a.getSpan() == b.getOriginZ()) return 3;
        return -1;
    }

    private static boolean touches(HeightfieldHierarchy.Node a, HeightfieldHierarchy.Node b) {
        return a != b && a.getOriginX() <= b.getOriginX()+b.getSpan()
                && b.getOriginX() <= a.getOriginX()+a.getSpan()
                && a.getOriginZ() <= b.getOriginZ()+b.getSpan()
                && b.getOriginZ() <= a.getOriginZ()+a.getSpan();
    }

    private Comparison compare(Scenario scenario, FloatBuffer gpu, FloatBuffer cpu) {
        float max = 0; int different = 0, terrain = 0, compared = 0;
        for (int pixel = 0; pixel < WIDTH*HEIGHT; pixel++) {
            boolean differs = false;
            int base = pixel*4;
            for (int channel = 0; channel < 4; channel++) {
                float a = gpu.get(base+channel), b = cpu.get(base+channel);
                require(Float.isFinite(a) && Float.isFinite(b), scenario.name + " finite linear framebuffer");
                float error = Math.abs(a-b); max = Math.max(max,error); compared++;
                if (error > 2e-5f) differs = true;
            }
            if (differs) different++;
            if (Math.abs(gpu.get(base)-.025f) + Math.abs(gpu.get(base+1)-.04f)
                    + Math.abs(gpu.get(base+2)-.065f) > 1e-4f) terrain++;
        }
        return new Comparison(max,different,terrain,compared);
    }

    private int decodeCommand(Renderer renderer, BufferObject command, boolean es) {
        Shader decoder = new Shader();
        String language = es ? "GLSL300" : "GLSL150";
        String prefix = es ? "precision highp float; precision highp int;\n" : "";
        decoder.addSource(Shader.ShaderType.Vertex, "landscape-command-decode.vert", prefix
                + "in vec3 inPosition; void main(){gl_Position=vec4(inPosition.xy-vec2(1.0),0,1);}", "", language);
        decoder.addSource(Shader.ShaderType.Fragment, "landscape-command-decode.frag", prefix
                + "layout(std140) uniform m_Command { uvec4 words[2]; }; out vec4 outColor;"
                + "void main(){int x=int(gl_FragCoord.x);outColor=vec4(float(words[x/4][x%4]),0,0,1);}", "", language);
        decoder.getBufferBlock("m_Command").setBufferObject(BufferType.UniformBufferObject, command);
        FrameBuffer target = new FrameBuffer(5, 1, 1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        Mesh quad = new com.jme3.scene.shape.Quad(2, 2);
        RenderState state = new RenderState(); state.setDepthTest(false); state.setDepthWrite(false);
        state.setFaceCullMode(RenderState.FaceCullMode.Off);
        renderer.setFrameBuffer(target); renderer.setViewPort(0, 0, 5, 1);
        renderer.setBackgroundColor(ColorRGBA.Black);
        renderer.clearBuffers(true, false, false); renderer.applyRenderState(state); renderer.setShader(decoder);
        renderer.renderMesh(quad, 0, 1, null);
        ByteBuffer bytes = BufferUtils.createByteBuffer(5 * 16).order(ByteOrder.nativeOrder());
        renderer.readFrameBufferWithFormat(target, bytes, Image.Format.RGBA32F);
        FloatBuffer values = bytes.asFloatBuffer();
        int[] words = new int[5];
        for (int i = 0; i < words.length; i++) {
            float value = values.get(i * 4);
            require(Float.isFinite(value) && value == Math.rint(value), "GPU command word decodes exactly");
            words[i] = (int) value;
        }
        require(words[0] == PATCH_CELLS * PATCH_CELLS * 6, "indirect index count equals canonical grid index count");
        require(words[2] == 0 && words[3] == 0 && words[4] == 0, "indirect command first/base/reserved words are zero");
        require(words[1] <= LEAF_LIMIT, "GPU command instance count is capped to leaf capacity");
        return words[1];
    }

    private FloatBuffer read(Renderer renderer, FrameBuffer target) {
        ByteBuffer bytes = BufferUtils.createByteBuffer(target.getWidth()*target.getHeight()*16)
                .order(ByteOrder.nativeOrder());
        renderer.readFrameBufferWithFormat(target, bytes, Image.Format.RGBA32F);
        return bytes.asFloatBuffer();
    }

    private double verifyGpuCover(Renderer renderer, Scenario scenario, BufferObject records,
            List<HeightfieldHierarchy.Node> cpuCover, boolean es) throws Exception {
        Shader decoder = new Shader();
        String language = es ? "GLSL300" : "GLSL150";
        for (Shader.ShaderType stage : new Shader.ShaderType[]{Shader.ShaderType.Vertex, Shader.ShaderType.Fragment}) {
            String path = "jme3test/terrain/HeightfieldGpuSelection."
                    + (stage == Shader.ShaderType.Vertex ? "vert" : "frag");
            decoder.addSource(stage, path, readAsset(path), "", language);
        }
        decoder.getBufferBlock("m_Result").setBufferObject(BufferType.UniformBufferObject, records);
        FrameBuffer target = new FrameBuffer(NODES, 5, 1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        RenderState state = new RenderState();
        state.setDepthTest(false); state.setDepthWrite(false); state.setFaceCullMode(RenderState.FaceCullMode.Off);
        renderer.setFrameBuffer(target); renderer.setViewPort(0, 0, NODES, 5);
        renderer.applyRenderState(state); renderer.setShader(decoder);
        renderer.renderMesh(new com.jme3.scene.shape.Quad(2, 2), 0, 1, null);
        FloatBuffer values = read(renderer, target);
        int visibleCount = countVisible(scenario, cpuCover);
        require(values.get(0) == visibleCount && values.get(1) == 0
                && values.get(2) == cpuCover.size() && values.get(3) == scenario.revision,
                scenario.name + " decoded GPU header matches the complete CPU cover");
        boolean[] expected = new boolean[NODES];
        for (HeightfieldHierarchy.Node node : cpuCover) expected[indices.get(node)] = true;
        FloatBuffer reference = FloatBuffer.allocate(8);
        double maximum = 0;
        for (int section = 0; section < 2; section++) {
            boolean[] seen = new boolean[NODES];
            int count = section == 0 ? cpuCover.size() : visibleCount;
            int row = section == 0 ? 1 : 3;
            for (int i = 0; i < count; i++) {
                float id = values.get((row * NODES + i) * 4);
                require(Float.isFinite(id) && id == Math.rint(id) && id >= 0 && id < NODES,
                        scenario.name + " decoded GPU node index is integral and bounded");
                int index = (int) id;
                require(expected[index] && !seen[index], scenario.name + " GPU cover has unique expected nodes");
                seen[index] = true;
                HeightfieldHierarchy.Node node = nodes.get(index);
                require(section == 0 || visible(scenario.camera, node), scenario.name + " compact node is visible");
                writeRecord(reference, 0, node, scenario, cpuCover);
                for (int component = 0; component < 8; component++) {
                    float value = values.get(((row + component / 4) * NODES + i) * 4 + component % 4);
                    require(value == reference.get(component), scenario.name + " GPU patch metadata matches CPU reconstruction");
                    if (section == 0 && component == 6) maximum = Math.max(maximum, value);
                }
            }
        }
        return maximum;
    }

    private void writePng(Path path, FloatBuffer pixels) throws Exception {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int index = (y*WIDTH+x)*4;
            int r = displayByte(pixels.get(index)); int g = displayByte(pixels.get(index+1));
            int b = displayByte(pixels.get(index+2)); int a = displayByte(pixels.get(index+3));
            image.setRGB(x, HEIGHT-1-y, (a<<24)|(r<<16)|(g<<8)|b);
        }
        ImageIO.write(image,"png",path.toFile());
    }

    private static int displayByte(float linear) {
        linear = Math.max(0, Math.min(1, linear));
        float encoded = linear <= .0031308f ? linear*12.92f : 1.055f*(float)Math.pow(linear,1f/2.4f)-.055f;
        return Math.max(0,Math.min(255,Math.round(encoded*255f)));
    }

    private FrameBuffer target(int width, int height) {
        FrameBuffer target = new FrameBuffer(width,height,1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        if (materialMode) {
            target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(new Texture2D(width, height, Image.Format.Depth24)));
        } else {
            target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
        }
        return target;
    }

    private BufferObject buffer(int bytes, int binding) {
        BufferObject object = new BufferObject();
        object.setData(BufferUtils.createByteBuffer(bytes).order(ByteOrder.nativeOrder()));
        object.setBinding(binding); object.setUpdateNeeded();
        return object;
    }

    private static HeightfieldPage makePage() {
        int side = CELLS+3; float[] samples = new float[side*side];
        for(int z=-1;z<=CELLS+1;z++) for(int x=-1;x<=CELLS+1;x++) {
            double wx=x*(double)SPACING, wz=z*(double)SPACING;
            double rolling=1.1*Math.sin(.19*wx)*Math.cos(.16*wz)+.42*Math.sin(.31*(wx+.7*wz));
            double ridge=5.2*Math.exp(-((wx-17.5)*(wx-17.5))/30.0)*Math.exp(-((wz-31)*(wz-31))/520.0);
            double spike=7.0*Math.exp(-((wx-43)*(wx-43)+(wz-22)*(wz-22))/7.0);
            samples[(z+1)*side+x+1]=(float)(rolling+ridge+spike);
        }
        return new HeightfieldPage(CELLS,SPACING,samples);
    }

    private static Camera camera(Vector3f location, Vector3f target) {
        Camera camera=new Camera(WIDTH,HEIGHT); camera.setFrustumPerspective(45,WIDTH/(float)HEIGHT,.1f,1000);
        camera.setLocation(location); camera.lookAt(target,Vector3f.UNIT_Y); camera.update(); return camera;
    }

    private static float ceilFloat(double value) {
        if(!Double.isFinite(value)||value>Float.MAX_VALUE)return Float.POSITIVE_INFINITY;
        if(value<=0)return 0;float rounded=(float)value;return (double)rounded<value?Math.nextUp(rounded):rounded;
    }

    private String readAsset(String path) throws Exception {
        AssetInfo asset=assetManager.locateAsset(new AssetKey<>(path));
        if(asset==null)throw new IllegalStateException("Missing shader asset "+path);
        try(InputStream in=asset.openStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] block=new byte[4096];int n;while((n=in.read(block))>=0)out.write(block,0,n);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);checks++;}

    private static final class Scenario {
        final String name;final Camera camera;final double targetPixels;final int revision;
        Scenario(String name,Camera camera,double targetPixels,int revision){this.name=name;this.camera=camera;this.targetPixels=targetPixels;this.revision=revision;}
    }
    private static final class Comparison {
        final float maxError;final int differingPixels,terrainPixels,compared;
        Comparison(float error,int different,int terrain,int compared){maxError=error;differingPixels=different;terrainPixels=terrain;this.compared=compared;}
    }
    private static final class Capture {
        final int leaves,gpuVisible,cpuVisible,terrainPixels,differingPixels,slot;
        final long generation;
        final boolean leaseCompleted;
        final double gpuError,cpuError;final float imageError;
        Capture(int leaves,int gpuVisible,int cpuVisible,double gpuError,double cpuError,
                float imageError,int differingPixels,int terrainPixels,int slot,long generation,boolean leaseCompleted){this.leaves=leaves;this.gpuVisible=gpuVisible;
            this.cpuVisible=cpuVisible;this.gpuError=gpuError;this.cpuError=cpuError;this.imageError=imageError;
            this.differingPixels=differingPixels;this.terrainPixels=terrainPixels;this.slot=slot;
            this.generation=generation;this.leaseCompleted=leaseCompleted;}
    }
}
