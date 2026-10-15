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
import com.jme3.scene.Geometry;
import com.jme3.scene.instancing.IndirectGeometry;
import com.jme3.system.AppSettings;
import com.jme3.terrain.heightfield.HeightfieldGpuSelection;
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
 * takes those three views as PNG files and exits.
 */
public final class TestGpuHeightfield extends SimpleApplication {
    private static final int CELLS = 256;
    private static final int PATCH_CELLS = 32;
    private static final float SPACING = .5f;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 800;
    private static final String[] CAPTURE_NAMES = {"overview", "valley", "close"};

    private final Path output;
    private final boolean finiteRun;
    private final CountDownLatch capturesFinished = new CountDownLatch(1);
    private volatile Throwable failure;
    private HeightfieldGpuSelection selection;
    private FrameBuffer captureTarget;
    private ScreenshotAppState screenshots;
    private int captureFrame;
    private int nextCapture;
    private int writtenCaptures;
    private boolean verifyCaptures;
    private boolean changeCameraAfterCapture;

    private TestGpuHeightfield(boolean finiteRun, Path output) {
        this.finiteRun = finiteRun;
        this.output = output;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 2) {
            throw new IllegalArgumentException("Usage: TestGpuHeightfield [<renderer> [<output-directory>]]");
        }
        boolean finite = args.length == 2;
        TestGpuHeightfield app = new TestGpuHeightfield(finite, finite ? Paths.get(args[1]) : null);
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length > 0 ? args[0] : AppSettings.ANGLE_GLES3);
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
            System.out.println("GPU_HEIGHTFIELD_CAPTURE_PASS views=3 pageCells=256 spacing=0.5m");
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
            if (!renderer.getCaps().contains(Caps.ComputeShader)
                    || !renderer.getCaps().contains(Caps.UniformBufferObject)) {
                throw new UnsupportedOperationException("GPU heightfield selection requires compute shaders and UBOs");
            }
            HeightfieldGrid.requireSupported(renderer);
            setDisplayFps(false);
            setDisplayStatView(false);
            flyCam.setMoveSpeed(15f);
            flyCam.setDragToRotate(true);
            flyCam.setEnabled(!finiteRun);
            cam.setFrustumPerspective(55f, (float) WIDTH / HEIGHT, .1f, 600f);
            viewPort.setBackgroundColor(new ColorRGBA(.42f, .59f, .76f, 1));

            HeightfieldPage page = createPage();
            HeightfieldHierarchy hierarchy = new HeightfieldHierarchy(page, PATCH_CELLS, 0);
            selection = new HeightfieldGpuSelection(assetManager, renderer, hierarchy);

            TextureArray heightPages = new TextureArray(java.util.Collections.singletonList(page.createImage()));
            heightPages.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            heightPages.setMagFilter(Texture.MagFilter.Nearest);
            heightPages.setWrap(Texture.WrapMode.EdgeClamp);
            HeightfieldGrid grid = new HeightfieldGrid(PATCH_CELLS);
            Geometry terrain = new IndirectGeometry("GPU selected terrain",
                    grid.createLodPatchMesh(hierarchy.getRoot()), selection.getCommand(), 0,
                    hierarchy.getRoot().getBound());
            terrain.setShadowMode(ShadowMode.Off);
            Material material = new Material(assetManager,
                    "Common/MatDefs/Terrain/Heightfield/Heightfield.j3md");
            material.setTexture("HeightPages", heightPages);
            material.setInt("HeightLayer", 0);
            selection.configureMaterial(material);
            material.setFloat("SnowLine", 26f);
            material.setFloat("SnowFade", 4f);
            material.setColor("GrassColor", new ColorRGBA(.16f, .24f, .085f, 1));
            material.setColor("RockColor", new ColorRGBA(.28f, .255f, .22f, 1));
            terrain.setMaterial(material);
            rootNode.attachChild(terrain);

            DirectionalLight sun = new DirectionalLight();
            sun.setDirection(new Vector3f(-.55f, -.8f, -.35f).normalizeLocal());
            sun.setColor(new ColorRGBA(1.55f, 1.42f, 1.25f, 1));
            rootNode.addLight(sun);
            AmbientLight ambient = new AmbientLight();
            ambient.setColor(new ColorRGBA(.27f, .34f, .45f, 1));
            rootNode.addLight(ambient);
            rootNode.attachChild(SkyFactory.createSky(assetManager, TestModernHeightfield.sky(),
                    SkyFactory.EnvMapType.CubeMap));

            FilterPostProcessor post = new FilterPostProcessor(assetManager);
            post.setFrameBufferFormat(Image.Format.RGBA16F);
            post.setFrameBufferDepthFormat(Image.Format.Depth24);
            post.addFilter(new KHRToneMapFilter());
            post.addFilter(new FXAAFilter());
            viewPort.addProcessor(post);

            setCameraPose(0);
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

    private HeightfieldPage createPage() {
        int side = CELLS + 3;
        float[] heights = new float[side * side];
        for (int z = -1; z <= CELLS + 1; z++) {
            for (int x = -1; x <= CELLS + 1; x++) {
                double worldX = x * (double) SPACING;
                double worldZ = z * (double) SPACING;
                heights[(z + 1) * side + x + 1] = ScenicHeightField.height(worldX * 8.0,
                        worldZ * 8.0) / 8f;
            }
        }
        return new HeightfieldPage(CELLS, SPACING, heights);
    }

    private void installControls() {
        inputManager.addMapping("HeightfieldOverview", new KeyTrigger(KeyInput.KEY_1));
        inputManager.addMapping("HeightfieldValley", new KeyTrigger(KeyInput.KEY_2));
        inputManager.addMapping("HeightfieldClose", new KeyTrigger(KeyInput.KEY_3));
        inputManager.addListener(cameraControls, "HeightfieldOverview", "HeightfieldValley",
                "HeightfieldClose");
    }

    private final ActionListener cameraControls = new ActionListener() {
        @Override
        public void onAction(String name, boolean pressed, float tpf) {
            if (!pressed) return;
            if ("HeightfieldOverview".equals(name)) setCameraPose(0);
            else if ("HeightfieldValley".equals(name)) setCameraPose(1);
            else if ("HeightfieldClose".equals(name)) setCameraPose(2);
        }
    };

    private void installHud() {
        com.jme3.font.BitmapText text = new com.jme3.font.BitmapText(guiFont);
        text.setSize(16f);
        text.setText("GPU HEIGHTFIELD  |  1 overview  2 valley  3 close\n"
                + "WASD/QZ move  |  hold left mouse and drag to look\n"
                + "One immutable 128 m page from fixed source samples; no streaming. Shadows off.");
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
        if (selection == null || failure != null) return;
        try {
            selection.dispatch(cam, 1.25, 1, 64, node -> true);
            if (!finiteRun) return;
            if (verifyCaptures) {
                if (writtenCaptures != CAPTURE_NAMES.length) {
                    throw new IOException("Not all screenshots were written during this run");
                }
                for (String name : CAPTURE_NAMES) {
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
            } else if (++captureFrame >= 5) {
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

    @Override
    public void destroy() {
        if (selection != null) {
            selection.close();
            selection = null;
        }
        if (captureTarget != null) {
            viewPort.setOutputFrameBuffer(null);
            renderer.deleteFrameBuffer(captureTarget);
            captureTarget = null;
        }
        super.destroy();
    }
}
