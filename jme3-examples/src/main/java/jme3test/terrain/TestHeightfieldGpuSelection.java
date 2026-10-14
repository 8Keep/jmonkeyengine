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

import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetKey;
import com.jme3.bounding.BoundingBox;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.MemoryBarrier;
import com.jme3.renderer.Renderer;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.system.AppSettings;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldLod;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldProjection;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.util.BufferUtils;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Finite GPU heightfield cover, 2:1 balance, cull, and compact proof. */
public final class TestHeightfieldGpuSelection extends SimpleApplication {
    private static final int CELLS = 128, PATCH_CELLS = 16;
    private static final int NODE_COUNT = 85, MAX_LEAVES = 64;
    private static final int NODE_VECS = 6, PLANE_COUNT = 6;
    private static final int OUTPUT_VECS = 1 + NODE_COUNT * 4;
    private static final float SPACING = .5f;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Path output;
    private volatile Throwable failure;
    private HeightfieldPage page;
    private HeightfieldHierarchy hierarchy;
    private HeightfieldLod oracle;
    private List<HeightfieldHierarchy.Node> nodes;
    private Map<HeightfieldHierarchy.Node, Integer> indices;
    private int checks;

    private TestHeightfieldGpuSelection(Path output) { this.output = output; }

    public static void main(String[] args) throws Exception {
        TestHeightfieldGpuSelection app = new TestHeightfieldGpuSelection(Paths.get(
                args.length > 1 ? args[1] : "build/heightfield-gpu-selection"));
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length > 0 ? args[0] : AppSettings.LWJGL_OPENGL45);
        settings.setResolution(320, 240);
        settings.setAudioRenderer(null);
        settings.setVSync(false);
        settings.setGraphicsDebug(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (!app.finished.await(60, TimeUnit.SECONDS)) {
            app.stop(false);
            throw new IllegalStateException("GPU heightfield selection timed out", app.failure);
        }
        app.stop(true);
        if (app.failure != null) throw new IllegalStateException("GPU heightfield selection failed", app.failure);
        System.out.println("HEIGHTFIELD_GPU_SELECTION_PASS checks=" + app.checks
                + " output=" + app.output.toAbsolutePath());
    }

    @Override public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            runProbe(renderManager.getRenderer());
        } catch (Throwable error) {
            fail(error);
        } finally {
            finished.countDown();
        }
    }

    @Override public void handleError(String message, Throwable error) {
        fail(error == null ? new IllegalStateException(message) : error);
    }

    private void fail(Throwable error) {
        failure = error;
        error.printStackTrace();
        finished.countDown();
    }

    private void runProbe(Renderer renderer) throws Exception {
        if (!renderer.getCaps().contains(Caps.ComputeShader)
                || !renderer.getCaps().contains(Caps.UniformBufferObject)) {
            throw new UnsupportedOperationException("Managed compute and UBO support required");
        }
        boolean gles = renderer.getCaps().contains(Caps.OpenGLES30);
        int inputBytes = (NODE_COUNT * NODE_VECS + PLANE_COUNT + 1) * 16;
        int outputBytes = OUTPUT_VECS * 16;
        require(renderer.getLimits().get(Limits.ComputeStorageBufferBindings) >= 2, "two storage bindings");
        require(renderer.getLimits().get(Limits.ComputeStorageBufferBlockSize) >= outputBytes, "output SSBO size");
        require(renderer.getLimits().get(Limits.UniformBufferObjectMaxBlockSize) >= outputBytes, "output UBO size");
        require(renderer.getLimits().get(Limits.ComputeWorkGroupSizeX) >= 1
                && renderer.getLimits().get(Limits.ComputeWorkGroupInvocations) >= 1, "single invocation workgroup");

        page = makePage();
        hierarchy = new HeightfieldHierarchy(page, PATCH_CELLS, 0);
        oracle = new HeightfieldLod(hierarchy);
        nodes = hierarchy.getNodes();
        require(nodes.size() == NODE_COUNT, "fixed 128/16 hierarchy node count");
        indices = new IdentityHashMap<>();
        for (int i = 0; i < nodes.size(); i++) indices.put(nodes.get(i), i);

        BufferObject input = new BufferObject();
        input.setData(BufferUtils.createByteBuffer(inputBytes).order(ByteOrder.nativeOrder()));
        input.setBinding(2);
        BufferObject result = new BufferObject();
        result.setData(BufferUtils.createByteBuffer(outputBytes).order(ByteOrder.nativeOrder()));
        result.setUpdateNeeded();
        result.setBinding(3); // Material assigns its ordinary UBO binding independently.

        Shader compute = new Shader();
        compute.addSource(Shader.ShaderType.Compute, "HeightfieldGpuSelection.comp",
                readAsset("jme3test/terrain/HeightfieldGpuSelection.comp"), "", gles ? "GLSL310" : "GLSL430");
        Material readback = new Material(assetManager, "jme3test/terrain/HeightfieldGpuSelection.j3md");
        readback.setUniformBufferObject("Result", result);
        Geometry quad = new Geometry("gpu-selection-result", new Quad(2, 2));
        quad.setLocalTranslation(-1, -1, 0);
        quad.setMaterial(readback);
        quad.updateGeometricState();
        FrameBuffer target = new FrameBuffer(NODE_COUNT, 5, 1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        Camera probeCamera = new Camera(NODE_COUNT, 5);
        probeCamera.setParallelProjection(true);
        probeCamera.setFrustum(.1f, 10f, -1f, 1f, 1f, -1f);
        probeCamera.setLocation(new Vector3f(0, 0, 1));
        probeCamera.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        probeCamera.update();
        renderManager.setCamera(probeCamera, false);

        List<Scenario> scenarios = new ArrayList<>();
        scenarios.add(new Scenario("normal", camera(new Vector3f(32, 30, 72), new Vector3f(32, 0, 32)), 1.25, MAX_LEAVES, 1, Readiness.ALL));
        scenarios.add(new Scenario("camera-cut", camera(new Vector3f(-42, 25, 9), new Vector3f(10, 0, 55)), .8, MAX_LEAVES, 1, Readiness.ALL));
        scenarios.add(new Scenario("cold-root", camera(new Vector3f(32, 30, 72), new Vector3f(32, 0, 32)), .5, MAX_LEAVES, 2, Readiness.ROOT));
        scenarios.add(new Scenario("missing-children", camera(new Vector3f(32, 30, 72), new Vector3f(32, 0, 32)), .65, MAX_LEAVES, 2, Readiness.NO_LEAVES));
        scenarios.add(new Scenario("budget-pressure", camera(new Vector3f(32, 30, 72), new Vector3f(32, 0, 32)), .25, 7, 3, Readiness.ALL));
        scenarios.add(new Scenario("source-revision", camera(new Vector3f(64, 58, -14), new Vector3f(32, 0, 32)), 1.0, MAX_LEAVES, 9, Readiness.ALL));

        Files.createDirectories(output);
        List<String> summary = new ArrayList<>();
        summary.add("GPU hierarchy selection; projected errors prepared by HeightfieldProjection and rounded upward to float.");
        summary.add("Readiness is synthetic metadata in this proof; no HeightfieldResidency acceptance is claimed.");
        for (Scenario scenario : scenarios) {
            runScenario(renderer, input, result, compute, quad, target, scenario, gles);
            summary.add(scenario.name + " checks=" + checks);
        }
        summary.add("renderer=" + renderer.getCaps());
        summary.add("limits=" + renderer.getLimits());
        Files.write(output.resolve("summary.txt"), summary);
        renderer.setFrameBuffer(null);
    }

    private void runScenario(Renderer renderer, BufferObject input, BufferObject result,
            Shader compute, Geometry quad, FrameBuffer target, Scenario scenario, boolean gles) {
        boolean[] ready = readiness(scenario.readiness);
        packInput(input, scenario, ready);
        compute.getUniform("m_NodeCount").setValue(VarType.Int, NODE_COUNT);
        compute.getUniform("m_MaxLeaves").setValue(VarType.Int, scenario.maxLeaves);
        compute.getUniform("m_TargetPixels").setValue(VarType.Float, (float) scenario.targetPixels);
        renderer.setFixedShaderStorageBufferObject(0, input);
        renderer.setFixedShaderStorageBufferObject(1, result);
        renderer.dispatchCompute(compute, 1, 1, 1);
        renderer.memoryBarrier(EnumSet.of(MemoryBarrier.UniformBuffer, MemoryBarrier.ShaderStorage));

        renderer.setFrameBuffer(target);
        renderer.setBackgroundColor(ColorRGBA.Black);
        renderer.clearBuffers(true, false, false);
        renderManager.renderGeometry(quad);
        ByteBuffer bytes = BufferUtils.createByteBuffer(NODE_COUNT * 5 * 16).order(ByteOrder.nativeOrder());
        renderer.readFrameBufferWithFormat(target, bytes, Image.Format.RGBA32F);
        FloatBuffer pixels = bytes.asFloatBuffer();
        verify(scenario, ready, pixels);
        System.out.println("HEIGHTFIELD_GPU_COVER scenario=" + scenario.name + " checks=" + checks
                + " gles=" + gles + " leaves=" + lastLeafCount + " visible=" + lastVisibleCount
                + " saturation=" + lastSaturation);
    }

    private int lastLeafCount, lastVisibleCount, lastSaturation;

    private void verify(Scenario scenario, boolean[] ready, FloatBuffer pixels) {
        float visibleCountF = pixel(pixels, 0, 0, 0);
        float saturationF = pixel(pixels, 0, 0, 1);
        float leafCountF = pixel(pixels, 0, 0, 2);
        float revisionF = pixel(pixels, 0, 0, 3);
        int visibleCount = exactInt(visibleCountF, "visible count");
        int saturation = exactInt(saturationF, "saturation flags");
        int leafCount = exactInt(leafCountF, "leaf count");
        require(visibleCount >= 0 && visibleCount <= leafCount && leafCount <= scenario.maxLeaves,
                scenario.name + " bounded counts");
        require(leafCount > 0, scenario.name + " compute wrote a nonempty cover");
        require((int) revisionF == scenario.revision, scenario.name + " source revision echoed");
        require((saturation & ~31) == 0, scenario.name + " known saturation flags");
        require((saturation & 8) == 0, scenario.name + " pinned root readiness is guaranteed");
        require((saturation & 16) == 0, scenario.name + " bounded selector iteration completed");
        lastLeafCount = leafCount;
        lastVisibleCount = visibleCount;
        lastSaturation = saturation;

        int[] cover = new int[leafCount];
        int[] edges = new int[leafCount];
        float[] reportedErrors = new float[leafCount];
        boolean[] morphed = new boolean[leafCount];
        boolean[] seen = new boolean[NODE_COUNT];
        for (int i = 0; i < leafCount; i++) {
            float nodeF = pixel(pixels, i, 1, 0);
            int node = exactInt(nodeF, scenario.name + " cover node");
            int edgeData = exactInt(pixel(pixels, i, 2, 1), scenario.name + " edge data");
            require(node >= 0 && node < NODE_COUNT && !seen[node], scenario.name + " unique cover node");
            seen[node] = true;
            cover[i] = node;
            edges[i] = edgeData & 15;
            morphed[i] = (edgeData & 16) != 0;
            reportedErrors[i] = pixel(pixels, i, 2, 2);
            require((edgeData & ~31) == 0, scenario.name + " edge and morph flag range");
            require((int) pixel(pixels, i, 2, 3) == scenario.revision, scenario.name + " cover revision echoed");
            HeightfieldHierarchy.Node selected = nodes.get(node);
            float projected = ceilFloat(HeightfieldProjection.pixels(scenario.camera,
                    selected.getBound(), morphed[i] ? selected.getMorphError() : selected.getStableError()));
            require(Float.floatToIntBits(reportedErrors[i]) == Float.floatToIntBits(projected),
                    scenario.name + " decoded selected error matches upward-rounded CPU projection input");
        }
        verifyCover(scenario, ready, cover, edges, morphed, saturation);

        boolean[] visible = new boolean[NODE_COUNT];
        for (int i = 0; i < visibleCount; i++) {
            int node = exactInt(pixel(pixels, i, 3, 0), scenario.name + " compact node");
            int edgeData = exactInt(pixel(pixels, i, 4, 1), scenario.name + " compact edge data");
            require(node >= 0 && node < NODE_COUNT && seen[node] && !visible[node], scenario.name + " compact record references a unique cover leaf");
            int coverIndex = indexOf(cover, node);
            require(edgeData == (edges[coverIndex] | (morphed[coverIndex] ? 16 : 0)), scenario.name + " compact edge data preserved");
            require((int) pixel(pixels, i, 4, 3) == scenario.revision, scenario.name + " compact revision echoed");
            for (int channel = 0; channel < 4; channel++) {
                require(pixel(pixels, i, 3, channel) == pixel(pixels, coverIndex, 1, channel)
                        && pixel(pixels, i, 4, channel) == pixel(pixels, coverIndex, 2, channel),
                        scenario.name + " compact record copies complete cover record");
            }
            visible[node] = true;
        }
        for (int node : cover) {
            if (cameraContains(scenario.camera, nodes.get(node))) {
                require(visible[node], scenario.name + " culling preserves CPU-visible cover leaf " + node
                        + " bounds=" + nodes.get(node).getBound());
            }
        }
        HeightfieldLod.Selection cpu = oracle.select(scenario.camera, scenario.targetPixels,
                scenario.maxLeaves, node -> ready[indices.get(node)]);
        require(cpu.getLeaves().size() <= scenario.maxLeaves, scenario.name + " CPU oracle budget");
        HeightfieldLod.Transition cpuTransition = new HeightfieldLod.Transition(hierarchy);
        for (int i = 0; i < NODE_COUNT * 2; i++) cpuTransition.advance(cpu, 1f);
        boolean[] cpuCover = new boolean[NODE_COUNT];
        int[] cpuEdges = new int[NODE_COUNT];
        for (HeightfieldLod.Patch patch : cpuTransition.getPatches()) {
            int node = indices.get(patch.getNode());
            cpuCover[node] = true;
            for (int side = 0; side < 4; side++) if (patch.getEdgeMorph(side) > 0) cpuEdges[node] |= 1 << side;
        }
        int coverDeviation = 0, edgeDeviation = 0;
        for (int i = 0; i < cover.length; i++) {
            if (!cpuCover[cover[i]]) coverDeviation++;
            if (cpuEdges[cover[i]] != (edges[i] & 15)) edgeDeviation++;
        }
        for (int i = 0; i < NODE_COUNT; i++) if (cpuCover[i] && !seen[i]) coverDeviation++;
        double gpuMaximum = 0;
        for (float reportedError : reportedErrors) gpuMaximum = Math.max(gpuMaximum, reportedError);
        double tolerance = 4.0 * Math.ulp((float) scenario.targetPixels);
        if (saturation == 0) require(gpuMaximum <= scenario.targetPixels + tolerance,
                scenario.name + " unsaturated GPU cover meets requested screen error within float rounding");
        else require(gpuMaximum > scenario.targetPixels || Double.isInfinite(gpuMaximum),
                scenario.name + " saturation is reported only while quality remains over target");
        require(cpu.getMaximumPixels() >= 0, scenario.name + " CPU oracle reports finite or explicit infinite quality");
        if ((scenario.name.equals("camera-cut") || scenario.name.equals("source-revision"))
                && cpu.getMaximumPixels() <= scenario.targetPixels + tolerance) {
            require(gpuMaximum <= scenario.targetPixels + tolerance,
                    scenario.name + " GPU policy reaches CPU oracle quality when CPU is not limited");
        }
        if (scenario.readiness == Readiness.ROOT) {
            require(leafCount == 1 && (saturation & 2) != 0 && gpuMaximum > scenario.targetPixels,
                    "cold root reports unresolved error while child pages are unavailable");
        }
        if (scenario.maxLeaves == 7) {
            require(leafCount == 7 && (saturation & 1) != 0 && gpuMaximum > scenario.targetPixels,
                    "budget pressure reports unresolved error at the actual GPU leaf limit");
        }
        if (scenario.name.equals("normal")) {
            require(leafCount > 1 && visibleCount > 0 && gpuMaximum > 0,
                    "normal camera exercises nonempty GPU hierarchy selection and culling");
        }
        System.out.println("HEIGHTFIELD_GPU_ORACLE scenario=" + scenario.name + " gpuLeaves=" + cover.length
                + " cpuLeaves=" + cpu.getLeaves().size() + " gpuPixels=" + gpuMaximum
                + " cpuPixels=" + cpu.getMaximumPixels() + " coverDeviation=" + coverDeviation
                + " commonEdgeDeviation=" + edgeDeviation + " saturation=" + saturation);
    }

    private void verifyCover(Scenario scenario, boolean[] ready, int[] cover, int[] edgeMasks,
            boolean[] morphed, int saturation) {
        int[] cellCoverage = new int[CELLS * CELLS];
        for (int i = 0; i < cover.length; i++) {
            HeightfieldHierarchy.Node a = nodes.get(cover[i]);
            for (int z = a.getOriginZ(); z < a.getOriginZ() + a.getSpan(); z++) {
                for (int x = a.getOriginX(); x < a.getOriginX() + a.getSpan(); x++) cellCoverage[z * CELLS + x]++;
            }
            int expected = 0;
            boolean expectedMorph = false;
            for (int j = 0; j < cover.length; j++) {
                if (i == j) continue;
                HeightfieldHierarchy.Node b = nodes.get(cover[j]);
                int side = touchingSide(a, b);
                if (side >= 0 && b.getLevel() < a.getLevel()) expected |= 1 << side;
                if (touches(a, b) && b.getLevel() < a.getLevel()) expectedMorph = true;
                if (touches(a, b)) require(Math.abs(a.getLevel() - b.getLevel()) <= 1,
                        scenario.name + " edge/corner 2:1 balance");
            }
            require(edgeMasks[i] == expected, scenario.name + " exact finer-edge collapse flags");
            require(morphed[i] == expectedMorph, scenario.name + " stable/morph error flag includes corner neighbors");
            require(a == hierarchy.getRoot() || ready[cover[i]], scenario.name + " output leaves ready");
        }
        for (int cell = 0; cell < cellCoverage.length; cell++) {
            if (cellCoverage[cell] != 1) throw new IllegalStateException("GPU cover has coverage="
                    + cellCoverage[cell] + " at cell=" + (cell % CELLS) + "," + (cell / CELLS)
                    + " leaves=" + java.util.Arrays.toString(cover));
            checks++;
        }
        require(cover.length > 0 && ready[0], scenario.name + " pinned root remains ready as fallback");
        if (scenario.readiness == Readiness.ROOT) require(cover.length == 1 && cover[0] == 0,
                "cold root scenario remains on pinned root");
    }

    private void packInput(BufferObject input, Scenario scenario, boolean[] ready) {
        ByteBuffer data = input.getData().order(ByteOrder.nativeOrder());
        data.clear();
        FloatBuffer f = data.asFloatBuffer();
        IntBuffer ints = data.asIntBuffer();
        for (int i = 0; i < NODE_COUNT; i++) {
            HeightfieldHierarchy.Node node = nodes.get(i);
            int base = i * NODE_VECS * 4;
            List<HeightfieldHierarchy.Node> children = node.getChildren();
            for (int q = 0; q < 4; q++) ints.put(base + q, q < children.size() ? indices.get(children.get(q)) : -1);
            ints.put(base + 4, node.getLevel());
            ints.put(base + 5, ready[i] ? 1 : 0);
            ints.put(base + 6, 0);
            ints.put(base + 7, 0);
            f.put(base + 8, node.getOriginX());
            f.put(base + 9, node.getOriginZ());
            f.put(base + 10, node.getSpan());
            f.put(base + 11, node.getStride());
            double stable = HeightfieldProjection.pixels(scenario.camera, node.getBound(), node.getStableError());
            double morph = HeightfieldProjection.pixels(scenario.camera, node.getBound(), node.getMorphError());
            f.put(base + 12, ceilFloat(stable));
            f.put(base + 13, ceilFloat(morph));
            f.put(base + 14, 0);
            f.put(base + 15, 0);
            BoundingBox b = node.getBound();
            Vector3f c = b.getCenter();
            f.put(base + 16, c.x - b.getXExtent());
            f.put(base + 17, c.y - b.getYExtent());
            f.put(base + 18, c.z - b.getZExtent());
            f.put(base + 19, 0);
            f.put(base + 20, c.x + b.getXExtent());
            f.put(base + 21, c.y + b.getYExtent());
            f.put(base + 22, c.z + b.getZExtent());
            f.put(base + 23, 0);
        }
        int planeBase = NODE_COUNT * NODE_VECS * 4;
        float[][] planes = planes(scenario.camera.getViewProjectionMatrix());
        for (int p = 0; p < PLANE_COUNT; p++) {
            int base = planeBase + p * 4;
            for (int j = 0; j < 4; j++) f.put(base + j, planes[p][j]);
        }
        int info = planeBase + PLANE_COUNT * 4;
        f.put(info, scenario.revision);
        f.put(info + 1, (float) scenario.targetPixels);
        f.put(info + 2, scenario.maxLeaves);
        f.put(info + 3, NODE_COUNT);
        input.setUpdateNeeded();
        input.getData().position(0).limit(input.getData().capacity());
    }

    private boolean[] readiness(Readiness mode) {
        boolean[] ready = new boolean[NODE_COUNT];
        ready[0] = true; // pinned root is always the fallback
        for (int i = 1; i < NODE_COUNT; i++) {
            int level = nodes.get(i).getLevel();
            ready[i] = mode == Readiness.ALL || (mode == Readiness.NO_LEAVES && level < 3);
        }
        return ready;
    }

    private boolean cameraContains(Camera camera, HeightfieldHierarchy.Node node) {
        int savedPlaneState = camera.getPlaneState();
        camera.setPlaneState(0);
        try {
            return camera.contains(node.getBound()) != Camera.FrustumIntersect.Outside;
        } finally {
            camera.setPlaneState(savedPlaneState);
        }
    }

    private static float[][] planes(Matrix4f m) {
        float[][] rows = {{m.m00,m.m01,m.m02,m.m03}, {m.m10,m.m11,m.m12,m.m13},
            {m.m20,m.m21,m.m22,m.m23}, {m.m30,m.m31,m.m32,m.m33}};
        float[][] p = new float[6][4];
        for (int i = 0; i < 4; i++) {
            p[0][i] = rows[3][i] + rows[0][i];
            p[1][i] = rows[3][i] - rows[0][i];
            p[2][i] = rows[3][i] + rows[1][i];
            p[3][i] = rows[3][i] - rows[1][i];
            p[4][i] = rows[3][i] + rows[2][i];
            p[5][i] = rows[3][i] - rows[2][i];
        }
        return p;
    }

    private static float ceilFloat(double value) {
        if (!Double.isFinite(value) || value > Float.MAX_VALUE) return Float.POSITIVE_INFINITY;
        if (value <= 0) return 0;
        float rounded = (float) value;
        return (double) rounded < value ? Math.nextUp(rounded) : rounded;
    }

    private static boolean touches(HeightfieldHierarchy.Node a, HeightfieldHierarchy.Node b) {
        return a != b && a.getOriginX() <= b.getOriginX() + b.getSpan()
                && b.getOriginX() <= a.getOriginX() + a.getSpan()
                && a.getOriginZ() <= b.getOriginZ() + b.getSpan()
                && b.getOriginZ() <= a.getOriginZ() + a.getSpan();
    }

    private static int touchingSide(HeightfieldHierarchy.Node a, HeightfieldHierarchy.Node b) {
        boolean zOverlap = Math.max(a.getOriginZ(), b.getOriginZ()) < Math.min(a.getOriginZ() + a.getSpan(), b.getOriginZ() + b.getSpan());
        boolean xOverlap = Math.max(a.getOriginX(), b.getOriginX()) < Math.min(a.getOriginX() + a.getSpan(), b.getOriginX() + b.getSpan());
        if (zOverlap && a.getOriginX() == b.getOriginX() + b.getSpan()) return 0;
        if (zOverlap && a.getOriginX() + a.getSpan() == b.getOriginX()) return 1;
        if (xOverlap && a.getOriginZ() == b.getOriginZ() + b.getSpan()) return 2;
        if (xOverlap && a.getOriginZ() + a.getSpan() == b.getOriginZ()) return 3;
        return -1;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return -1;
    }

    private static int exactInt(float value, String label) {
        if (!Float.isFinite(value) || value != Math.rint(value)) throw new IllegalStateException(label + " is not an exact integer: " + value);
        return (int) value;
    }

    private static float pixel(FloatBuffer pixels, int x, int y, int channel) {
        int i = (y * NODE_COUNT + x) * 4 + channel;
        return pixels.get(i);
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException("GPU heightfield selection check failed: " + message);
        checks++;
    }

    private String readAsset(String path) throws Exception {
        AssetInfo asset = assetManager.locateAsset(new AssetKey<>(path));
        if (asset == null) throw new IllegalStateException("Missing shader asset " + path);
        try (InputStream in = asset.openStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[4096]; int n;
            while ((n = in.read(block)) >= 0) bytes.write(block, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static HeightfieldPage makePage() {
        int side = CELLS + 3;
        float[] samples = new float[side * side];
        for (int z = -1; z <= CELLS + 1; z++) for (int x = -1; x <= CELLS + 1; x++) {
            double wx = x * (double) SPACING, wz = z * (double) SPACING;
            double rolling = 1.1 * Math.sin(.19 * wx) * Math.cos(.16 * wz)
                    + .42 * Math.sin(.31 * (wx + .7 * wz));
            double ridge = 5.2 * Math.exp(-((wx - 17.5) * (wx - 17.5)) / 30.0)
                    * Math.exp(-((wz - 31.0) * (wz - 31.0)) / 520.0);
            double spike = 7.0 * Math.exp(-((wx - 43) * (wx - 43) + (wz - 22) * (wz - 22)) / 7.0);
            samples[(z + 1) * side + x + 1] = (float) (rolling + ridge + spike);
        }
        return new HeightfieldPage(CELLS, SPACING, samples);
    }

    private static Camera camera(Vector3f location, Vector3f target) {
        Camera camera = new Camera(320, 240);
        camera.setFrustumPerspective(45f, 320f / 240f, .1f, 1000f);
        camera.setLocation(location);
        camera.lookAt(target, Vector3f.UNIT_Y);
        camera.update();
        return camera;
    }

    private enum Readiness { ALL, ROOT, NO_LEAVES }

    private static final class Scenario {
        final String name; final Camera camera; final double targetPixels;
        final int maxLeaves, revision; final Readiness readiness;
        Scenario(String name, Camera camera, double targetPixels, int maxLeaves, int revision, Readiness readiness) {
            this.name = name; this.camera = camera; this.targetPixels = targetPixels;
            this.maxLeaves = maxLeaves; this.revision = revision; this.readiness = readiness;
        }
    }
}
