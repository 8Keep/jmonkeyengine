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

import com.jme3.app.SimpleApplication;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.SceneProcessor;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.post.filters.KHRToneMapFilter;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.shadow.DirectionalLightShadowFilter;
import com.jme3.scene.Geometry;
import com.jme3.system.AppSettings;
import com.jme3.terrain.heightfield.HeightfieldGrid;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldHierarchy.Node;
import com.jme3.terrain.heightfield.HeightfieldLod;
import com.jme3.terrain.heightfield.HeightfieldLod.Patch;
import com.jme3.terrain.heightfield.HeightfieldLod.Selection;
import com.jme3.terrain.heightfield.HeightfieldLod.Transition;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldReconstruction;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureArray;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Original, finite resident fixture for screen-error heightfield selection and
 * crack-free patch transitions. The 128-cell canonical page is drawn through
 * pre-created shared-grid patches; it is not a streaming system, world
 * generator, or target-performance claim. No arguments opens interactive
 * camera control. Pass a renderer name and output directory to run the bounded
 * assertion-enabled capture path. The shadow filter is included to exercise
 * the PreShadow material technique; its whole-colour filtering remains a
 * diagnostic limitation, not physical-lighting acceptance.
 */
public final class TestHeightfieldLod extends SimpleApplication {
    private static final int CELLS = 128;
    private static final int PATCH_CELLS = 16;
    private static final float SPACING = .5f;
    private static final int MAX_FRAMES = 600;
    private static final double PIXEL_TARGET = 1.5;
    private static final int PATCH_BUDGET = 64;
    private static final float TRANSITION_STEP = .25f;

    private final Path output;
    private final CountDownLatch completed = new CountDownLatch(1);
    private final Map<Node, Geometry> geometryByNode = new IdentityHashMap<>();
    private final Set<Node> displayed = Collections.newSetFromMap(new IdentityHashMap<>());
    private HeightfieldPage page;
    private HeightfieldHierarchy hierarchy;
    private HeightfieldLod lod;
    private Transition transition;
    private Selection currentSelection;
    private CameraPath cameraPath;
    private HeightfieldLodGpuProbe gpuProbe;
    private FrameBuffer captureTarget;
    private volatile Throwable failure;
    private int frame;
    private int checks;

    private TestHeightfieldLod(Path output) {
        this.output = output;
    }

    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        Path output = args.length > 1 ? Paths.get(args[1]) : null;
        if (output != null && !assertions) {
            throw new IllegalStateException("Finite terrain LOD capture requires Java assertions (-ea)");
        }
        TestHeightfieldLod app = new TestHeightfieldLod(output);
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length > 0 ? args[0] : AppSettings.ANGLE_GLES3);
        settings.setResolution(960, 640);
        settings.setAudioRenderer(null);
        // Some desktop drivers emit a debug-group message for every draw.
        // Keep normal capture logs small; enable for driver diagnostics.
        settings.setGraphicsDebug(Boolean.getBoolean("jme.heightfieldLod.debug"));
        settings.setGammaCorrection(true);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (output != null) {
            if (!app.completed.await(90, TimeUnit.SECONDS)) {
                app.stop(false);
                throw new IllegalStateException("Terrain LOD capture exceeded 90 seconds", app.failure);
            }
            app.stop(true);
            if (app.failure != null) {
                throw new IllegalStateException("Terrain LOD capture failed", app.failure);
            }
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        failure = error == null ? new IllegalStateException(message) : error;
        failure.printStackTrace();
        completed.countDown();
    }

    @Override
    public void simpleInitApp() {
        try {
            flyCam.setEnabled(output == null);
            flyCam.setMoveSpeed(24);
            flyCam.setDragToRotate(true);
            HeightfieldGrid.requireSupported(renderer);
            page = makePage();
            hierarchy = new HeightfieldHierarchy(page, PATCH_CELLS, 0);
            lod = new HeightfieldLod(hierarchy);
            transition = new Transition(hierarchy);
            createPatches();
            if (output != null) {
                gpuProbe = new HeightfieldLodGpuProbe(assetManager, renderManager, renderer, page);
            }
            addLightingAndCapture();
            cameraPath = new CameraPath();
            cameraPath.apply(0);
            System.out.println("HEIGHTFIELD_LOD_SHADOW_NOTE: DirectionalLightShadowFilter is included to exercise PreShadow; its whole-colour filter is diagnostic only.");
        } catch (Throwable error) {
            fail(error);
        }
    }

    private static HeightfieldPage makePage() {
        int side = CELLS + 3;
        float[] samples = new float[side * side];
        for (int z = -1; z <= CELLS + 1; z++) {
            for (int x = -1; x <= CELLS + 1; x++) {
                double wx = x * (double) SPACING;
                double wz = z * (double) SPACING;
                samples[(z + 1) * side + x + 1] = (float) height(wx, wz);
            }
        }
        return new HeightfieldPage(CELLS, SPACING, samples);
    }

    private static double height(double x, double z) {
        double rolling = 1.1 * Math.sin(.19 * x) * Math.cos(.16 * z)
                + .42 * Math.sin(.31 * (x + .7 * z));
        double ridge = 5.2 * Math.exp(-((x - 17.5) * (x - 17.5)) / 30.0)
                * Math.exp(-((z - 31.0) * (z - 31.0)) / 520.0);
        double spike = 7.0 * Math.exp(-((x - 43.0) * (x - 43.0)
                + (z - 22.0) * (z - 22.0)) / 7.0);
        return rolling + ridge + spike;
    }

    private void createPatches() {
        HeightfieldGrid grid = new HeightfieldGrid(PATCH_CELLS);
        TextureArray heights = new TextureArray(Collections.singletonList(page.createImage()));
        heights.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        heights.setMagFilter(Texture.MagFilter.Nearest);
        heights.setWrap(Texture.WrapMode.EdgeClamp);
        for (Node node : hierarchy.getNodes()) {
            Geometry geometry = new Geometry("lod-" + node.getOriginX() + "-" + node.getOriginZ()
                    + "-" + node.getSpan(), grid.createLodPatchMesh(node));
            geometry.setShadowMode(ShadowMode.CastAndReceive);
            Material material = new Material(assetManager,
                    "Common/MatDefs/Terrain/Heightfield/Heightfield.j3md");
            material.setTexture("HeightPages", heights);
            material.setInt("HeightLayer", 0);
            material.setFloat("SampleSpacing", SPACING);
            material.setBoolean("UseHeightfieldLod", true);
            material.setInt("PageCells", CELLS);
            material.setInt("PatchGridCells", PATCH_CELLS);
            material.setVector2("PageLatticeOrigin", new Vector2f(0, 0));
            material.setVector2("PatchLatticeOrigin", new Vector2f(node.getOriginX(), node.getOriginZ()));
            material.setInt("SampleStride", node.getStride());
            material.setFloat("InteriorMorph", 0);
            material.setFloat("EdgeMorphWest", 0);
            material.setFloat("EdgeMorphEast", 0);
            material.setFloat("EdgeMorphSouth", 0);
            material.setFloat("EdgeMorphNorth", 0);
            material.setFloat("SnowLine", 7.8f);
            material.setFloat("SnowFade", 1.5f);
            material.setColor("GrassColor", new ColorRGBA(.12f, .20f, .075f, 1));
            material.setColor("RockColor", new ColorRGBA(.29f, .26f, .22f, 1));
            material.setColor("SnowColor", new ColorRGBA(.83f, .87f, .9f, 1));
            geometry.setMaterial(material);
            geometryByNode.put(node, geometry);
        }
    }

    private void addLightingAndCapture() {
        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-.55f, -1f, -.42f).normalizeLocal());
        sun.setColor(new ColorRGBA(1.25f, 1.18f, 1.03f, 1));
        rootNode.addLight(sun);
        AmbientLight sky = new AmbientLight();
        sky.setColor(new ColorRGBA(.22f, .27f, .34f, 1));
        rootNode.addLight(sky);

        FilterPostProcessor post = new FilterPostProcessor(assetManager);
        post.setFrameBufferFormat(Image.Format.RGBA16F);
        post.setFrameBufferDepthFormat(Image.Format.Depth24);
        DirectionalLightShadowFilter shadows = new DirectionalLightShadowFilter(assetManager, 1024, 3);
        shadows.setLight(sun);
        shadows.setShadowIntensity(.45f);
        post.addFilter(shadows);
        post.addFilter(new KHRToneMapFilter());
        post.addFilter(new FXAAFilter());
        viewPort.addProcessor(post);
        if (output != null) {
            FrameBuffer target = new FrameBuffer(cam.getWidth(), cam.getHeight(), 1);
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA16F));
            target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
            captureTarget = target;
            viewPort.setOutputFrameBuffer(target);
            viewPort.addProcessor(new Capture(target));
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (failure != null || hierarchy == null) return;
        try {
            frame++;
            if (output != null) cameraPath.apply(frame);
            double target = output == null ? PIXEL_TARGET : requestedPixels(frame);
            currentSelection = lod.select(cam, target, PATCH_BUDGET, node -> true);
            transition.advance(currentSelection, TRANSITION_STEP);
            List<Patch> patches = transition.getPatches();
            verifyFrame(patches);
            syncDrawSet(patches);
            if (output != null && isCaptureFrame(frame)) {
                System.out.println("HEIGHTFIELD_LOD_GPU frame=" + frame + " "
                        + gpuProbe.verify(patches, captureTarget));
                printMetrics(patches, target);
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private double requestedPixels(int currentFrame) {
        if (currentFrame < 31 || (currentFrame >= 221 && currentFrame < 291)) return 1000;
        if (currentFrame < 211) return 1.25;
        if (currentFrame < 391) return 1.0;
        if (currentFrame < 501) return 2.0;
        return PIXEL_TARGET;
    }

    private void syncDrawSet(List<Patch> patches) {
        Set<Node> next = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Patch patch : patches) {
            Node node = patch.getNode();
            next.add(node);
            Material material = geometryByNode.get(node).getMaterial();
            material.setFloat("InteriorMorph", patch.getMorph());
            material.setFloat("EdgeMorphWest", patch.getEdgeMorph(0));
            material.setFloat("EdgeMorphEast", patch.getEdgeMorph(1));
            material.setFloat("EdgeMorphSouth", patch.getEdgeMorph(2));
            material.setFloat("EdgeMorphNorth", patch.getEdgeMorph(3));
            if (!displayed.contains(node)) rootNode.attachChild(geometryByNode.get(node));
        }
        for (Node prior : displayed) {
            if (!next.contains(prior)) rootNode.detachChild(geometryByNode.get(prior));
        }
        displayed.clear();
        displayed.addAll(next);
    }

    private void verifyFrame(List<Patch> patches) {
        int[] coverage = new int[CELLS * CELLS];
        for (Patch patch : patches) {
            Node node = patch.getNode();
            int x0 = node.getOriginX(), z0 = node.getOriginZ();
            for (int z = z0; z < z0 + node.getSpan(); z++) {
                for (int x = x0; x < x0 + node.getSpan(); x++) coverage[z * CELLS + x]++;
            }
            int stride = node.getStride();
            for (int z = z0; z <= z0 + node.getSpan(); z += stride) {
                for (int x = x0; x <= x0 + node.getSpan(); x += stride) {
                    float alpha = vertexMorph(patch, x, z);
                    Vector3f vertex = HeightfieldReconstruction.morphVertex(page, x, z, stride, alpha, null);
                    require(Float.isFinite(vertex.x) && Float.isFinite(vertex.y) && Float.isFinite(vertex.z),
                            "all current-cover reconstructed positions are finite");
                    if ((x == x0 || x == x0 + node.getSpan())
                            && (z == z0 || z == z0 + node.getSpan())) {
                        require(vertex.y == page.getHeight(x, z), "patch corners retain canonical source samples");
                    }
                }
            }
        }
        for (int count : coverage) require(count == 1, "transition cover owns every source cell exactly once");
        for (int i = 0; i < patches.size(); i++) {
            for (int j = i + 1; j < patches.size(); j++) {
                Node a = patches.get(i).getNode(), b = patches.get(j).getNode();
                if (!touches(a, b)) continue;
                require(Math.abs(a.getLevel() - b.getLevel()) <= 1,
                        "current cover remains 2:1 balanced across edges and corners");
                int sideA = touchingSide(a, b);
                if (sideA >= 0) verifySharedEdge(patches.get(i), patches.get(j), sideA);
            }
        }
        require(transition.getMaximumPixels(cam) >= 0, "transition quality estimate is nonnegative");
    }

    private void verifySharedEdge(Patch a, Patch b, int sideA) {
        Node na = a.getNode(), nb = b.getNode();
        int sideB = sideA ^ 1;
        int overlapStart = sideA < 2
                ? Math.max(na.getOriginZ(), nb.getOriginZ())
                : Math.max(na.getOriginX(), nb.getOriginX());
        int overlapEnd = sideA < 2
                ? Math.min(na.getOriginZ() + na.getSpan(), nb.getOriginZ() + nb.getSpan())
                : Math.min(na.getOriginX() + na.getSpan(), nb.getOriginX() + nb.getSpan());
        if (na.getLevel() == nb.getLevel()) {
            require(Math.abs(a.getEdgeMorph(sideA) - b.getEdgeMorph(sideB)) <= 1e-6f,
                    "equal-level shared edges use one morph value");
            int stride = Math.min(na.getStride(), nb.getStride());
            for (int t = overlapStart; t <= overlapEnd; t += stride) {
                int ax = sideA < 2 ? (sideA == 0 ? na.getOriginX() : na.getOriginX() + na.getSpan()) : t;
                int az = sideA < 2 ? t : (sideA == 2 ? na.getOriginZ() : na.getOriginZ() + na.getSpan());
                int bx = sideB < 2 ? (sideB == 0 ? nb.getOriginX() : nb.getOriginX() + nb.getSpan()) : t;
                int bz = sideB < 2 ? t : (sideB == 2 ? nb.getOriginZ() : nb.getOriginZ() + nb.getSpan());
                Vector3f pa = HeightfieldReconstruction.morphVertex(page, ax, az, na.getStride(),
                        vertexMorph(a, ax, az), null);
                Vector3f pb = HeightfieldReconstruction.morphVertex(page, bx, bz, nb.getStride(),
                        vertexMorph(b, bx, bz), null);
                require(pa.distance(pb) <= 1e-5f, "same-level shared edge vertices coincide");
            }
            return;
        }

        Patch fine = na.getStride() < nb.getStride() ? a : b;
        Patch coarse = fine == a ? b : a;
        int fineSide = fine == a ? sideA : sideB;
        Node nf = fine.getNode(), nc = coarse.getNode();
        require(fine.getEdgeMorph(fineSide) == 1f,
                "fine edge adjacent to coarser patch is fully collapsed");
        for (int t = overlapStart; t <= overlapEnd; t += nf.getStride()) {
            int x = fineSide < 2 ? (fineSide == 0 ? nf.getOriginX() : nf.getOriginX() + nf.getSpan()) : t;
            int z = fineSide < 2 ? t : (fineSide == 2 ? nf.getOriginZ() : nf.getOriginZ() + nf.getSpan());
            Vector3f fineVertex = HeightfieldReconstruction.morphVertex(page, x, z, nf.getStride(),
                    vertexMorph(fine, x, z), null);
            int collapsedX = Math.round(fineVertex.x / SPACING);
            int collapsedZ = Math.round(fineVertex.z / SPACING);
            float coarseX = collapsedX * SPACING, coarseZ = collapsedZ * SPACING;
            float coarseHeight = page.getHeight(collapsedX, collapsedZ);
            require(Math.abs(fineVertex.x - coarseX) <= 1e-5f
                    && Math.abs(fineVertex.z - coarseZ) <= 1e-5f
                    && collapsedX % nc.getStride() == 0 && collapsedZ % nc.getStride() == 0
                    && Math.abs(fineVertex.y - coarseHeight) <= 1e-5f,
                    "fine edge collapses onto the stable coarse edge surface");
        }
        require(coarse.getMorph() == 0f, "coarse patch does not morph beside a finer patch");
    }

    private static float vertexMorph(Patch patch, int x, int z) {
        Node node = patch.getNode();
        int localX = (x - node.getOriginX()) / node.getStride();
        int localZ = (z - node.getOriginZ()) / node.getStride();
        if ((localX == 0 || localX == PATCH_CELLS) && (localZ == 0 || localZ == PATCH_CELLS)) return 0;
        float alpha = patch.getMorph();
        if (localX == 0) alpha = Math.max(alpha, patch.getEdgeMorph(0));
        if (localX == PATCH_CELLS) alpha = Math.max(alpha, patch.getEdgeMorph(1));
        if (localZ == 0) alpha = Math.max(alpha, patch.getEdgeMorph(2));
        if (localZ == PATCH_CELLS) alpha = Math.max(alpha, patch.getEdgeMorph(3));
        return alpha;
    }

    private static boolean touches(Node a, Node b) {
        return a != b && a.getOriginX() <= b.getOriginX() + b.getSpan()
                && b.getOriginX() <= a.getOriginX() + a.getSpan()
                && a.getOriginZ() <= b.getOriginZ() + b.getSpan()
                && b.getOriginZ() <= a.getOriginZ() + a.getSpan();
    }

    private static int touchingSide(Node a, Node b) {
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

    private void printMetrics(List<Patch> patches, double target) {
        double selectionPixels = currentSelection.getMaximumPixels();
        double transitionPixels = transition.getMaximumPixels(cam);
        boolean intermediate = patches.stream().anyMatch(p -> inFlight(p.getMorph())
                || inFlight(p.getEdgeMorph(0)) || inFlight(p.getEdgeMorph(1))
                || inFlight(p.getEdgeMorph(2)) || inFlight(p.getEdgeMorph(3)));
        System.out.println("HEIGHTFIELD_LOD frame=" + frame + " patches=" + patches.size()
                + " coveredCells=" + (CELLS * CELLS) + " targetPixels=" + target
                + " selectionMaxPixels=" + selectionPixels + " transitionMaxPixels=" + transitionPixels
                + " budgetLimited=" + currentSelection.isBudgetLimited()
                + " readinessLimited=" + currentSelection.isReadinessLimited()
                + " sourceLimited=" + currentSelection.isSourceLimited()
                + " transitioning=" + transition.isTransitioning() + " intermediateMorph=" + intermediate
                + " cpuChecks=" + checks);
    }

    private static boolean inFlight(float alpha) { return alpha > 0 && alpha < 1; }

    private static boolean isCaptureFrame(int f) {
        return f == 30 || f == 31 || f == 33 || f == 35
                || f == 120 || f == 240 || f == 420 || f == 540;
    }

    private String captureLabel(int f) {
        switch (f) {
            case 30: return "overview-f030";
            case 33: return "split-mid-morph-f033";
            case 120: return "grazing-f120";
            case 240: return "telephoto-f240";
            case 420: return "orthographic-f420";
            case 540: return "return-f540";
            default: return null;
        }
    }

    private void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new IllegalStateException(message + " at frame " + frame);
    }

    private void fail(Throwable error) {
        failure = error;
        error.printStackTrace();
        completed.countDown();
    }

    private final class CameraPath {
        void apply(int f) {
            float aspect = (float) cam.getWidth() / cam.getHeight();
            if (f >= 391 && f < 501) {
                cam.setParallelProjection(true);
                cam.setFrustum(.1f, 500f, -38f * aspect, 38f * aspect, 38f, -38f);
                cam.setLocation(new Vector3f(32, 110, 32));
                cam.lookAt(new Vector3f(32, 1, 32), Vector3f.UNIT_Z);
                return;
            }
            cam.setParallelProjection(false);
            float fov = f < 31 ? 58f : f < 211 ? 47f + 8f * (float) Math.sin(f * .025)
                    : f < 291 ? 27f : f < 391 ? 67f : 39f + 8f * (float) Math.sin(f * .02);
            cam.setFrustumPerspective(fov, aspect, .15f, 500f);
            if (f < 31) {
                cam.setLocation(new Vector3f(32, 86, -48));
                cam.lookAt(new Vector3f(32, 1, 32), Vector3f.UNIT_Y);
            } else if (f < 211) {
                float t = Math.min(1, (f - 31) / 180f);
                Vector3f location = new Vector3f(1 + 2 * t, 5.5f + .8f * (float) Math.sin(f * .035), 3 + 5 * t);
                Vector3f target = new Vector3f(54 - 5 * t, (float) height(44, 19) + .8f, 18 + 10 * t);
                cam.setLocation(location);
                cam.lookAt(target, Vector3f.UNIT_Y);
            } else if (f < 291) {
                cam.setLocation(new Vector3f(103, 64, -38));
                cam.lookAt(new Vector3f(32, 2, 32), Vector3f.UNIT_Y);
            } else if (f < 391) {
                cam.setLocation(new Vector3f(3, 7, 5));
                cam.lookAt(new Vector3f(52, (float) height(48, 19) + 1, 22), Vector3f.UNIT_Y);
            } else {
                cam.setLocation(new Vector3f(82, 38, 75));
                cam.lookAt(new Vector3f(32, 2, 32), Vector3f.UNIT_Y);
            }
        }
    }

    private final class Capture implements SceneProcessor {
        private final FrameBuffer target;
        private boolean initialized;
        private Capture(FrameBuffer target) { this.target = target; }
        @Override public void initialize(RenderManager rm, ViewPort vp) { initialized = true; }
        @Override public boolean isInitialized() { return initialized; }
        @Override public void reshape(ViewPort vp, int w, int h) { }
        @Override public void preFrame(float tpf) { }
        @Override public void postQueue(RenderQueue queue) { }
        @Override public void cleanup() {
            initialized = false;
            if (gpuProbe != null) gpuProbe.close();
            renderer.deleteFrameBuffer(target);
        }
        @Override public void setProfiler(AppProfiler profiler) { }
        @Override public void postFrame(FrameBuffer out) {
            if (failure != null) return;
            try {
                String label = captureLabel(frame);
                if (label == null && frame >= 31 && frame <= 39) {
                    label = "motion-split-f" + String.format("%03d", frame);
                }
                if (label != null) {
                    writeCapture(label);
                    if (frame == 33) {
                        boolean intermediate = transition.getPatches().stream().anyMatch(p -> inFlight(p.getMorph())
                                || inFlight(p.getEdgeMorph(0)) || inFlight(p.getEdgeMorph(1))
                                || inFlight(p.getEdgeMorph(2)) || inFlight(p.getEdgeMorph(3)));
                        require(intermediate, "fixed mid-morph capture must contain intermediate alpha");
                    }
                }
                if (frame >= MAX_FRAMES) {
                    System.out.println("HEIGHTFIELD_LOD_PASS frames=" + frame + " checks=" + checks
                            + " output=" + output.toAbsolutePath());
                    completed.countDown();
                }
            } catch (Throwable error) {
                fail(error);
            }
        }

        private void writeCapture(String label) throws Exception {
            Files.createDirectories(output);
            int width = target.getWidth(), height = target.getHeight();
            ByteBuffer bytes = BufferUtils.createByteBuffer(width * height * 16)
                    .order(ByteOrder.nativeOrder());
            // Read the actual post-filter RGBA16F target as float, then apply
            // one display transfer instead of quantizing an intermediate to 8-bit linear.
            renderer.readFrameBufferWithFormat(target, bytes, Image.Format.RGBA32F);
            FloatBuffer linear = bytes.asFloatBuffer();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = ((height - 1 - y) * width + x) * 4;
                    int r = display(linear.get(i));
                    int g = display(linear.get(i + 1));
                    int b = display(linear.get(i + 2));
                    image.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
            }
            if (!ImageIO.write(image, "png", output.resolve(label + ".png").toFile())) {
                throw new IllegalStateException("PNG writer unavailable");
            }
            System.out.println("HEIGHTFIELD_LOD_CAPTURE " + label);
        }

        private int display(float linear) {
            if (!Float.isFinite(linear)) throw new IllegalStateException("non-finite post-filter HDR pixel");
            linear = Math.max(0, Math.min(1, linear));
            float encoded = linear <= .0031308f ? linear * 12.92f
                    : 1.055f * (float) Math.pow(linear, 1.0 / 2.4) - .055f;
            return Math.min(255, Math.max(0, Math.round(encoded * 255)));
        }
    }
}
