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
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
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

import com.jme3.app.SimpleApplication;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.SceneProcessor;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.post.filters.KHRToneMapFilter;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.system.AppSettings;
import com.jme3.terrain.heightfield.HeightfieldGrid;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureArray;
import com.jme3.texture.TextureCubeMap;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.jme3.util.SkyFactory;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Original asset-free scenic scene using reusable GPU-displaced height patches.
 * No arguments: explore with the usual fly camera. Arguments: renderer and
 * output directory produce three fixed captures and exit. This finite resident
 * scene demonstrates geometry/materials; it is not streaming, LOD, hydrology,
 * physically simulated sky or a frame-rate benchmark. Run with -ea.
 */
public final class TestModernHeightfield extends SimpleApplication {
    private static final int CELLS = 128;
    private static final int PAGES = 8;
    private static final float SPACING = 1f;
    private final Path output;
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile Throwable failure;

    private TestModernHeightfield(Path output) {
        this.output = output;
    }

    public static void main(String[] args) throws Exception {
        TestModernHeightfield app = new TestModernHeightfield(args.length > 1 ? Paths.get(args[1]) : null);
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length > 0 ? args[0] : AppSettings.ANGLE_GLES3);
        settings.setResolution(1024, 720);
        settings.setAudioRenderer(null);
        settings.setGraphicsDebug(true);
        settings.setGammaCorrection(true);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (app.output != null) {
            if (!app.completed.await(90, TimeUnit.SECONDS)) {
                app.stop(false);
                throw new IllegalStateException("Scenic terrain capture timed out");
            }
            app.stop(true);
            if (app.failure != null) {
                throw new IllegalStateException("Scenic terrain failed", app.failure);
            }
            System.out.println("MODERN_HEIGHTFIELD_PASS pages=64 spacing=1m sharedVertices=16641"
                    + " sharedTriangles=32768 totalTriangles=2097152 captures=3");
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
        HeightfieldGrid.requireSupported(renderer);
        setDisplayFps(false);
        setDisplayStatView(false);
        flyCam.setMoveSpeed(80);
        flyCam.setDragToRotate(true);
        if (output != null) {
            flyCam.setEnabled(false);
        }
        long started = System.nanoTime();
        HeightfieldGrid grid = new HeightfieldGrid(CELLS);
        List<HeightfieldPage> pages = new ArrayList<>();
        List<Image> images = new ArrayList<>();
        for (int pz = 0; pz < PAGES; pz++) {
            for (int px = 0; px < PAGES; px++) {
                int side = CELLS + 3;
                float[] data = new float[side * side];
                for (int z = -1; z <= CELLS + 1; z++) {
                    for (int x = -1; x <= CELLS + 1; x++) {
                        data[(z + 1) * side + x + 1] = ScenicHeightField.height(
                                (px * CELLS + x) * (double) SPACING,
                                (pz * CELLS + z) * (double) SPACING);
                    }
                }
                HeightfieldPage page = new HeightfieldPage(CELLS, SPACING, data);
                pages.add(page);
                images.add(page.createImage());
            }
        }
        verifySeams(pages);
        TextureArray heights = new TextureArray(images);
        heights.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        heights.setMagFilter(Texture.MagFilter.Nearest);
        heights.setWrap(Texture.WrapMode.EdgeClamp);
        for (int pz = 0; pz < PAGES; pz++) {
            for (int px = 0; px < PAGES; px++) {
                int layer = pz * PAGES + px;
                Geometry patch = new Geometry("height-page-" + layer, grid.createPatchMesh(pages.get(layer)));
                patch.setLocalTranslation(px * CELLS * SPACING, 0, pz * CELLS * SPACING);
                Material material = new Material(assetManager, "Common/MatDefs/Terrain/Heightfield/Heightfield.j3md");
                material.setTexture("HeightPages", heights);
                material.setInt("HeightLayer", layer);
                material.setFloat("SampleSpacing", pages.get(layer).getSampleSpacing());
                material.setFloat("SnowLine", 210);
                material.setFloat("SnowFade", 32);
                material.setColor("GrassColor", new ColorRGBA(.10f, .18f, .055f, 1));
                material.setColor("RockColor", new ColorRGBA(.24f, .21f, .17f, 1));
                patch.setMaterial(material);
                rootNode.attachChild(patch);
            }
        }
        // Three equal directions/intensities let capture mode compare the
        // same radiance in one light batch against three additive batches.
        for (int i = 0; i < 3; i++) {
            DirectionalLight sun = new DirectionalLight();
            sun.setDirection(new Vector3f(-.65f, -.8f, -.45f).normalizeLocal());
            sun.setColor(new ColorRGBA(2.8f / 3, 2.6f / 3, 2.25f / 3, 1));
            rootNode.addLight(sun);
        }
        AmbientLight skyFill = new AmbientLight();
        skyFill.setColor(new ColorRGBA(.11f, .15f, .22f, 1));
        rootNode.addLight(skyFill);
        renderManager.setSinglePassLightBatchSize(4);
        rootNode.attachChild(SkyFactory.createSky(assetManager, sky(), SkyFactory.EnvMapType.CubeMap));
        FilterPostProcessor post = new FilterPostProcessor(assetManager);
        post.setFrameBufferFormat(Image.Format.RGBA16F);
        // GLES's generic Depth allocation is 16-bit even on ES3. Fine relief
        // needs an explicit 24-bit target to avoid equal-depth additive overlap.
        post.setFrameBufferDepthFormat(Image.Format.Depth24);
        post.addFilter(new KHRToneMapFilter());
        FXAAFilter fxaa = new FXAAFilter();
        post.addFilter(fxaa);
        viewPort.addProcessor(post);
        setCameraPose(0);
        System.out.println("SCENIC_PREPARATION_MS=" + (System.nanoTime() - started) / 1000000);
        if (output != null) {
            // Keep post-filter linear color in float storage until display
            // encoding. An 8-bit linear readback bands dark terrain severely.
            FrameBuffer captureTarget = new FrameBuffer(cam.getWidth(), cam.getHeight(), 1);
            captureTarget.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA16F));
            captureTarget.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
            viewPort.setOutputFrameBuffer(captureTarget);
            viewPort.addProcessor(new Capture(fxaa, captureTarget));
        }
    }

    private static void verifySeams(List<HeightfieldPage> pages) {
        for (int pz = 0; pz < PAGES; pz++) {
            for (int px = 0; px < PAGES; px++) {
                HeightfieldPage page = pages.get(pz * PAGES + px);
                for (int i = -1; i <= CELLS + 1; i++) {
                    for (int offset = -1; offset <= 1; offset++) {
                        if (px + 1 < PAGES && page.getHeight(CELLS + offset, i)
                                != pages.get(pz * PAGES + px + 1).getHeight(offset, i)) {
                            throw new IllegalStateException("shared X halo differs");
                        }
                        if (pz + 1 < PAGES && page.getHeight(i, CELLS + offset)
                                != pages.get((pz + 1) * PAGES + px).getHeight(i, offset)) {
                            throw new IllegalStateException("shared Z halo differs");
                        }
                    }
                }
            }
        }
    }

    private void setCameraPose(int pose) {
        cam.setFrustumPerspective(55, 1024f / 720, .25f, 4000);
        if (pose == 0) {
            cam.setLocation(new Vector3f(1080, 640, -130));
            cam.lookAt(new Vector3f(512, 140, 520), Vector3f.UNIT_Y);
        } else if (pose == 1) {
            cam.setLocation(new Vector3f(500, ScenicHeightField.height(500, 90) + 55, 90));
            cam.lookAt(new Vector3f(520, 160, 590), Vector3f.UNIT_Y);
        } else {
            cam.setLocation(new Vector3f(350, ScenicHeightField.height(350, 460) + 35, 460));
            cam.lookAt(new Vector3f(490, ScenicHeightField.height(490, 340) + 2, 340), Vector3f.UNIT_Y);
        }
    }

    // Original artistic directional sky, kept separate from a future transport/LUT system.
    private static TextureCubeMap sky() {
        int side = 64;
        ArrayList<ByteBuffer> faces = new ArrayList<>();
        for (int face = 0; face < 6; face++) {
            ByteBuffer data = BufferUtils.createByteBuffer(side * side * 3);
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    float u = 2f * (x + .5f) / side - 1;
                    float v = 2f * (y + .5f) / side - 1;
                    Vector3f direction;
                    switch (face) {
                        case 0: direction = new Vector3f(1, -v, -u); break;
                        case 1: direction = new Vector3f(-1, -v, u); break;
                        case 2: direction = new Vector3f(u, 1, v); break;
                        case 3: direction = new Vector3f(u, -1, -v); break;
                        case 4: direction = new Vector3f(u, -v, 1); break;
                        default: direction = new Vector3f(-u, -v, -1); break;
                    }
                    direction.normalizeLocal();
                    float t = (float) Math.pow(Math.max(direction.y, 0), .35);
                    data.put((byte) Math.round(255 * (.38f * (1 - t) + .065f * t)));
                    data.put((byte) Math.round(255 * (.52f * (1 - t) + .18f * t)));
                    data.put((byte) Math.round(255 * (.69f * (1 - t) + .39f * t)));
                }
            }
            data.flip();
            faces.add(data);
        }
        return new TextureCubeMap(new Image(Image.Format.RGB8, side, side, 0, faces, ColorSpace.Linear));
    }

    private final class Capture implements SceneProcessor {
        private final FXAAFilter fxaa;
        private final FrameBuffer outputTarget;
        private boolean initialized;
        private int frames;
        private BufferedImage referenceDisplay;
        private FloatBuffer referenceHdr;

        private Capture(FXAAFilter fxaa, FrameBuffer outputTarget) {
            this.fxaa = fxaa;
            this.outputTarget = outputTarget;
        }

        @Override public void initialize(RenderManager rm, ViewPort vp) { initialized = true; }
        @Override public boolean isInitialized() { return initialized; }
        @Override public void reshape(ViewPort vp, int w, int h) { }
        @Override public void preFrame(float tpf) { }
        @Override public void postQueue(RenderQueue queue) { }
        @Override public void cleanup() {
            initialized = false;
            renderer.deleteFrameBuffer(outputTarget);
            referenceDisplay = null;
            referenceHdr = null;
        }
        @Override public void setProfiler(AppProfiler profiler) { }
        @Override public void postFrame(FrameBuffer out) {
            frames++;
            if (frames % 20 != 0 || frames > 120) {
                return;
            }
            try {
                // Read the output this processor owns. The renderer's current
                // framebuffer query can report a main-buffer override even while
                // a different physical target is bound.
                FrameBuffer target = outputTarget;
                BufferedImage image = readDisplay(target);
                if (frames >= 60) {
                    FloatBuffer hdr = readHdr(out, target);
                    if (frames == 60 || frames == 100) {
                        referenceHdr = hdr;
                        referenceDisplay = image;
                        renderManager.setSinglePassLightBatchSize(1);
                    } else {
                        compareHdr(referenceHdr, hdr);
                        int maximum = maximumDisplayDifference(referenceDisplay, image);
                        if (frames == 80) {
                            // Isolate tone mapping from threshold-based FXAA.
                            // The strict HDR check applies to both filter states.
                            System.out.println("SCENIC_FXAA_BATCH_DIAGNOSTIC maximumDisplayByteError=" + maximum);
                            fxaa.setEnabled(false);
                            renderManager.setSinglePassLightBatchSize(4);
                        } else {
                            if (maximum > 2) {
                                throw new IllegalStateException("Light batching changes tone-mapped display: " + maximum);
                            }
                            System.out.println("SCENIC_LIGHT_BATCH_DISPLAY_PASS maximumDisplayByteError=" + maximum);
                            completed.countDown();
                        }
                    }
                }
                if (frames <= 60) {
                    Files.createDirectories(output);
                    String label = frames == 20 ? "overview" : frames == 40 ? "valley" : "close";
                    if (!ImageIO.write(image, "png", output.resolve(label + ".png").toFile())) {
                        throw new IllegalStateException("PNG writer unavailable");
                    }
                    System.out.println("SCENIC_CAPTURE " + label);
                    if (frames < 60) {
                        setCameraPose(frames == 20 ? 1 : 2);
                    }
                }
            } catch (Throwable error) {
                failure = error;
                completed.countDown();
            }
        }

        private BufferedImage readDisplay(FrameBuffer target) {
            int width = cam.getWidth(), height = cam.getHeight();
            // The viewport's `out` names the pre-tone-map scene buffer. FPP
            // restores our linear RGBA16F output before this processor. Encode
            // the post-filter float values to display exactly once.
            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 16)
                    .order(ByteOrder.nativeOrder());
            renderer.readFrameBufferWithFormat(target, pixels, Image.Format.RGBA32F);
            FloatBuffer linear = pixels.asFloatBuffer();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = ((height - 1 - y) * width + x) * 4;
                    int red = display(linear.get(i));
                    int green = display(linear.get(i + 1));
                    int blue = display(linear.get(i + 2));
                    image.setRGB(x, y, (red << 16) | (green << 8) | blue);
                }
            }
            return image;
        }

        private FloatBuffer readHdr(FrameBuffer scene, FrameBuffer outputTarget) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 16)
                    .order(ByteOrder.nativeOrder());
            try {
                renderer.readFrameBufferWithFormat(scene, pixels, Image.Format.RGBA32F);
            } finally {
                renderer.setFrameBuffer(outputTarget);
            }
            return pixels.asFloatBuffer();
        }

        private void compareHdr(FloatBuffer expected, FloatBuffer actual) {
            float maximum = 0;
            for (int i = 0; i < actual.capacity(); i++) {
                if (i % 4 == 3) {
                    continue; // Additive pass alpha does not represent radiance.
                }
                float a = expected.get(i), b = actual.get(i);
                float difference = Math.abs(a - b);
                // RGBA16F accumulation rounds once per additive pass. A small
                // absolute/relative envelope covers that, not repeated fill.
                if (!Float.isFinite(a) || !Float.isFinite(b)
                        || difference > .002f * Math.max(1f, Math.abs(a))) {
                    throw new IllegalStateException("Light batching changes linear HDR at channel "
                            + i + ": " + a + " vs " + b);
                }
                maximum = Math.max(maximum, difference);
            }
            System.out.println("SCENIC_LIGHT_BATCH_HDR_PASS maximumLinearError=" + maximum);
        }

        private int maximumDisplayDifference(BufferedImage expected, BufferedImage actual) {
            int maximum = 0;
            for (int y = 0; y < actual.getHeight(); y++) {
                for (int x = 0; x < actual.getWidth(); x++) {
                    int a = expected.getRGB(x, y);
                    int b = actual.getRGB(x, y);
                    for (int shift = 0; shift <= 16; shift += 8) {
                        int difference = Math.abs(((a >> shift) & 255) - ((b >> shift) & 255));
                        maximum = Math.max(maximum, difference);
                    }
                }
            }
            return maximum;
        }

        private int display(float linear) {
            if (!Float.isFinite(linear)) {
                throw new IllegalStateException("Non-finite post-filter output");
            }
            linear = Math.max(0f, Math.min(1f, linear));
            float encoded = linear <= .0031308f ? linear * 12.92f
                    : 1.055f * (float) Math.pow(linear, 1.0 / 2.4) - .055f;
            return Math.min(255, Math.max(0, Math.round(encoded * 255)));
        }
    }
}
