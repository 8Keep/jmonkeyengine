/*
 * Copyright (c) 2009-2026 jMonkeyEngine
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
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.shadow.CompareMode;
import com.jme3.shadow.DirectionalLightVisibilityRenderer;
import com.jme3.system.AppSettings;
import com.jme3.terrain.heightfield.HeightfieldGrid;
import com.jme3.terrain.heightfield.HeightfieldPage;
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
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Finite direct-only visibility control using original flat terrain and one
 * ordinary PBR block. Linear HDR sum checks preserve sky fill when the sun is
 * occluded. This small control is not the outdoor scenic or morph/cascade gate.
 */
public final class TestDirectionalVisibility extends SimpleApplication {
    private static final String[] PHASE_LABELS = {
        "sun-sky-shadowed", "sun-only-shadowed", "sky-only", "sun-sky-unoccluded",
        "all-lights-shadowed-no-emission", "extras-sky-shadowed", "extras-only-shadowed",
        "extras-only-unoccluded", "emissive-only", "all-lights-shadowed-with-emission",
        "all-lights-unoccluded-no-emission", "all-lights-unoccluded-with-emission"
    };
    private static final int FRAMES_PER_PHASE = 5;
    private static final float EMISSIVE_INTENSITY = 0.5f;
    private static final ColorRGBA EMISSIVE_COLOR = new ColorRGBA(.4f, .2f, .1f, 1f);

    private final Path output;
    private final boolean softwareCompare;
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile Throwable failure;
    private final DirectionalLight sun = new DirectionalLight();
    private final DirectionalLight extraA = new DirectionalLight();
    private final DirectionalLight extraB = new DirectionalLight();
    private final AmbientLight sky = new AmbientLight();
    private DirectionalLightVisibilityRenderer visibility;
    private Geometry block;
    private Geometry emissiveReceiver;
    private Material blockMaterial;
    private Material emissiveReceiverMaterial;
    private FrameBuffer target;
    private ByteBuffer pixels;
    private TextureArray heights;
    private final float[][] captures = new float[PHASE_LABELS.length][];
    private int blockPixelX, blockPixelY, receiverPixelX, receiverPixelY;
    private int frame;

    private TestDirectionalVisibility(Path output, boolean softwareCompare) {
        this.output = output;
        this.softwareCompare = softwareCompare;
    }
    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions || args.length < 2 || args.length > 3
                || (args.length == 3 && !"software".equalsIgnoreCase(args[2]))) {
            throw new IllegalArgumentException("Use -ea, renderer, output directory, and optional 'software'");
        }
        boolean softwareCompare = args.length == 3;
        TestDirectionalVisibility app = new TestDirectionalVisibility(Paths.get(args[1]), softwareCompare);
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args[0]);
        settings.setResolution(640, 480);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(true);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (!app.finished.await(60, TimeUnit.SECONDS)) {
            app.stop(false);
            throw new IllegalStateException("Visibility control timeout", app.failure);
        }
        app.stop(true);
        if (app.failure != null) throw new IllegalStateException("Visibility control failed", app.failure);
    }
    @Override public void handleError(String message, Throwable error) {
        fail(error == null ? new IllegalStateException(message) : error);
    }
    private void fail(Throwable error) { failure = error; error.printStackTrace(); finished.countDown(); }

    @Override public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            HeightfieldGrid.requireSupported(renderer);
            HeightfieldPage page = new HeightfieldPage(128, .5f, new float[131 * 131]);
            heights = new TextureArray(Collections.singletonList(page.createImage()));
            heights.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            heights.setMagFilter(Texture.MagFilter.Nearest);
            heights.setWrap(Texture.WrapMode.EdgeClamp);
            Geometry terrain = new Geometry("original-flat-height-page", new HeightfieldGrid(128).createPatchMesh(page));
            terrain.setQueueBucket(RenderQueue.Bucket.Opaque);
            terrain.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            Material ground = new Material(assetManager, "Common/MatDefs/Terrain/Heightfield/Heightfield.j3md");
            ground.setTexture("HeightPages", heights);
            ground.setFloat("SampleSpacing", .5f);
            ground.setColor("GrassColor", new ColorRGBA(.18f, .24f, .12f, 1));
            ground.setFloat("SnowLine", 1000);
            terrain.setMaterial(ground);
            rootNode.attachChild(terrain);
            block = new Geometry("ordinary-pbr-occluder", new Box(3, 4, 3));
            block.setLocalTranslation(24, 4, 26);
            block.setQueueBucket(RenderQueue.Bucket.Opaque);
            block.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            blockMaterial = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
            blockMaterial.setColor("BaseColor", new ColorRGBA(.4f, .12f, .04f, 1));
            blockMaterial.setFloat("Roughness", .8f);
            blockMaterial.setColor("Emissive", EMISSIVE_COLOR);
            blockMaterial.setFloat("EmissiveIntensity", 0f);
            block.setMaterial(blockMaterial);
            rootNode.attachChild(block);
            // This ordinary PBR receiver sits just beyond the block in the
            // sun's travel direction, inside its ground shadow.
            emissiveReceiver = new Geometry("ordinary-pbr-emissive-shadow-receiver", new Box(1, .15f, 1));
            emissiveReceiver.setLocalTranslation(30, .15f, 30);
            emissiveReceiver.setQueueBucket(RenderQueue.Bucket.Opaque);
            emissiveReceiver.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            emissiveReceiverMaterial = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
            emissiveReceiverMaterial.setColor("BaseColor", new ColorRGBA(.65f, .65f, .65f, 1));
            emissiveReceiverMaterial.setFloat("Roughness", .8f);
            emissiveReceiverMaterial.setColor("Emissive", EMISSIVE_COLOR);
            emissiveReceiverMaterial.setFloat("EmissiveIntensity", 0f);
            emissiveReceiver.setMaterial(emissiveReceiverMaterial);
            rootNode.attachChild(emissiveReceiver);
            sun.setDirection(new Vector3f(.6f, -1f, .35f).normalizeLocal());
            extraA.setDirection(new Vector3f(-.75f, -1f, .2f).normalizeLocal());
            extraB.setDirection(new Vector3f(.15f, -1f, -.85f).normalizeLocal());
            extraA.setColor(new ColorRGBA(.45f, .33f, .25f, 1f));
            extraB.setColor(new ColorRGBA(.25f, .38f, .5f, 1f));
            rootNode.addLight(sun);
            rootNode.addLight(sky);
            // The ordinary PBR block receives one direct batch; terrain gets
            // two differently directed local lights in separate batches.
            terrain.addLight(extraA);
            terrain.addLight(extraB);
            getRenderManager().setSinglePassLightBatchSize(1);
            visibility = new DirectionalLightVisibilityRenderer(assetManager, 1024, 3);
            visibility.setLight(sun);
            visibility.setShadowIntensity(1);
            if (softwareCompare) visibility.setShadowCompareMode(CompareMode.Software);
            visibility.addReceiver(ground);
            visibility.addReceiver(blockMaterial);
            visibility.addReceiver(emissiveReceiverMaterial);
            viewPort.addProcessor(visibility);
            cam.setParallelProjection(true);
            float aspect = (float) cam.getWidth() / cam.getHeight();
            cam.setFrustum(.1f, 200, -34 * aspect, 34 * aspect, 34, -34);
            cam.setLocation(new Vector3f(32, 80, 32));
            cam.lookAt(new Vector3f(32, 0, 32), Vector3f.UNIT_Z);
            cam.update();
            Vector3f blockScreen = cam.getScreenCoordinates(new Vector3f(24, 8, 26));
            blockPixelX = Math.max(1, Math.min(cam.getWidth() - 2, Math.round(blockScreen.x)));
            blockPixelY = Math.max(1, Math.min(cam.getHeight() - 2, Math.round(blockScreen.y)));
            Vector3f receiverScreen = cam.getScreenCoordinates(new Vector3f(30, .3f, 30));
            receiverPixelX = Math.max(1, Math.min(cam.getWidth() - 2, Math.round(receiverScreen.x)));
            receiverPixelY = Math.max(1, Math.min(cam.getHeight() - 2, Math.round(receiverScreen.y)));
            target = new FrameBuffer(cam.getWidth(), cam.getHeight(), 1);
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA16F));
            target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
            pixels = BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 16).order(ByteOrder.nativeOrder());
            viewPort.setOutputFrameBuffer(target);
            viewPort.setBackgroundColor(new ColorRGBA(.12f, .2f, .3f, 1));
            viewPort.addProcessor(new Capture());
        } catch (Throwable error) { fail(error); }
    }
    @Override public void simpleUpdate(float tpf) {
        if (failure != null) return;
        frame++;
        int phase = (frame - 1) / FRAMES_PER_PHASE;
        if (phase >= PHASE_LABELS.length) return;
        boolean sunOn = phase == 0 || phase == 1 || phase == 3 || phase == 4 || phase == 9 || phase >= 10;
        boolean extrasOn = (phase >= 4 && phase <= 7) || phase == 9 || phase >= 10;
        boolean skyOn = phase == 0 || phase == 2 || phase == 3 || phase == 4 || phase == 5 || phase == 9 || phase >= 10;
        sun.setColor(sunOn ? new ColorRGBA(1.4f, 1.28f, 1.1f, 1) : ColorRGBA.Black);
        extraA.setColor(extrasOn ? new ColorRGBA(.45f, .33f, .25f, 1f) : ColorRGBA.Black);
        extraB.setColor(extrasOn ? new ColorRGBA(.25f, .38f, .5f, 1f) : ColorRGBA.Black);
        sky.setColor(skyOn ? new ColorRGBA(.22f, .27f, .34f, 1) : ColorRGBA.Black);
        visibility.setShadowIntensity(phase == 3 || phase == 7 || phase >= 10 ? 0f : 1f);
        float emissiveIntensity = phase == 8 || phase == 9 || phase == 11 ? EMISSIVE_INTENSITY : 0f;
        blockMaterial.setFloat("EmissiveIntensity", emissiveIntensity);
        emissiveReceiverMaterial.setFloat("EmissiveIntensity", emissiveIntensity);
    }
    private void verify() {
        int compared = 0, occluded = 0;
        float maximumSumError = 0, maximumBatchError = 0, maximumExtraFilterError = 0;
        float maximumSunIsolationError = 0, minimumSky = Float.POSITIVE_INFINITY;
        double extraSignal = 0;
        for (int y = 0; y < cam.getHeight(); y++) for (int x = 0; x < cam.getWidth(); x++) {
            Vector3f near = cam.getWorldCoordinates(new Vector2f(x + .5f, y + .5f), 0);
            Vector3f far = cam.getWorldCoordinates(new Vector2f(x + .5f, y + .5f), 1);
            float t = -near.y / (far.y - near.y);
            Vector3f ground = near.interpolateLocal(far, t);
            if (ground.x < 2 || ground.x > 62 || ground.z < 2 || ground.z > 62
                    || (ground.x > 20 && ground.x < 28 && ground.z > 22 && ground.z < 30)
                    || (ground.x > 28 && ground.x < 32 && ground.z > 28 && ground.z < 32)) continue;
            int offset = (y * cam.getWidth() + x) * 4;
            boolean shadowed = false;
            for (int c = 0; c < 3; c++) {
                float skyError = Math.abs(captures[0][offset + c] - captures[1][offset + c] - captures[2][offset + c]);
                maximumSumError = Math.max(maximumSumError, skyError);
                if (skyError > .0015f) throw new IllegalStateException("Sky lost or nonlinear HDR composition: " + skyError);

                float batchError = Math.abs(captures[4][offset + c] - captures[1][offset + c]
                        - captures[6][offset + c] - captures[2][offset + c]);
                float extrasSkyError = Math.abs(captures[5][offset + c] - captures[6][offset + c] - captures[2][offset + c]);
                maximumBatchError = Math.max(maximumBatchError, Math.max(batchError, extrasSkyError));
                if (batchError > .0025f || extrasSkyError > .0025f) {
                    throw new IllegalStateException("Extra-light batches repeated or lost direct/sky energy: " + maximumBatchError);
                }

                float extraFilterError = Math.abs(captures[6][offset + c] - captures[7][offset + c]);
                maximumExtraFilterError = Math.max(maximumExtraFilterError, extraFilterError);
                if (extraFilterError > .0025f) throw new IllegalStateException("Designated sun visibility attenuated an extra direction: " + extraFilterError);
                extraSignal += Math.abs(captures[6][offset + c]);

                float sunIsolationError = Math.abs(captures[4][offset + c] - captures[5][offset + c] - captures[1][offset + c]);
                maximumSunIsolationError = Math.max(maximumSunIsolationError, sunIsolationError);
                if (sunIsolationError > .0025f) throw new IllegalStateException("Extra lights changed the designated sun delta: " + sunIsolationError);

                float direct = captures[3][offset + c] - captures[2][offset + c];
                if (direct > .01f && captures[1][offset + c] < direct * .15f) shadowed = true;
                compared++;
            }
            if (shadowed) {
                occluded++;
                minimumSky = Math.min(minimumSky, Math.min(captures[0][offset],
                        Math.min(captures[0][offset + 1], captures[0][offset + 2])));
            }
        }
        if (occluded < 20 || minimumSky <= .01f) throw new IllegalStateException("Control failed to exhibit sun occlusion with preserved sky: " + occluded);
        if (extraSignal < 5.0) throw new IllegalStateException("The extra directional lights had no measurable terrain contribution: " + extraSignal);

        float[] emissiveOnly = sampleBlock(captures[8]);
        float[] noEmission = sampleBlock(captures[4]);
        float[] withEmission = sampleBlock(captures[9]);
        float[] receiverEmissionOnly = sampleReceiver(captures[8]);
        float[] receiverShadowed = sampleReceiver(captures[4]);
        float[] receiverShadowedEmission = sampleReceiver(captures[9]);
        float[] receiverUnoccluded = sampleReceiver(captures[10]);
        float[] receiverUnoccludedEmission = sampleReceiver(captures[11]);
        float expectedEmissionError = 0f, shadowedEmissionError = 0f;
        float zeroLightReceiverEmissionError = 0f, shadowedReceiverEmissionError = 0f;
        float unoccludedReceiverEmissionError = 0f, receiverShadowSignal = 0f;
        for (int c = 0; c < 3; c++) {
            float expected = component(EMISSIVE_COLOR, c) * EMISSIVE_INTENSITY;
            expectedEmissionError = Math.max(expectedEmissionError, Math.abs(emissiveOnly[c] - expected));
            shadowedEmissionError = Math.max(shadowedEmissionError,
                    Math.abs((withEmission[c] - noEmission[c]) - expected));
            zeroLightReceiverEmissionError = Math.max(zeroLightReceiverEmissionError,
                    Math.abs(receiverEmissionOnly[c] - expected));
            shadowedReceiverEmissionError = Math.max(shadowedReceiverEmissionError,
                    Math.abs((receiverShadowedEmission[c] - receiverShadowed[c]) - expected));
            unoccludedReceiverEmissionError = Math.max(unoccludedReceiverEmissionError,
                    Math.abs((receiverUnoccludedEmission[c] - receiverUnoccluded[c]) - expected));
            receiverShadowSignal += Math.abs(receiverUnoccluded[c] - receiverShadowed[c]);
        }
        if (expectedEmissionError > .0015f || shadowedEmissionError > .0025f
                || zeroLightReceiverEmissionError > .0015f || shadowedReceiverEmissionError > .0025f
                || unoccludedReceiverEmissionError > .0025f || receiverShadowSignal < .02f) {
            throw new IllegalStateException("PBR emissive energy was lost or repeated: zeroLightError="
                    + expectedEmissionError + " shadowedDeltaError=" + shadowedEmissionError
                    + " zeroLightReceiverError=" + zeroLightReceiverEmissionError
                    + " shadowedReceiverDeltaError=" + shadowedReceiverEmissionError
                    + " unoccludedReceiverDeltaError=" + unoccludedReceiverEmissionError
                    + " receiverShadowSignal=" + receiverShadowSignal);
        }
        System.out.println("DIRECTIONAL_VISIBILITY_CONTROL_PASS compare=" + (softwareCompare ? "software" : "hardware")
                + " batchSize=" + getRenderManager().getSinglePassLightBatchSize()
                + " channels=" + compared + " shadowedPixels=" + occluded
                + " maxSkySumError=" + maximumSumError + " maxBatchSumError=" + maximumBatchError
                + " maxExtraFilterError=" + maximumExtraFilterError + " maxSunIsolationError=" + maximumSunIsolationError
                + " extraSignal=" + extraSignal + " zeroLightEmissionError=" + expectedEmissionError
                + " shadowedEmissionDeltaError=" + shadowedEmissionError
                + " zeroLightReceiverEmissionError=" + zeroLightReceiverEmissionError
                + " shadowedReceiverEmissionDeltaError=" + shadowedReceiverEmissionError
                + " unoccludedReceiverEmissionDeltaError=" + unoccludedReceiverEmissionError
                + " receiverShadowSignal=" + receiverShadowSignal + " minimumShadowedFill=" + minimumSky);
    }
    private float[] sampleBlock(float[] rgba) {
        return sample(rgba, blockPixelX, blockPixelY);
    }
    private float[] sampleReceiver(float[] rgba) {
        return sample(rgba, receiverPixelX, receiverPixelY);
    }
    private float[] sample(float[] rgba, int centerX, int centerY) {
        float[] sample = new float[3];
        for (int y = centerY - 1; y <= centerY + 1; y++) {
            for (int x = centerX - 1; x <= centerX + 1; x++) {
                int offset = (y * cam.getWidth() + x) * 4;
                for (int c = 0; c < 3; c++) sample[c] += rgba[offset + c] / 9f;
            }
        }
        return sample;
    }
    private static float component(ColorRGBA color, int index) {
        return index == 0 ? color.r : index == 1 ? color.g : color.b;
    }
    private final class Capture implements SceneProcessor {
        private boolean initialized;
        @Override public void initialize(RenderManager rm, ViewPort vp) { initialized = true; }
        @Override public boolean isInitialized() { return initialized; }
        @Override public void reshape(ViewPort vp, int w, int h) { }
        @Override public void preFrame(float tpf) { }
        @Override public void postQueue(RenderQueue queue) { }
        @Override public void setProfiler(AppProfiler profiler) { }
        @Override public void cleanup() {
            renderer.deleteFrameBuffer(target);
            renderer.deleteImage(heights.getImage());
            for (ByteBuffer data : heights.getImage().getData()) BufferUtils.destroyDirectBuffer(data);
            BufferUtils.destroyDirectBuffer(pixels);
            initialized = false;
        }
        @Override public void postFrame(FrameBuffer out) {
            if (failure != null || frame > PHASE_LABELS.length * FRAMES_PER_PHASE
                    || frame % FRAMES_PER_PHASE != 0) return;
            try {
                pixels.clear();
                renderer.readFrameBufferWithFormat(target, pixels, Image.Format.RGBA32F);
                pixels.rewind();
                FloatBuffer linear = pixels.asFloatBuffer();
                float[] values = new float[linear.remaining()];
                linear.get(values);
                for (float value : values) if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite HDR pixel");
                int phase = frame / FRAMES_PER_PHASE - 1;
                captures[phase] = values;
                write(values, PHASE_LABELS[phase]);
                if (phase == PHASE_LABELS.length - 1) { verify(); finished.countDown(); }
            } catch (Throwable error) { fail(error); }
        }
        private void write(float[] linear, String label) throws Exception {
            Files.createDirectories(output);
            BufferedImage image = new BufferedImage(cam.getWidth(), cam.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < cam.getHeight(); y++) for (int x = 0; x < cam.getWidth(); x++) {
                int i = ((cam.getHeight() - 1 - y) * cam.getWidth() + x) * 4;
                int color = (display(linear[i]) << 16) | (display(linear[i + 1]) << 8) | display(linear[i + 2]);
                image.setRGB(x, y, color);
            }
            ImageIO.write(image, "png", output.resolve(label + ".png").toFile());
        }
        private int display(float value) {
            value = Math.max(0, Math.min(1, value));
            float encoded = value <= .0031308f ? value * 12.92f : 1.055f * (float) Math.pow(value, 1 / 2.4) - .055f;
            return Math.max(0, Math.min(255, Math.round(encoded * 255)));
        }
    }
}
