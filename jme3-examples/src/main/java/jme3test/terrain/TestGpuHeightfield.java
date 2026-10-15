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
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.bounding.BoundingBox;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.post.filters.KHRToneMapFilter;
import com.jme3.renderer.Caps;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.renderer.opengl.GLFence;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.instancing.IndirectGeometry;
import com.jme3.shadow.DirectionalLightVisibilityRenderer;
import com.jme3.system.AppSettings;
import com.jme3.terrain.heightfield.HeightfieldGpuSelection;
import com.jme3.terrain.heightfield.HeightfieldGpuTransitions;
import com.jme3.terrain.heightfield.HeightfieldGrid;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.texture.Image;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureArray;
import com.jme3.util.SkyFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Original, asset-free interactive GPU-selected heightfield scene. The terrain
 * is one immutable resident page sampled from ScenicHeightField; this example
 * does not stream pages or claim a performance result.
 *
 * <p>No arguments opens an interactive window. Press 1, 2, or 3 for overview,
 * valley, and close camera presets. Passing a renderer and output directory
 * takes those three views as PNG files and exits. Optional --profile records
 * finite frame timings before screenshots; --no-shadows disables sun visibility
 * while retaining shadow-map rendering for a color-only comparison. Combine
 * --profile and --static-selection to measure unchanged draws with selection
 * dispatched only once per camera preset. --edit-tour captures one valley hill
 * edit and restore; it cannot be combined with profiling. --cpu-selection uses
 * the ordinary CPU oracle and instanced material route without compute/indirect
 * draws; it also works in interactive mode. --smooth-lod enables GPU sibling
 * morphing, with each transaction taking about .2 seconds during interaction.
 * Finite captures use a fixed .25 step and allow the cover to settle.
 */
public final class TestGpuHeightfield extends SimpleApplication {
    private static final int CELLS = 256;
    private static final int PATCH_CELLS = 32;
    private static final float SPACING = .5f;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 800;
    private static final int PROFILE_WARMUP = 60;
    private static final int PROFILE_FRAMES_PER_VIEW = 120;
    private static final String[] CAPTURE_NAMES = {"overview", "valley", "close"};
    private static final String[] EDIT_CAPTURE_NAMES = {"before-edit", "after-edit", "restored"};
    private static final int MAX_RETIRED_REVISIONS = 2;
    private static final int EDIT_SETTLED_FRAMES = 5;

    private final Path output;
    private final boolean finiteRun;
    private final boolean profileRun;
    private final boolean staticSelection;
    private final boolean editTour;
    private final boolean cpuSelection;
    private final boolean smoothLod;
    private float transitionStep;
    private final CountDownLatch capturesFinished = new CountDownLatch(1);
    private volatile Throwable failure;
    private TerrainRevision activeRevision;
    private final Deque<RetiredRevision> retiredRevisions = new ArrayDeque<>();
    private boolean requestedEdit;
    private boolean revisionChangeRequested;
    private int nextRevision = 2;
    private DirectionalLightVisibilityRenderer visibility;
    private boolean shadowsEnabled;
    private FrameBuffer captureTarget;
    private ScreenshotAppState screenshots;
    private int captureFrame;
    private int nextCapture;
    private int writtenCaptures;
    private boolean verifyCaptures;
    private boolean changeCameraAfterCapture;
    private HeightfieldFrameTimings timings;
    private int profileFrame;
    private int drainFrames;
    private boolean profileWritten;
    private int profilePose = -1;
    private int editTourStage;
    private int editTourSettledFrames;

    private TestGpuHeightfield(boolean finiteRun, Path output, boolean shadowsEnabled,
            boolean profileRun, boolean staticSelection, boolean editTour, boolean cpuSelection, boolean smoothLod) {
        this.finiteRun = finiteRun;
        this.output = output;
        this.shadowsEnabled = shadowsEnabled;
        this.profileRun = profileRun;
        this.staticSelection = staticSelection;
        this.editTour = editTour;
        this.cpuSelection = cpuSelection;
        this.smoothLod = smoothLod;
    }

    public static void main(String[] args) throws Exception {
        boolean shadows = true, profile = false, staticSelection = false, editTour = false, cpu = false, smooth = false;
        String rendererName = AppSettings.ANGLE_GLES3;
        Path output = null;
        int positional = 0;
        for (String arg : args) {
            if ("--no-shadows".equals(arg)) shadows = false;
            else if ("--profile".equals(arg)) profile = true;
            else if ("--static-selection".equals(arg)) staticSelection = true;
            else if ("--edit-tour".equals(arg)) editTour = true;
            else if ("--cpu-selection".equals(arg)) cpu = true;
            else if ("--smooth-lod".equals(arg)) smooth = true;
            else if (!arg.startsWith("--") && positional < 2) {
                if (positional == 0) rendererName = arg;
                else output = Paths.get(arg);
                positional++;
            }
            else throw new IllegalArgumentException("Usage: TestGpuHeightfield [<renderer> [<output-directory>]] [--cpu-selection | --smooth-lod] [--no-shadows] [--profile [--static-selection] | --edit-tour]");
        }
        if (staticSelection && !profile) throw new IllegalArgumentException("--static-selection requires --profile");
        if (smooth && (cpu || staticSelection)) {
            throw new IllegalArgumentException("--smooth-lod requires GPU selection on every frame");
        }
        boolean finite = output != null;
        if (profile && !finite) throw new IllegalArgumentException("--profile requires an output directory");
        if (editTour && (!finite || profile || staticSelection)) {
            throw new IllegalArgumentException("--edit-tour requires an output directory and cannot be combined with --profile or --static-selection");
        }
        TestGpuHeightfield app = new TestGpuHeightfield(finite, output,
                shadows, profile, staticSelection, editTour, cpu, smooth);
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(rendererName);
        settings.setResolution(WIDTH, HEIGHT);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(true);
        settings.setGraphicsDebug(true);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (finite) {
            boolean completed = app.capturesFinished.await(90, TimeUnit.SECONDS);
            app.stop(completed);
            if (!completed) {
                throw new IllegalStateException("GPU heightfield capture timed out", app.failure);
            }
            if (app.failure != null) {
                throw new IllegalStateException("GPU heightfield capture failed", app.failure);
            }
            if (app.editTour) {
                System.out.println("GPU_HEIGHTFIELD_EDIT_TOUR_PASS captures=before-edit:r1,after-edit:r2,restored:r3 cpu=" + cpu);
            } else {
                System.out.println("GPU_HEIGHTFIELD_CAPTURE_PASS views=3 pageCells=256 spacing=0.5m cpu=" + cpu);
            }
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        fail(error == null ? new IllegalStateException(message) : error);
    }

    private void fail(Throwable error) {
        failure = error;
        error.printStackTrace();
        capturesFinished.countDown();
    }

    @Override
    public void simpleInitApp() {
        try {
            if (!cpuSelection && (!renderer.getCaps().contains(Caps.ComputeShader)
                    || !renderer.getCaps().contains(Caps.UniformBufferObject))) {
                throw new UnsupportedOperationException("GPU heightfield selection requires compute shaders and UBOs");
            }
            HeightfieldGrid.requireSupported(renderer);
            System.out.println("HEIGHTFIELD_SELECTION_MODE cpu=" + cpuSelection
                    + " smooth=" + smoothLod + " computeCap=" + renderer.getCaps().contains(Caps.ComputeShader)
                    + " indirectCap=" + renderer.getCaps().contains(Caps.IndexedIndirectDraw));
            setDisplayFps(false);
            setDisplayStatView(false);
            flyCam.setMoveSpeed(15f);
            flyCam.setDragToRotate(true);
            flyCam.setEnabled(!finiteRun);
            cam.setFrustumPerspective(55f, (float) WIDTH / HEIGHT, .1f, 600f);
            viewPort.setBackgroundColor(new ColorRGBA(.42f, .59f, .76f, 1));

            activeRevision = createRevision(false, 1);
            rootNode.attachChild(activeRevision.geometry);

            DirectionalLight sun = new DirectionalLight();
            sun.setDirection(new Vector3f(-.55f, -.8f, -.35f).normalizeLocal());
            sun.setColor(new ColorRGBA(1.55f, 1.42f, 1.25f, 1));
            rootNode.addLight(sun);
            AmbientLight ambient = new AmbientLight();
            ambient.setColor(new ColorRGBA(.27f, .34f, .45f, 1));
            rootNode.addLight(ambient);
            rootNode.attachChild(SkyFactory.createSky(assetManager, TestModernHeightfield.sky(),
                    SkyFactory.EnvMapType.CubeMap));

            visibility = new DirectionalLightVisibilityRenderer(assetManager, 2048, 3);
            visibility.setLight(sun);
            visibility.setShadowIntensity(shadowsEnabled ? 1 : 0);
            visibility.setShadowZExtend(220);
            visibility.addReceiver(activeRevision.material);
            viewPort.addProcessor(visibility);

            FilterPostProcessor post = new FilterPostProcessor(assetManager);
            post.setFrameBufferFormat(Image.Format.RGBA16F);
            post.setFrameBufferDepthFormat(Image.Format.Depth24);
            post.addFilter(new KHRToneMapFilter());
            post.addFilter(new FXAAFilter());
            viewPort.addProcessor(post);
            if (profileRun) {
                timings = new HeightfieldFrameTimings(renderer, PROFILE_WARMUP);
                // End queries after this viewport's shadow/main/post commands.
                viewPort.addProcessor(timings);
            }

            setCameraPose(editTour ? 1 : 0);
            if (!finiteRun) {
                installControls();
                installHud();
            } else {
                Files.createDirectories(output);
                captureTarget = new FrameBuffer(WIDTH, HEIGHT, 1);
                captureTarget.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA16F));
                captureTarget.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
                viewPort.setOutputFrameBuffer(captureTarget);
                screenshots = new ScreenshotAppState(output.toAbsolutePath().toString()
                        + java.io.File.separator, CAPTURE_NAMES[0]) {
                    @Override
                    protected void writeImageFile(java.io.File file) throws IOException {
                        // The SDL presentation buffer is still linear when this
                        // callback runs. Read float output and encode before quantization.
                        if (!ImageIO.write(TestModernHeightfield.readDisplay(TestGpuHeightfield.this.renderer,
                                captureTarget, WIDTH, HEIGHT), "png", file)) {
                            throw new IOException("PNG writer unavailable");
                        }
                        writtenCaptures++;
                    }
                };
                screenshots.setIsNumbered(false);
                stateManager.attach(screenshots);
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private HeightfieldPage createPage(boolean edited) {
        int side = CELLS + 3;
        float[] heights = new float[side * side];
        for (int z = -1; z <= CELLS + 1; z++) {
            for (int x = -1; x <= CELLS + 1; x++) {
                double worldX = x * (double) SPACING;
                double worldZ = z * (double) SPACING;
                float height = ScenicHeightField.height(worldX * 8.0, worldZ * 8.0) / 8f;
                if (edited) {
                    double dx = worldX - 66.0;
                    double dz = worldZ - 72.0;
                    height += (float) (10.0 * Math.exp(-.5 * (dx * dx + dz * dz) / 100.0));
                }
                heights[(z + 1) * side + x + 1] = height;
            }
        }
        return new HeightfieldPage(CELLS, SPACING, heights);
    }

    private TerrainRevision createRevision(boolean edited, int label) {
        HeightfieldGpuSelection nextSelection = null;
        HeightfieldCpuPatchBatch nextCpu = null;
        HeightfieldGpuTransitions nextTransitions = null;
        TextureArray nextHeights = null;
        try {
            HeightfieldPage page = createPage(edited);
            HeightfieldHierarchy hierarchy = new HeightfieldHierarchy(page, PATCH_CELLS, 0);
            if (smoothLod) {
                nextTransitions = new HeightfieldGpuTransitions(assetManager, renderer, hierarchy);
            } else if (!cpuSelection) {
                nextSelection = new HeightfieldGpuSelection(assetManager, renderer, hierarchy,
                        HeightfieldGpuSelection.DrawCover.Complete);
            }
            nextHeights = new TextureArray(java.util.Collections.singletonList(page.createImage()));
            nextHeights.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            nextHeights.setMagFilter(Texture.MagFilter.Nearest);
            nextHeights.setWrap(Texture.WrapMode.EdgeClamp);
            HeightfieldGrid revisionGrid = new HeightfieldGrid(PATCH_CELLS);
            Mesh mesh = revisionGrid.createLodPatchMesh(hierarchy.getRoot());
            Material material = new Material(assetManager,
                    "Common/MatDefs/Terrain/Heightfield/Heightfield.j3md");
            material.setTexture("HeightPages", nextHeights);
            material.setInt("HeightLayer", 0);
            Geometry geometry;
            if (cpuSelection) {
                nextCpu = new HeightfieldCpuPatchBatch(renderer, hierarchy, mesh, material);
                geometry = nextCpu.getGeometry();
            } else {
                if (nextTransitions != null) nextTransitions.configureMaterial(material);
                else nextSelection.configureMaterial(material);
                geometry = new IndirectGeometry("GPU selected terrain r" + label,
                        mesh, nextTransitions != null ? nextTransitions.getCommand() : nextSelection.getCommand(),
                        0, hierarchy.getRoot().getBound());
            }
            geometry.setShadowMode(ShadowMode.CastAndReceive);
            material.setFloat("SnowLine", 26f);
            material.setFloat("SnowFade", 4f);
            material.setColor("GrassColor", new ColorRGBA(.16f, .24f, .085f, 1));
            material.setColor("RockColor", new ColorRGBA(.28f, .255f, .22f, 1));
            geometry.setMaterial(material);
            return new TerrainRevision(edited, label, page, nextSelection, nextCpu, nextTransitions, nextHeights, geometry, material);
        } catch (Throwable error) {
            if (nextTransitions != null) nextTransitions.close();
            if (nextSelection != null) nextSelection.close();
            if (nextCpu != null) nextCpu.close();
            if (nextHeights != null) renderer.deleteImage(nextHeights.getImage());
            if (error instanceof RuntimeException) throw (RuntimeException) error;
            if (error instanceof Error) throw (Error) error;
            throw new IllegalStateException("Could not create terrain revision " + label, error);
        }
    }

    private void installControls() {
        inputManager.addMapping("HeightfieldOverview", new KeyTrigger(KeyInput.KEY_1));
        inputManager.addMapping("HeightfieldValley", new KeyTrigger(KeyInput.KEY_2));
        inputManager.addMapping("HeightfieldClose", new KeyTrigger(KeyInput.KEY_3));
        inputManager.addMapping("HeightfieldEdit", new KeyTrigger(KeyInput.KEY_E));
        inputManager.addMapping("HeightfieldShadows", new KeyTrigger(KeyInput.KEY_H));
        inputManager.addListener(cameraControls, "HeightfieldOverview", "HeightfieldValley",
                "HeightfieldClose", "HeightfieldEdit", "HeightfieldShadows");
    }

    private final ActionListener cameraControls = new ActionListener() {
        @Override
        public void onAction(String name, boolean pressed, float tpf) {
            if (!pressed) return;
            if ("HeightfieldOverview".equals(name)) setCameraPose(0);
            else if ("HeightfieldValley".equals(name)) setCameraPose(1);
            else if ("HeightfieldClose".equals(name)) setCameraPose(2);
            else if ("HeightfieldEdit".equals(name)) {
                requestedEdit = !requestedEdit;
                revisionChangeRequested = requestedEdit != activeRevision.edited;
            }
            else if ("HeightfieldShadows".equals(name)) {
                shadowsEnabled = !shadowsEnabled;
                visibility.setShadowIntensity(shadowsEnabled ? 1 : 0);
            }
        }
    };

    private void installHud() {
        com.jme3.font.BitmapText text = new com.jme3.font.BitmapText(guiFont);
        text.setSize(16f);
        text.setText((cpuSelection ? "CPU" : smoothLod ? "GPU SMOOTH" : "GPU") + " HEIGHTFIELD  |  1 overview  2 valley  3 close  |  E toggle valley hill  |  H sun shadows\n"
                + "WASD/QZ move  |  hold left mouse and drag to look\n"
                + "One resident 128 m page; E rebuilds a 10 m hill revision. No streaming.");
        text.setLocalTranslation(16, cam.getHeight() - 16, 0);
        guiNode.attachChild(text);
    }

    private void setCameraPose(int pose) {
        if (pose == 0) {
            cam.setLocation(new Vector3f(137f, 70f, -15f));
            cam.lookAt(new Vector3f(64f, 15f, 66f), Vector3f.UNIT_Y);
        } else if (pose == 1) {
            cam.setLocation(new Vector3f(43f, ScenicHeightField.height(344, 72) / 8f + 9f, 9f));
            cam.lookAt(new Vector3f(66f, 17f, 72f), Vector3f.UNIT_Y);
        } else {
            float x = 48f, z = 55f;
            cam.setLocation(new Vector3f(x, ScenicHeightField.height(x * 8, z * 8) / 8f + 7f, z));
            cam.lookAt(new Vector3f(61f, ScenicHeightField.height(488, 440) / 8f + 1f, 55f),
                    Vector3f.UNIT_Y);
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (activeRevision == null || failure != null) return;
        try {
            transitionStep = finiteRun ? .25f : Math.min(1f, Math.max(0f, tpf) / .2f);
            retireSignaledRevisions();
            applyRequestedRevision();
            if (profileRun && !profileWritten) {
                if (profileFrame < PROFILE_WARMUP + PROFILE_FRAMES_PER_VIEW * CAPTURE_NAMES.length) {
                    int pose = Math.max(0, (profileFrame - PROFILE_WARMUP) / PROFILE_FRAMES_PER_VIEW);
                    timings.beginFrame(CAPTURE_NAMES[pose]);
                    setCameraPose(pose);
                    profileFrame++;
                    if (!staticSelection || profilePose != pose) {
                        activeRevision.dispatch(cam, transitionStep);
                        profilePose = pose;
                    }
                    if (profileFrame == PROFILE_WARMUP + PROFILE_FRAMES_PER_VIEW * CAPTURE_NAMES.length) {
                        timings.stopSampling();
                    }
                    return;
                }
                // Availability polling never waits for results. Keep rendering
                // until all queries retire, then write outside measured frames.
                if (!timings.isDrained()) {
                    if (++drainFrames > 300) throw new IllegalStateException("GPU timing queries did not retire");
                    activeRevision.dispatch(cam, transitionStep);
                    return;
                }
                timings.write(output);
                profileWritten = true;
                setCameraPose(0);
                System.out.println("GPU_HEIGHTFIELD_PROFILE_PASS frames="
                        + PROFILE_FRAMES_PER_VIEW * CAPTURE_NAMES.length + " warmup=" + PROFILE_WARMUP
                        + " selection=" + (staticSelection ? "once-per-view" : "every-frame"));
            }
            activeRevision.dispatch(cam, transitionStep);
            if (!finiteRun) return;
            if (editTour) {
                updateEditTour();
                return;
            }
            if (verifyCaptures) {
                if (writtenCaptures != captureNames().length) {
                    throw new IOException("Not all screenshots were written during this run");
                }
                for (String name : captureNames()) {
                    if (!Files.isRegularFile(output.resolve(name + ".png"))) {
                        throw new IOException("Screenshot was not written: " + name + ".png");
                    }
                }
                capturesFinished.countDown();
                verifyCaptures = false;
            } else if (changeCameraAfterCapture) {
                changeCameraAfterCapture = false;
                captureFrame = 0;
                setCameraPose(nextCapture);
            } else if (++captureFrame >= (smoothLod ? 110 : 5)) {
                captureFrame = 0;
                screenshots.setFileName(CAPTURE_NAMES[nextCapture]);
                screenshots.takeScreenshot();
                nextCapture++;
                if (nextCapture < CAPTURE_NAMES.length) {
                    changeCameraAfterCapture = true;
                } else {
                    verifyCaptures = true;
                }
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private String[] captureNames() {
        return editTour ? EDIT_CAPTURE_NAMES : CAPTURE_NAMES;
    }

    private void updateEditTour() {
        if (verifyCaptures) {
            if (writtenCaptures != EDIT_CAPTURE_NAMES.length) {
                throw new IllegalStateException("Not all edit-tour screenshots were written");
            }
            for (String name : EDIT_CAPTURE_NAMES) {
                if (!Files.isRegularFile(output.resolve(name + ".png"))) {
                    throw new IllegalStateException("Edit-tour screenshot was not written: " + name + ".png");
                }
            }
            capturesFinished.countDown();
            verifyCaptures = false;
            return;
        }
        if (editTourStage >= EDIT_CAPTURE_NAMES.length) return;
        int expectedRevision = editTourStage + 1;
        boolean expectedEdited = editTourStage == 1;
        if (activeRevision.label != expectedRevision || activeRevision.edited != expectedEdited
                || revisionChangeRequested) {
            editTourSettledFrames = 0;
            return;
        }
        int settledFrames = smoothLod ? 110 : EDIT_SETTLED_FRAMES;
        if (++editTourSettledFrames < settledFrames) return;
        String name = EDIT_CAPTURE_NAMES[editTourStage];
        screenshots.setFileName(name);
        screenshots.takeScreenshot();
        System.out.println("GPU_HEIGHTFIELD_EDIT_TOUR_CAPTURE name=" + name
                + " revision=" + activeRevision.label + " edited=" + activeRevision.edited
                + " settledFrames=" + settledFrames);
        editTourStage++;
        editTourSettledFrames = 0;
        if (editTourStage == 1) {
            requestedEdit = true;
            revisionChangeRequested = true;
        } else if (editTourStage == 2) {
            requestedEdit = false;
            revisionChangeRequested = true;
        } else {
            verifyCaptures = true;
        }
    }

    private void applyRequestedRevision() {
        if (!revisionChangeRequested || requestedEdit == activeRevision.edited
                || retiredRevisions.size() >= MAX_RETIRED_REVISIONS) return;
        TerrainRevision candidate = null;
        GLFence retirementFence = null;
        boolean candidateGpuWorkStarted = false;
        try {
            candidate = createRevision(requestedEdit, nextRevision);
            candidateGpuWorkStarted = true;
            candidate.dispatch(cam, transitionStep);
            // Force the new one-layer array upload while the old revision is still active.
            renderer.setTexture(0, candidate.heightPages);
            retirementFence = renderer.createFence();
            visibility.addReceiver(candidate.material);

            TerrainRevision old = activeRevision;
            rootNode.attachChild(candidate.geometry);
            rootNode.detachChild(old.geometry);
            visibility.removeReceiver(old.material);
            activeRevision = candidate;
            candidate = null;
            retiredRevisions.addLast(new RetiredRevision(old, retirementFence));
            retirementFence = null;
            nextRevision++;
            if (nextRevision > 16777216) nextRevision = 1;
            requestedEdit = activeRevision.edited;
            revisionChangeRequested = false;
            BoundingBox bound = (BoundingBox) activeRevision.geometry.getModelBound();
            System.out.printf(Locale.ROOT,
                    "GPU_HEIGHTFIELD_REVISION revision=%d edited=%s hillRiseM=%.1f minY=%.3f maxY=%.3f retired=%d\n",
                    activeRevision.label, activeRevision.edited,
                    activeRevision.edited ? 10f : 0f,
                    bound.getCenter().y - bound.getYExtent(),
                    bound.getCenter().y + bound.getYExtent(), retiredRevisions.size());
        } catch (Throwable error) {
            if (candidate != null) visibility.removeReceiver(candidate.material);
            if (candidateGpuWorkStarted && candidate != null) {
                if (retirementFence != null) renderer.deleteFence(retirementFence);
                retirementFence = null;
                try {
                    retirementFence = renderer.createFence();
                } catch (Throwable fenceError) {
                    System.err.println("GPU_HEIGHTFIELD_CANDIDATE_FENCE_UNAVAILABLE revision="
                            + candidate.label + " reason=" + fenceError);
                }
                retiredRevisions.addLast(new RetiredRevision(candidate, retirementFence));
                System.err.println("GPU_HEIGHTFIELD_REVISION_REJECTED active=" + activeRevision.label
                        + " retainedCandidate=" + candidate.label + " fence=" + (retirementFence != null)
                        + " retired=" + retiredRevisions.size() + " reason=" + error);
                candidate = null;
                retirementFence = null;
            }
            if (retirementFence != null) renderer.deleteFence(retirementFence);
            if (candidate != null) candidate.release(renderer);
            revisionChangeRequested = false;
            requestedEdit = activeRevision.edited;
            if (finiteRun) {
                fail(error);
            } else if (!candidateGpuWorkStarted) {
                System.err.println("GPU_HEIGHTFIELD_REVISION_REJECTED active=" + activeRevision.label
                        + " reason=" + error);
            }
        }
    }

    private static final class TerrainRevision {
        private final boolean edited;
        private final int label;
        private final HeightfieldPage page;
        private final HeightfieldGpuSelection selection;
        private final HeightfieldCpuPatchBatch cpuBatch;
        private final HeightfieldGpuTransitions transitions;
        private final TextureArray heightPages;
        private final Geometry geometry;
        private final Material material;
        private boolean released;

        private TerrainRevision(boolean edited, int label, HeightfieldPage page,
                HeightfieldGpuSelection selection, HeightfieldCpuPatchBatch cpuBatch,
                HeightfieldGpuTransitions transitions, TextureArray heightPages,
                Geometry geometry, Material material) {
            this.edited = edited;
            this.label = label;
            this.page = page;
            this.selection = selection;
            this.cpuBatch = cpuBatch;
            this.transitions = transitions;
            this.heightPages = heightPages;
            this.geometry = geometry;
            this.material = material;
        }

        private void dispatch(com.jme3.renderer.Camera camera, float step) {
            if (transitions != null) transitions.dispatch(camera, 1.25, label, 64, step, node -> true);
            else if (selection != null) selection.dispatch(camera, 1.25, label, 64, node -> true);
            else cpuBatch.dispatch(camera, 1.25, label, 64, node -> true);
        }

        private void release(com.jme3.renderer.Renderer renderer) {
            if (released) return;
            if (transitions != null) transitions.close();
            if (selection != null) selection.close();
            if (cpuBatch != null) cpuBatch.close();
            renderer.deleteImage(heightPages.getImage());
            for (VertexBuffer buffer : geometry.getMesh().getBufferList().getArray()) {
                renderer.deleteBuffer(buffer);
            }
            released = true;
        }
    }

    private static final class RetiredRevision {
        private final TerrainRevision revision;
        private final GLFence fence;

        private RetiredRevision(TerrainRevision revision, GLFence fence) {
            this.revision = revision;
            this.fence = fence;
        }
    }

    private void retireSignaledRevisions() {
        java.util.Iterator<RetiredRevision> iterator = retiredRevisions.iterator();
        while (iterator.hasNext()) {
            RetiredRevision retired = iterator.next();
            if (retired.fence != null && renderer.isFenceSignaled(retired.fence)) {
                renderer.deleteFence(retired.fence);
                retired.revision.release(renderer);
                iterator.remove();
                System.out.println("GPU_HEIGHTFIELD_REVISION_RELEASED revision=" + retired.revision.label
                        + " remaining=" + retiredRevisions.size());
            }
        }
    }

    @Override
    public void destroy() {
        if (activeRevision != null) {
            if (visibility != null) visibility.removeReceiver(activeRevision.material);
            activeRevision.release(renderer);
            activeRevision = null;
        }
        while (!retiredRevisions.isEmpty()) {
            RetiredRevision retired = retiredRevisions.removeFirst();
            if (retired.fence != null) renderer.deleteFence(retired.fence);
            retired.revision.release(renderer);
        }
        if (captureTarget != null) {
            viewPort.setOutputFrameBuffer(null);
            renderer.deleteFrameBuffer(captureTarget);
            captureTarget = null;
        }
        super.destroy();
    }
}
