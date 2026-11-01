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
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain.forest;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.shadow.DirectionalLightVisibilityRenderer;
import com.jme3.system.AppSettings;
import com.jme3.util.BufferUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Interactive or finite framebuffer preview of the original conifer mesh/far representations. */
public final class TestConiferImpostor extends SimpleApplication {
    private static final int WIDTH = 960;
    private static final int HEIGHT = 640;
    private static final int FRAMES_PER_VIEW = 36;
    private static final int FINITE_TIMEOUT_SECONDS = 240;
    private static final ColorRGBA SUN_COLOR = new ColorRGBA(3.76991f, 3.39292f, 2.82743f, 1f);
    private static final ColorRGBA AMBIENT_COLOR = new ColorRGBA(.34f, .39f, .31f, 1f);
    private static final Vector3f SUN_DIRECTION = new Vector3f(-.35f, -.82f, -.45f).normalizeLocal();
    private static final String[] SHOTS = {
        "near-front", "near-side", "near-overhead",
        "far-front", "far-side", "far-overhead",
        "blend-front", "blend-overhead", "mesh-small-front", "far-small-front",
        "mesh-small-overhead", "far-small-overhead", "mesh-perspective", "far-perspective",
        "mesh-backlit", "far-backlit"
    };
    private static final Pose[] POSES = {
        new Pose(false, new Vector3f(0, 10, 29), new Vector3f(0, 8.5f, 0), Vector3f.UNIT_Y),
        new Pose(false, new Vector3f(29, 10, 0), new Vector3f(0, 8.5f, 0), Vector3f.UNIT_Y),
        new Pose(false, new Vector3f(0, 31, 0), new Vector3f(0, 8.5f, 0), Vector3f.UNIT_Z),
        new Pose(true, new Vector3f(0, 10, 29), new Vector3f(0, 8.5f, 0), Vector3f.UNIT_Y),
        new Pose(true, new Vector3f(29, 10, 0), new Vector3f(0, 8.5f, 0), Vector3f.UNIT_Y),
        new Pose(true, new Vector3f(0, 31, 0), new Vector3f(0, 8.5f, 0), Vector3f.UNIT_Z),
        new Pose(.5f, 12, new Vector3f(0,10,29), new Vector3f(0,8.5f,0), Vector3f.UNIT_Y, 1),
        new Pose(.5f, 12, new Vector3f(0,31,0), new Vector3f(0,8.5f,0), Vector3f.UNIT_Z, 1),
        new Pose(0, 240, new Vector3f(0,10,29), new Vector3f(0,8.5f,0), Vector3f.UNIT_Y, 1),
        new Pose(1, 240, new Vector3f(0,10,29), new Vector3f(0,8.5f,0), Vector3f.UNIT_Y, 1),
        new Pose(0, 120, new Vector3f(0,31,0), new Vector3f(0,8.5f,0), Vector3f.UNIT_Z, 1),
        new Pose(1, 120, new Vector3f(0,31,0), new Vector3f(0,8.5f,0), Vector3f.UNIT_Z, 1),
        new Pose(0, 0, new Vector3f(240,200,320), new Vector3f(0,9,0), Vector3f.UNIT_Y, 1),
        new Pose(1, 0, new Vector3f(240,200,320), new Vector3f(0,9,0), Vector3f.UNIT_Y, 1),
        new Pose(0, 12, new Vector3f(-18,10,-23), new Vector3f(0,8.5f,0), Vector3f.UNIT_Y, 0),
        new Pose(1, 12, new Vector3f(-18,10,-23), new Vector3f(0,8.5f,0), Vector3f.UNIT_Y, 1)
    };

    private final Path output;
    private final boolean finiteRun;
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile Throwable failure;
    private OriginalConifer.Asset asset;
    private Geometry tree;
    private ConiferImpostorBaker baker;
    private ConiferImpostor farAsset;
    private InstancedConifers farInstances;
    private com.jme3.scene.Node farParent = new com.jme3.scene.Node("far-preview");
    private boolean atlasReady;
    private long bakeStarted;
    private Material material;
    private ScreenshotAppState screenshots;
    private DirectionalLightVisibilityRenderer shadows;
    private int frame;
    private int currentPose = -1;
    private int written;

    public static void main(String[] args) throws Exception {
        LaunchOptions options = parseOptions(args);
        TestConiferImpostor app = new TestConiferImpostor(options.output);
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(options.renderer);
        settings.setWidth(WIDTH);
        settings.setHeight(HEIGHT);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(true);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setDisplayStatView(false);
        app.setDisplayFps(false);
        app.setPauseOnLostFocus(false);
        try {
            app.start();
            if (options.output == null) {
                app.finished.await();
            } else if (!app.finished.await(FINITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                app.fail(new IllegalStateException("conifer preview timed out after "
                        + FINITE_TIMEOUT_SECONDS + " seconds"));
                app.finished.await(10, TimeUnit.SECONDS);
            }
            app.stop(true);
            if (app.failure != null) throw new IllegalStateException("conifer preview failed", app.failure);
            if (options.output != null) {
                if (app.written != SHOTS.length) throw new IllegalStateException("incomplete conifer captures: " + app.written);
                System.out.println("CONIFER_IMPOSTOR_PASS captures=" + app.written
                        + " nearTriangles=" + app.asset.getNearMesh().getTriangleCount()
                        + " midTriangles=" + app.asset.getMidMesh().getTriangleCount());
            }
        } catch (InterruptedException interrupted) {
            app.stop(false);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Throwable error) {
            app.stop(false);
            if (error instanceof Exception) throw (Exception) error;
            if (error instanceof Error) throw (Error) error;
            throw new IllegalStateException(error);
        }
    }

    private TestConiferImpostor(Path output) {
        this.output = output;
        finiteRun = output != null;
    }

    private static LaunchOptions parseOptions(String[] args) {
        String renderer = AppSettings.ANGLE_GLES3;
        boolean rendererSet = false;
        Path output = null;
        for (String arg : args) {
            if (arg.startsWith("--renderer=")) {
                renderer = arg.substring("--renderer=".length());
                rendererSet = true;
            } else if (arg.startsWith("--output=")) {
                output = asOutput(arg.substring("--output=".length()));
            } else if (!rendererSet && isRendererName(arg)) {
                renderer = arg;
                rendererSet = true;
            } else if (!arg.startsWith("--") && output == null) {
                output = asOutput(arg);
            } else {
                throw new IllegalArgumentException("usage: TestConiferImpostor [renderer] [output-dir]"
                        + " or [--renderer=name] [--output=directory]");
            }
        }
        if (renderer.isBlank()) throw new IllegalArgumentException("renderer name cannot be blank");
        return new LaunchOptions(renderer, output);
    }

    private static boolean isRendererName(String value) {
        return value.startsWith("ANGLE_") || value.startsWith("LWJGL-");
    }

    private static Path asOutput(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("output directory cannot be blank");
        try {
            return Paths.get(value).toAbsolutePath();
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("invalid output directory", invalid);
        }
    }

    private static final class LaunchOptions {
        final String renderer;
        final Path output;
        LaunchOptions(String renderer, Path output) { this.renderer = renderer; this.output = output; }
    }

    @Override
    public void simpleInitApp() {
        try {
            viewPort.setBackgroundColor(new ColorRGBA(.43f, .58f, .72f, 1f));
            cam.setFrustumPerspective(42f, (float) WIDTH / HEIGHT, .1f, 120f);
            flyCam.setMoveSpeed(12f);
            flyCam.setDragToRotate(true);

            asset = OriginalConifer.build(20261031L);
            material = OriginalConiferMaterial.create(assetManager, SUN_DIRECTION, SUN_COLOR, AMBIENT_COLOR);
            tree = new Geometry("original-conifer", asset.getNearMesh());
            tree.setMaterial(material);
            tree.setShadowMode(ShadowMode.CastAndReceive);
            rootNode.attachChild(tree);
            rootNode.attachChild(farParent);
            baker = new ConiferImpostorBaker(renderManager, assetManager, asset, material);
            bakeStarted = System.nanoTime();

            DirectionalLight sun = new DirectionalLight();
            sun.setDirection(SUN_DIRECTION);
            sun.setColor(SUN_COLOR);
            rootNode.addLight(sun);
            rootNode.addLight(new AmbientLight(AMBIENT_COLOR));

            shadows = new DirectionalLightVisibilityRenderer(assetManager, 1024, 2);
            shadows.setLight(sun);
            shadows.setShadowZExtend(70f);
            shadows.setShadowZFadeLength(14f);
            shadows.addReceiver(material);
            viewPort.addProcessor(shadows);

            addGround(material);
            if (!finiteRun) {
                inputManager.addMapping("impostor-next-view", new com.jme3.input.controls.KeyTrigger(com.jme3.input.KeyInput.KEY_TAB));
                inputManager.addListener((com.jme3.input.controls.ActionListener) (name, pressed, tpf) -> {
                    if (pressed && atlasReady) setPose((currentPose + 1) % POSES.length);
                }, "impostor-next-view");
                System.out.println("Conifer preview: Tab changes representation/view; WASD/QZ and drag explore; Esc closes.");
            }
            if (finiteRun) startFiniteCapture();
            else setPose(0);
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void addGround(Material receiverMaterial) {
        Geometry ground = new Geometry("preview-ground", groundMesh(28f, -.04f));
        ground.setMaterial(receiverMaterial);
        ground.setShadowMode(ShadowMode.Receive);
        rootNode.attachChild(ground);
    }

    private void startFiniteCapture() {
        try {
            Files.createDirectories(output);
            String folder = output.toString() + File.separator;
            screenshots = new ScreenshotAppState(folder, SHOTS[0]) {
                @Override
                protected void writeImageFile(File file) throws IOException {
                    super.writeImageFile(file);
                    written++;
                    System.out.println("captured=" + file.getAbsolutePath());
                }
            };
            screenshots.setIsNumbered(false);
            stateManager.attach(screenshots);
        } catch (IOException failure) {
            throw new IllegalStateException("could not create capture directory " + output, failure);
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (failure != null) return;
        try {
            if (!atlasReady) {
                if (!baker.update()) return;
                farAsset = new ConiferImpostor(renderer, assetManager, material,
                        baker.getColorFrames(), baker.getNormalDepthFrames());
                farInstances = new InstancedConifers(renderer, asset, material, farParent, 2, farAsset);
                farInstances.update(new float[]{0,0,0,1,1,0,1}, new int[]{2}, 1);
                shadows.addReceiver(farInstances.getMaterial());
                shadows.addReceiver(farInstances.getFarMaterial());
                atlasReady = true;
                System.out.println("CONIFER_IMPOSTOR_BAKE_PASS views=" + ConiferImpostorFrames.COUNT
                        + " logicalBytes=" + baker.getLogicalBytes() + " maxMipCoverageError=" + baker.getMaxMipCoverageError() + " coverageErrorAtLeast16px=" + baker.getMaxMipCoverageError(16) + " metadataBytes=" + farAsset.getMetadataBytes()
                        + " wallMs=" + (System.nanoTime() - bakeStarted) / 1e6);
                setPose(0);
            }
        } catch (Throwable error) { fail(error); return; }
        if (!finiteRun) return;
        if (written == SHOTS.length) {
            stop();
            return;
        }
        int pose = Math.min(frame / FRAMES_PER_VIEW, POSES.length - 1);
        int localFrame = frame % FRAMES_PER_VIEW;
        if (pose != currentPose) setPose(pose);
        if (localFrame == 25 && screenshots != null) {
            screenshots.setFileName(SHOTS[pose]);
            screenshots.takeScreenshot();
        }
        frame++;
        if (frame > SHOTS.length * FRAMES_PER_VIEW + 8 && written != SHOTS.length) {
            fail(new IllegalStateException("only captured " + written + " of " + SHOTS.length + " preview frames"));
        }
    }

    private void setPose(int index) {
        Pose pose = POSES[index];
        currentPose = index;
        boolean farVisible = pose.farWeight > 0;
        tree.setCullHint(farVisible ? com.jme3.scene.Spatial.CullHint.Always : com.jme3.scene.Spatial.CullHint.Inherit);
        tree.setMesh(pose.meshLod == 0 ? asset.getNearMesh() : asset.getMidMesh());
        farParent.setCullHint(farVisible ? com.jme3.scene.Spatial.CullHint.Inherit : com.jme3.scene.Spatial.CullHint.Always);
        if (farInstances != null && farVisible) {
            if (pose.farWeight < 1) {
                farInstances.update(new float[]{0,0,0,1,1,0,1, 0,0,0,1,1,0,1},
                        new int[]{pose.meshLod,2}, new float[]{1-pose.farWeight,pose.farWeight}, 2);
            } else farInstances.update(new float[]{0,0,0,1,1,0,1}, new int[]{2}, 1);
        }
        tree.updateModelBound();
        cam.setParallelProjection(pose.halfHeight > 0);
        if (pose.halfHeight > 0) cam.setFrustum(.1f, 120f, -1.5f*pose.halfHeight,
                1.5f*pose.halfHeight, pose.halfHeight, -pose.halfHeight);
        else cam.setFrustumPerspective(42f, (float) WIDTH/HEIGHT, .1f, 1200f);
        shadows.setShadowZExtend(pose.halfHeight > 0 ? 70 : 900);
        cam.setLocation(pose.location.clone());
        cam.lookAt(pose.target, pose.up);
    }

    @Override
    public void handleError(String message, Throwable error) {
        fail(error == null ? new IllegalStateException(message) : error);
    }

    private void fail(Throwable error) {
        if (failure == null) {
            failure = error;
            error.printStackTrace();
            stop(false);
        }
    }

    @Override
    public void destroy() {
        try {
            if (shadows != null && viewPort != null) {
                viewPort.removeProcessor(shadows);
                shadows = null;
            }
            if (farInstances != null) farInstances.close();
            if (farAsset != null) farAsset.close();
            if (baker != null) baker.close();
        } catch (Throwable cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else failure.addSuppressed(cleanupFailure);
        }
        try {
            super.destroy();
        } catch (Throwable cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else failure.addSuppressed(cleanupFailure);
        } finally {
            finished.countDown();
        }
    }

    private static Mesh groundMesh(float extent, float y) {
        Mesh mesh = new Mesh();
        mesh.setMode(Mesh.Mode.Triangles);
        mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(new float[]{
            -extent, y, -extent, -extent, y, extent, extent, y, extent, extent, y, -extent
        }));
        mesh.setBuffer(VertexBuffer.Type.Normal, 3, BufferUtils.createFloatBuffer(new float[]{
            0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0
        }));
        mesh.setBuffer(VertexBuffer.Type.TexCoord, 3, BufferUtils.createFloatBuffer(new float[]{
            0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0
        }));
        mesh.setBuffer(VertexBuffer.Type.Color, 4, BufferUtils.createFloatBuffer(new float[]{
            .25f, .31f, .23f, 1, .25f, .31f, .23f, 1,
            .25f, .31f, .23f, 1, .25f, .31f, .23f, 1
        }));
        mesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(new int[]{0, 1, 2, 0, 2, 3}));
        mesh.setStatic();
        mesh.updateBound();
        return mesh;
    }

    private static final class Pose {
        final float farWeight;
        final float halfHeight;
        final int meshLod;
        final Vector3f location;
        final Vector3f target;
        final Vector3f up;
        Pose(boolean mid, Vector3f location, Vector3f target, Vector3f up) {
            this(mid ? 1 : 0, 12, location, target, up, 0);
        }
        Pose(float farWeight, float halfHeight, Vector3f location, Vector3f target, Vector3f up, int meshLod) {
            this.farWeight = farWeight; this.halfHeight = halfHeight; this.meshLod = meshLod;
            this.location = location; this.target = target; this.up = up;
        }
    }
}
