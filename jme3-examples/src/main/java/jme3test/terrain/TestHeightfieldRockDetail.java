/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * 'AS IS' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.post.filters.KHRToneMapFilter;
import com.jme3.math.Vector2f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.system.AppSettings;
import com.jme3.terrain.heightfield.HeightfieldGrid;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldSurfacePage;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureArray;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Finite visual comparison for image-backed height modulation of rock normals. */
public final class TestHeightfieldRockDetail extends SimpleApplication {
    private static final int WIDTH = 960;
    private static final int HEIGHT = 640;
    private static final int CELLS = 128;
    private static final double SPACING = .25;
    private static final int FRAMES_PER_SHOT = 36;
    private static final int FINITE_TIMEOUT_SECONDS = 120;
    private static final float DEFAULT_HEIGHT_METRES = RegionalRockDetail.DEFAULT_HEIGHT_METRES;
    private static final ColorRGBA SUN_COLOR = new ColorRGBA(2.8f, 2.55f, 2.25f, 1f);
    private static final ColorRGBA SKY_COLOR = new ColorRGBA(.16f, .19f, .23f, 1f);
    private static final Shot[] SHOTS = {
        new Shot("close-front", new Vector3f(-8, 2, 0), new Vector3f(0, 0, 0)),
        new Shot("grazing", new Vector3f(0, 8, 8), new Vector3f(0, 0, 0)),
        new Shot("minified", new Vector3f(-100, 320, 100), new Vector3f(0, 0, 0))
    };

    private final Path output;
    private final boolean finiteRun;
    private final boolean heightDisabled;
    private final boolean cached;
    private final float heightMetres;
    private final String rendererName;
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile Throwable failure;
    private ScreenshotAppState screenshots;
    private FilterPostProcessor post;
    private TextureArray cachedAlbedo, cachedNormal, cachedSemantic;
    private Texture2D cachedPageTable;
    private int frame;
    private int written;

    public static void main(String[] args) throws Exception {
        Options options = parseOptions(args);
        TestHeightfieldRockDetail app = new TestHeightfieldRockDetail(options);
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(options.renderer);
        settings.setWidth(WIDTH);
        settings.setHeight(HEIGHT);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(true);
        settings.setGraphicsDebug(true);
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
                app.fail(new IllegalStateException("rock detail preview timed out after "
                        + FINITE_TIMEOUT_SECONDS + " seconds"));
                app.finished.await(10, TimeUnit.SECONDS);
            }
            app.stop(true);
            if (app.failure != null) throw new IllegalStateException("rock detail preview failed", app.failure);
            if (options.output != null) {
                if (app.written != SHOTS.length) {
                    throw new IllegalStateException("incomplete rock detail captures: " + app.written);
                }
                System.out.println("HEIGHTFIELD_ROCK_DETAIL_PASS captures=" + app.written);
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

    private TestHeightfieldRockDetail(Options options) {
        output = options.output;
        finiteRun = output != null;
        heightDisabled = options.heightDisabled;
        cached = options.cached;
        heightMetres = options.heightMetres;
        rendererName = options.renderer;
    }

    @Override
    public void simpleInitApp() {
        try {
            viewPort.setBackgroundColor(new ColorRGBA(.40f, .49f, .56f, 1f));
            cam.setFrustumPerspective(42f, (float) WIDTH / HEIGHT, .1f, 1000f);
            flyCam.setMoveSpeed(12f);
            flyCam.setDragToRotate(true);
            flyCam.setEnabled(!finiteRun);

            HeightfieldPage page = createRockRamp();
            TextureArray heights = new TextureArray(Collections.singletonList(page.createImage()));
            heights.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            heights.setMagFilter(Texture.MagFilter.Nearest);
            heights.setWrap(Texture.WrapMode.EdgeClamp);

            RegionalRockDetail detail = new RegionalRockDetail(assetManager);
            Material material = new Material(assetManager,
                    "Common/MatDefs/Terrain/Heightfield/Heightfield.j3md");
            material.setTexture("HeightPages", heights);
            material.setInt("HeightLayer", 0);
            material.setFloat("SampleSpacing", (float) SPACING);
            material.setFloat("SnowLine", 10000f);
            material.setFloat("SnowFade", 10f);
            material.setFloat("Roughness", .82f);
            material.setColor("GrassColor", new ColorRGBA(.11f, .16f, .10f, 1f));
            material.setColor("RockColor", new ColorRGBA(.43f, .37f, .30f, 1f));
            material.setColor("SnowColor", new ColorRGBA(.82f, .86f, .9f, 1f));
            detail.bind(material);
            if (heightDisabled) detail.unbindHeight(material);
            else detail.bindHeight(material, heightMetres);
            if (cached) bindCachedSurface(material);

            Geometry ramp = new Geometry("bedrock-height-detail-ramp",
                    new HeightfieldGrid(CELLS).createPatchMesh(page));
            ramp.setLocalTranslation(-CELLS * (float) SPACING * .5f, 0,
                    -CELLS * (float) SPACING * .5f);
            ramp.setMaterial(material);
            ramp.setQueueBucket(RenderQueue.Bucket.Opaque);
            rootNode.attachChild(ramp);

            DirectionalLight sun = new DirectionalLight();
            sun.setDirection(new Vector3f(.8f, -.6f, 0).normalizeLocal());
            sun.setColor(SUN_COLOR);
            rootNode.addLight(sun);
            AmbientLight sky = new AmbientLight();
            sky.setColor(SKY_COLOR);
            rootNode.addLight(sky);

            post = new FilterPostProcessor(assetManager);
            post.setFrameBufferFormat(Image.Format.RGBA16F);
            post.setFrameBufferDepthFormat(Image.Format.Depth24);
            post.addFilter(new KHRToneMapFilter());
            post.addFilter(new FXAAFilter());
            viewPort.addProcessor(post);
            System.out.printf("ROCK_DETAIL_IMAGE renderer=%s disabled=%s amplitudeMetres=%.3f "
                            + "cached=%s baseBytes=%d mipBytes=%d mipLevels=%d bitsPerPixel=%d albedoBaseBytes=%d albedoRgb8MipEstimate=%d%n",
                    rendererName, heightDisabled, heightDisabled ? 0 : heightMetres, cached,
                    detail.getHeightBaseImageBytes(), detail.getHeightMipChainBytes(),
                    detail.getHeightMipLevels(), detail.getHeightBitsPerPixel(),
                    detail.getBaseImageBytes(), detail.getEstimatedMipChainRgb8Bytes());
            if (finiteRun) startFiniteCapture();
            else setShot(0);
        } catch (Throwable error) {
            fail(error);
        }
    }

    private static HeightfieldPage createRockRamp() {
        int side = CELLS + 3;
        float[] heights = new float[side * side];
        for (int z = -1; z <= CELLS + 1; z++) {
            for (int x = -1; x <= CELLS + 1; x++) {
                double worldX = x * SPACING - CELLS * SPACING * .5;
                double worldZ = z * SPACING - CELLS * SPACING * .5;
                double broad = .65 * Math.sin(worldX * .19) * Math.cos(worldZ * .13)
                        + .28 * Math.sin((worldX + worldZ) * .11);
                heights[(z + 1) * side + x + 1] = (float) (4.0 * worldX + broad);
            }
        }
        return new HeightfieldPage(CELLS, (float) SPACING, heights);
    }

    private void bindCachedSurface(Material material) {
        HeightfieldSurfacePage.Source source = (x, z, sample) -> {
            float nx = .07f * (float) Math.sin(x * .11);
            float nz = .05f * (float) Math.cos(z * .09);
            float inverse = 1f / (float) Math.sqrt(nx * nx + 1f + nz * nz);
            sample.albedoR = .43f;
            sample.albedoG = .37f;
            sample.albedoB = .30f;
            sample.normalX = nx * inverse;
            sample.normalY = inverse;
            sample.normalZ = nz * inverse;
            sample.roughness = .82f;
            sample.semanticId = 1;
            sample.detailWeight = Math.max(0f, Math.min(1f, (x + .5f) / 64f));
        };
        HeightfieldSurfacePage page = new HeightfieldSurfacePage(
                new HeightfieldSurfacePage.Revision(0, 0, 0), 0, 0, 64, 1, 1, source);
        cachedAlbedo = new TextureArray(Collections.singletonList(page.createAlbedoImage(0)));
        cachedNormal = new TextureArray(Collections.singletonList(page.createNormalImage(0)));
        cachedSemantic = new TextureArray(Collections.singletonList(page.createSemanticImage(0)));
        bilinear(cachedAlbedo);
        bilinear(cachedNormal);
        nearest(cachedSemantic);

        ByteBuffer tableData = BufferUtils.createByteBuffer(4 * Float.BYTES).order(ByteOrder.nativeOrder());
        tableData.putFloat(0f).putFloat(0f).putFloat(1f).putFloat(0f).flip();
        cachedPageTable = new Texture2D(new Image(Image.Format.RGBA32F, 1, 1, tableData, ColorSpace.Linear));
        nearest(cachedPageTable);
        HeightfieldSurfaceBindings.bind(material, cachedAlbedo, cachedNormal, cachedSemantic,
                cachedPageTable, 68, HeightfieldSurfacePage.GUTTER, 1,
                new Vector2f(-16f, -16f), 32f, .5f);
    }

    private static void bilinear(Texture texture) {
        texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        texture.setMagFilter(Texture.MagFilter.Bilinear);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
    }

    private static void nearest(Texture texture) {
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
    }

    private void startFiniteCapture() {
        try {
            Files.createDirectories(output);
            screenshots = new ScreenshotAppState(output.toString() + File.separator, SHOTS[0].name) {
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
        if (!finiteRun || failure != null) return;
        if (written == SHOTS.length) {
            stop();
            return;
        }
        int index = Math.min(frame / FRAMES_PER_SHOT, SHOTS.length - 1);
        int localFrame = frame % FRAMES_PER_SHOT;
        if (localFrame == 0) setShot(index);
        if (localFrame == 25) {
            screenshots.setFileName(SHOTS[index].name);
            screenshots.takeScreenshot();
        }
        frame++;
        if (frame > SHOTS.length * FRAMES_PER_SHOT + 8 && written != SHOTS.length) {
            fail(new IllegalStateException("only captured " + written + " of " + SHOTS.length + " rock detail views"));
        }
    }

    private void setShot(int index) {
        Shot shot = SHOTS[index];
        cam.setLocation(shot.location.clone());
        cam.lookAt(shot.target, Vector3f.UNIT_Y);
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
            if (post != null && viewPort != null) {
                viewPort.removeProcessor(post);
                post = null;
            }
        } catch (Throwable cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else failure.addSuppressed(cleanupFailure);
        }
        releaseOwned(cachedAlbedo);
        releaseOwned(cachedNormal);
        releaseOwned(cachedSemantic);
        releaseOwned(cachedPageTable);
        cachedAlbedo = null;
        cachedNormal = null;
        cachedSemantic = null;
        cachedPageTable = null;
        try {
            super.destroy();
        } catch (Throwable cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else failure.addSuppressed(cleanupFailure);
        } finally {
            finished.countDown();
        }
    }

    private void release(Texture texture) {
        if (texture == null || texture.getImage() == null || renderer == null) return;
        Image image = texture.getImage();
        try {
            renderer.deleteImage(image);
        } finally {
            if (image.getData() != null) {
                for (ByteBuffer data : image.getData()) BufferUtils.destroyDirectBuffer(data);
            }
        }
    }

    private void releaseOwned(Texture texture) {
        try {
            release(texture);
        } catch (Throwable cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else failure.addSuppressed(cleanupFailure);
        }
    }

    private static Options parseOptions(String[] args) {
        String renderer = AppSettings.ANGLE_GLES3;
        Path output = null;
        boolean rendererSet = false, heightDisabled = false, amplitudeSet = false;
        boolean cached = false;
        float heightMetres = DEFAULT_HEIGHT_METRES;
        for (String arg : args) {
            if (arg.startsWith("--renderer=")) {
                renderer = arg.substring("--renderer=".length());
                rendererSet = true;
            } else if (arg.startsWith("--output=")) {
                output = outputPath(arg.substring("--output=".length()));
            } else if (arg.equals("--height-disabled")) {
                heightDisabled = true;
            } else if (arg.equals("--cached")) {
                cached = true;
            } else if (arg.startsWith("--height-metres=")) {
                try {
                    heightMetres = Float.parseFloat(arg.substring("--height-metres=".length()));
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("height amplitude must be a finite non-negative number", invalid);
                }
                amplitudeSet = true;
            } else if (!rendererSet && isRendererName(arg)) {
                renderer = arg;
                rendererSet = true;
            } else if (!arg.startsWith("--") && output == null) {
                output = outputPath(arg);
            } else {
                throw usage();
            }
        }
        if (renderer.isBlank()) throw new IllegalArgumentException("renderer name cannot be blank");
        if (!Float.isFinite(heightMetres) || heightMetres < 0) {
            throw new IllegalArgumentException("height amplitude must be finite and non-negative");
        }
        if (heightDisabled && amplitudeSet) throw new IllegalArgumentException("choose --height-disabled or --height-metres, not both");
        return new Options(renderer, output, heightDisabled, heightMetres, cached);
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("usage: TestHeightfieldRockDetail [renderer] [output-dir] "
                + "[--height-metres=value | --height-disabled] [--cached]");
    }

    private static boolean isRendererName(String value) {
        return value.startsWith("ANGLE_") || value.startsWith("LWJGL-");
    }

    private static Path outputPath(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("output directory cannot be blank");
        try {
            return Paths.get(value).toAbsolutePath();
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("invalid output directory", invalid);
        }
    }

    private static final class Shot {
        final String name;
        final Vector3f location;
        final Vector3f target;
        Shot(String name, Vector3f location, Vector3f target) {
            this.name = name;
            this.location = location;
            this.target = target;
        }
    }

    private static final class Options {
        final String renderer;
        final Path output;
        final boolean heightDisabled;
        final float heightMetres;
        final boolean cached;
        Options(String renderer, Path output, boolean heightDisabled, float heightMetres, boolean cached) {
            this.renderer = renderer;
            this.output = output;
            this.heightDisabled = heightDisabled;
            this.heightMetres = heightMetres;
            this.cached = cached;
        }
    }
}
