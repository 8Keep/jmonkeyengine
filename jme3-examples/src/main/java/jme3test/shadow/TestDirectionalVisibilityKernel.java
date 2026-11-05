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
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS 'AS IS'
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
package jme3test.shadow;

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.Texture.ShadowCompareMode;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** GPU phase-parity probe for the directional 5x5 PCF reduction. */
public final class TestDirectionalVisibilityKernel extends SimpleApplication {
    private static final int WIDTH = 256, HEIGHT = 64, MAP_SIZE = 8;
    private static final float RECEIVER_DEPTH = 0.5f;
    private static final float HARDWARE_TOLERANCE = 3e-3f;
    private static final float SOFTWARE_TOLERANCE = 2e-5f;

    private final Path output;
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile Throwable failure;
    private Texture2D hardwareDepth, softwareDepth;
    private ByteBuffer hardwareSource, softwareSource, readback;
    private FrameBuffer target;
    private Geometry quad;
    private Material material;
    private float[] hardwarePixels, softwarePixels;
    private final float[] maximumErrors = new float[2];
    private int phase;
    private com.jme3.renderer.opengl.GLFence finalUse;
    private int drainFrames;

    private TestDirectionalVisibilityKernel(Path output) { this.output = output; }

    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions || args.length != 2) {
            throw new IllegalArgumentException("Use -ea, renderer, and output directory");
        }
        TestDirectionalVisibilityKernel app = new TestDirectionalVisibilityKernel(Paths.get(args[1]));
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args[0]);
        settings.setResolution(WIDTH, HEIGHT);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(false);
        settings.setGraphicsDebug(true);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        try {
            if (!app.finished.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("directional PCF GPU probe timed out", app.failure);
            }
            if (app.failure != null) {
                throw new IllegalStateException("directional PCF GPU probe failed", app.failure);
            }
        } finally {
            app.stop(true);
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

    @Override public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            setDisplayFps(false);
            setDisplayStatView(false);
            if (!renderer.getCaps().contains(com.jme3.renderer.Caps.GLSL150)
                    && !renderer.getCaps().contains(com.jme3.renderer.Caps.OpenGLES30)) {
                throw new UnsupportedOperationException("probe requires GLSL 150 or OpenGL ES 3.0");
            }
            if (!renderer.getCaps().contains(com.jme3.renderer.Caps.FloatDepthBuffer)) {
                throw new UnsupportedOperationException("probe requires floating-point depth textures");
            }
            if (!renderer.getCaps().contains(com.jme3.renderer.Caps.TextureShadowCompare)) {
                throw new UnsupportedOperationException("probe requires hardware shadow comparisons");
            }

            hardwareDepth = makeDepthTexture(true);
            softwareDepth = makeDepthTexture(false);
            target = new FrameBuffer(WIDTH, HEIGHT, 1);
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
            viewPort.setOutputFrameBuffer(target);
            viewPort.setBackgroundColor(ColorRGBA.Black);

            material = new Material(assetManager, "Common/MatDefs/DirectionalVisibilityKernel.j3md");
            material.setBoolean("ProbeEnabled", true);
            material.setBoolean("HardwareShadows", true);
            material.setFloat("DirectionalShadowMapSize", MAP_SIZE);
            material.setVector2("ProbeOutputSize", new Vector2f(WIDTH, HEIGHT));
            material.setTexture("DirectionalShadowMap0", hardwareDepth);
            material.getAdditionalRenderState().setDepthTest(false);
            material.getAdditionalRenderState().setDepthWrite(false);
            material.getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Off);

            cam.setParallelProjection(true);
            cam.setFrustum(.1f, 10f, -1f, 1f, 1f, -1f);
            cam.setLocation(new com.jme3.math.Vector3f(0f, 0f, 2f));
            cam.lookAt(com.jme3.math.Vector3f.ZERO, com.jme3.math.Vector3f.UNIT_Y);
            cam.update();
            quad = new Geometry("directional-pcf-kernel-probe", new Quad(2f, 2f));
            quad.setLocalTranslation(-1f, -1f, 0f);
            quad.setQueueBucket(RenderQueue.Bucket.Opaque);
            quad.setMaterial(material);
            rootNode.attachChild(quad);

            readback = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 16).order(ByteOrder.nativeOrder());
            viewPort.addProcessor(new Capture());
        } catch (Throwable error) {
            fail(error);
        }
    }

    @Override public void simpleUpdate(float tpf) {
        if (finalUse == null || failure != null) return;
        if (renderer.isFenceSignaled(finalUse)) {
            renderer.deleteFence(finalUse);
            finalUse = null;
            System.out.println("DIRECTIONAL_PCF_CLEANUP_DRAIN_PASS");
            finished.countDown();
        } else if (++drainFrames > 300) {
            fail(new IllegalStateException("PCF probe GPU use did not drain"));
        }
    }

    private Texture2D makeDepthTexture(boolean hardware) {
        ByteBuffer data = BufferUtils.createByteBuffer(MAP_SIZE * MAP_SIZE * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        FloatBuffer values = data.asFloatBuffer();
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                boolean lit = ((x * 3 + y * 5) % 7 < 3) ^ ((x + 2 * y) % 5 == 0);
                values.put(lit ? .85f : .15f);
            }
        }
        data.position(0);
        Image image = new Image(Image.Format.Depth32F, MAP_SIZE, MAP_SIZE, data);
        Texture2D texture = new Texture2D(image);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
        if (hardware) {
            hardwareSource = data;
            texture.setShadowCompareMode(ShadowCompareMode.LessOrEqual);
            texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            texture.setMagFilter(Texture.MagFilter.Bilinear);
        } else {
            softwareSource = data;
            texture.setShadowCompareMode(ShadowCompareMode.Off);
            texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            texture.setMagFilter(Texture.MagFilter.Nearest);
        }
        return texture;
    }

    private final class Capture implements com.jme3.post.SceneProcessor {
        private boolean initialized;
        @Override public void initialize(RenderManager rm, ViewPort vp) { initialized = true; }
        @Override public boolean isInitialized() { return initialized; }
        @Override public void reshape(ViewPort vp, int w, int h) { }
        @Override public void preFrame(float tpf) { }
        @Override public void postQueue(RenderQueue queue) { }
        @Override public void setProfiler(com.jme3.profile.AppProfiler profiler) { }
        @Override public void postFrame(FrameBuffer out) {
            if (failure != null || phase >= 2) return;
            try {
                readback.clear();
                FrameBuffer previous = renderer.getCurrentFrameBuffer();
                try {
                    renderer.readFrameBufferWithFormat(target, readback, Image.Format.RGBA32F);
                } finally {
                    renderer.setFrameBuffer(previous);
                }
                readback.rewind();
                FloatBuffer floats = readback.asFloatBuffer();
                float[] pixels = new float[WIDTH * HEIGHT];
                float tolerance = phase == 0 ? HARDWARE_TOLERANCE : SOFTWARE_TOLERANCE;
                float maximumError = 0f, minimum = Float.POSITIVE_INFINITY, maximum = Float.NEGATIVE_INFINITY;
                int fractional = 0;
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        int pixel = y * WIDTH + x;
                        float actual = floats.get(pixel * 4);
                        float uvx = (x + .5f) / WIDTH;
                        float uvy = (y + .5f) / HEIGHT;
                        float expected = directTent(uvx, uvy);
                        float error = Math.abs(actual - expected);
                        if (!Float.isFinite(actual) || error > tolerance) {
                            throw new IllegalStateException((phase == 0 ? "hardware" : "software")
                                    + " PCF mismatch at (" + x + "," + y + ") uv=(" + uvx + "," + uvy
                                    + ") expected=" + expected + " actual=" + actual + " error=" + error
                                    + " tolerance=" + tolerance);
                        }
                        pixels[pixel] = actual;
                        maximumError = Math.max(maximumError, error);
                        minimum = Math.min(minimum, actual);
                        maximum = Math.max(maximum, actual);
                        if (expected > 0f && expected < 1f) fractional++;
                    }
                }
                if (fractional < WIDTH || maximum - minimum < .2f) {
                    throw new IllegalStateException("probe produced insufficient nonconstant samples: fractional="
                            + fractional + " range=" + (maximum - minimum));
                }
                maximumErrors[phase] = maximumError;
                if (phase == 0) {
                    hardwarePixels = pixels;
                    material.setBoolean("HardwareShadows", false);
                    material.setTexture("DirectionalShadowMap0", softwareDepth);
                    phase = 1;
                } else {
                    softwarePixels = pixels;
                    writePreview(output.resolve("directional-pcf-hardware.png"), hardwarePixels);
                    writePreview(output.resolve("directional-pcf-software.png"), softwarePixels);
                    System.out.println("DIRECTIONAL_PCF_PHASE_PARITY_PASS renderer=" + renderer
                            + " pixels=" + (WIDTH * HEIGHT) + " fractional=" + fractional
                            + " hardwareMaxError=" + maximumErrors[0] + "/" + HARDWARE_TOLERANCE
                            + " softwareMaxError=" + maximumErrors[1] + "/" + SOFTWARE_TOLERANCE
                            + " range=" + minimum + ".." + maximum);
                    phase = 2;
                    quad.removeFromParent();
                    finalUse = renderer.createFence();
                }
            } catch (Throwable error) {
                fail(error);
            }
        }
        @Override public void cleanup() {
            if (finalUse != null) { renderer.deleteFence(finalUse); finalUse = null; }
            if (quad != null) {
                rootNode.detachChild(quad);
                quad = null;
            }
            if (viewPort != null) viewPort.setOutputFrameBuffer(null);
            if (target != null) {
                renderer.deleteFrameBuffer(target);
                target = null;
            }
            deleteTexture(hardwareDepth, hardwareSource);
            deleteTexture(softwareDepth, softwareSource);
            if (readback != null) BufferUtils.destroyDirectBuffer(readback);
            initialized = false;
        }
    }

    private void deleteTexture(Texture2D texture, ByteBuffer source) {
        if (texture == null) return;
        renderer.deleteImage(texture.getImage());
        if (source != null) BufferUtils.destroyDirectBuffer(source);
        if (texture == hardwareDepth) hardwareDepth = null;
        if (texture == softwareDepth) softwareDepth = null;
    }

    private float directTent(float u, float v) {
        float sum = 0f;
        for (int dy = -2; dy <= 2; dy++) {
            float weightY = 3f - Math.abs(dy);
            for (int dx = -2; dx <= 2; dx++) {
                float weight = weightY * (3f - Math.abs(dx));
                float sampleU = u + dx / (float) MAP_SIZE;
                float sampleV = v + dy / (float) MAP_SIZE;
                sum += weight * bilinearComparison(sampleU, sampleV);
            }
        }
        return sum / 81f;
    }

    private float bilinearComparison(float u, float v) {
        float px = u * MAP_SIZE - .5f;
        float py = v * MAP_SIZE - .5f;
        int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py);
        float fx = px - x0, fy = py - y0;
        float lower = mix(comparisonAt(x0, y0), comparisonAt(x0 + 1, y0), fx);
        float upper = mix(comparisonAt(x0, y0 + 1), comparisonAt(x0 + 1, y0 + 1), fx);
        return mix(lower, upper, fy);
    }

    private float comparisonAt(int x, int y) {
        if (x < 0 || x >= MAP_SIZE || y < 0 || y >= MAP_SIZE) return 1f;
        boolean lit = ((x * 3 + y * 5) % 7 < 3) ^ ((x + 2 * y) % 5 == 0);
        return RECEIVER_DEPTH <= (lit ? .85f : .15f) ? 1f : 0f;
    }

    private static float mix(float a, float b, float t) { return a + (b - a) * t; }

    private void writePreview(Path path, float[] values) throws Exception {
        Files.createDirectories(output);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int gray = Math.max(0, Math.min(255, Math.round(values[y * WIDTH + x] * 255f)));
            image.setRGB(x, HEIGHT - 1 - y, (gray << 16) | (gray << 8) | gray);
        }
        ImageIO.write(image, "png", path.toFile());
    }

}
