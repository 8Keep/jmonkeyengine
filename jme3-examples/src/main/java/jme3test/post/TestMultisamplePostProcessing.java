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
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package jme3test.post;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.MatParam;
import com.jme3.math.ColorRGBA;
import com.jme3.post.Filter;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.FilterPostProcessor.MultisampleMode;
import com.jme3.post.SceneProcessor;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengles.GLES20;
import org.lwjgl.opengles.GLES30;
import org.lwjgl.opengles.GLES31;

/**
 * Finite synthetic example of sampled and resolved post-processing inputs.
 * Run with LWJGL-OpenGL33 or ANGLE_GLES3. No external scene assets are needed.
 * A sample mask writes known color/depth pairs, including a far-depth
 * background. A custom filter applies a depth-dependent operation before resolve.
 */
public final class TestMultisamplePostProcessing extends SimpleApplication {
    private static final String ASSETS = "Common/MatDefs/Post/MultisampleExample/";
    private static final String[] STATES = {"sampled-4", "sampled-1", "sampled-4-again",
        "resize-recreate", "request-3", "over-limit", "unchanged-image-update",
        "fxaa-sampled", "fxaa-resolved", "automatic"};
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Camera passCamera = new Camera(1, 1);
    private final PairFilter pair = new PairFilter();
    private final ObservedFxaa fxaa = new ObservedFxaa();
    private final SourceWriter writer = new SourceWriter();
    private volatile Throwable failure;
    private FilterPostProcessor post;
    private Material fill, inspect;
    private Geometry quad;
    private FrameBuffer output, diagnostic;
    private int state, frames, width = 8, height = 8, requested = 4, negotiated, sceneSamples, checks;
    private boolean gles, inspected, stopped;
    private float[] sampledFxaa;

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || args.length == 1
                && !AppSettings.LWJGL_OPENGL33.equals(args[0])
                && !AppSettings.ANGLE_GLES3.equals(args[0])) {
            throw new IllegalArgumentException("Usage: TestMultisamplePostProcessing [LWJGL-OpenGL33|ANGLE_GLES3]");
        }
        TestMultisamplePostProcessing app = new TestMultisamplePostProcessing();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length == 0 ? AppSettings.LWJGL_OPENGL33 : args[0]);
        settings.setResolution(32, 24);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(false);
        settings.setVSync(false);
        settings.setX11PlatformPreferred(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (!app.finished.await(90, TimeUnit.SECONDS)) {
            app.stop(false);
            throw new IllegalStateException("multisample post-processing example timed out");
        }
        app.stop(true);
        if (app.failure != null) throw new IllegalStateException("multisample post-processing example failed", app.failure);
        System.out.println("MULTISAMPLE_POST_PROCESSING_PASS states=" + STATES.length + " sourceSamples=" + app.checks);
    }

    @Override public void handleError(String message, Throwable error) {
        failure = error == null ? new IllegalStateException(message) : error;
        failure.printStackTrace();
        finished.countDown();
    }

    @Override public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            setDisplayFps(false);
            setDisplayStatView(false);
            gles = renderer.getCaps().contains(Caps.OpenGLES30);
            require(renderer.getCaps().contains(Caps.TextureMultisample), "sampleable multisample textures are required");
            require(!gles || renderer.getCaps().contains(Caps.OpenGLES31), "sample masks require GLES 3.1");
            fill = new Material(assetManager, ASSETS + "Fill.j3md");
            inspect = new Material(assetManager, ASSETS + "Inspect.j3md");
            quad = new Geometry("multisample-example-pass", new Quad(1, 1));
            diagnostic = target(1, 1);
            output = target(width, height);
            cam.resize(width, height, false);
            viewPort.setOutputFrameBuffer(output);
            viewPort.addProcessor(writer); // Its postFrame runs before FPP's resolve.
            post = new FilterPostProcessor(assetManager);
            post.setFrameBufferFormat(Image.Format.RGBA16F);
            post.setFrameBufferDepthFormat(Image.Format.Depth24);
            post.setMultisampleMode(MultisampleMode.Sampled);
            post.setNumSamples(requested);
            post.addFilter(pair);
            viewPort.addProcessor(post);
            negotiated = preferred(requested);
            require(negotiated > 1, "the synthetic paired case requires at least two supported samples");
        } catch (Throwable error) { handleError("initialization", error); }
    }

    @Override public void simpleUpdate(float tpf) {
        if (stopped || failure != null || post == null || ++frames < 3) return;
        try {
            require(inspected, "filter input inspection did not execute");
            float[] image = read(output, width, height);
            if (state == 7) {
                sampledFxaa = image;
                float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < image.length; i += 4) { low = Math.min(low, image[i]); high = Math.max(high, image[i]); }
                require(high - low > .001f, "FXAA fixture must contain a nonconstant image");
            } else if (state == 8) {
                require(sampledFxaa.length == image.length, "FXAA output dimensions differ");
                for (int i = 0; i < image.length; i++) near(image[i], sampledFxaa[i], .0001, "FXAA resolved reference");
            } else {
                for (int i = 0; i < image.length; i++) near(image[i], pair.expected[i % 4], .0001, "paired output");
            }
            System.out.println("MULTISAMPLE_POST_STATE name=" + STATES[state] + " request=" + requested
                    + " selected=" + negotiated + " scene=" + sceneSamples + " mode=" + post.getMultisampleMode());
            if (++state == STATES.length) { finish(); return; }
            configureNext();
        } catch (Throwable error) { handleError("state " + state, error); }
    }

    private int preferred(int request) {
        int result = renderer.getPreferredFrameBufferSamples(Image.Format.RGBA16F, Image.Format.Depth24, request);
        require(result >= 1 && result <= request, "negotiation exceeded its request");
        return result;
    }

    private void configureNext() {
        frames = 0;
        inspected = false;
        requested = state == 1 ? 1 : state == 4 ? 3 : state == 5
                ? Math.max(5, renderer.getLimits().get(Limits.FrameBufferSamples) + 1) : 4;
        negotiated = preferred(requested);
        if (state == 7) { post.removeFilter(pair); post.addFilter(fxaa); }
        if (state == 9) { post.removeFilter(fxaa); post.addFilter(pair); }
        post.setMultisampleMode(state == 8 ? MultisampleMode.Resolved
                : state == 9 ? MultisampleMode.Automatic : MultisampleMode.Sampled);
        post.setNumSamples(requested);
        if (state == 3) {
            post.cleanup();
            renderer.deleteFrameBuffer(output);
            width = 12;
            height = 10;
            output = target(width, height);
            cam.resize(width, height, false);
            viewPort.setOutputFrameBuffer(output);
            post.initialize(renderManager, viewPort);
        } else post.reshape(viewPort, width, height);
    }

    private static ColorRGBA color(int sample) {
        int index = sample % 4;
        return new ColorRGBA((index + 1) / 8f, (4 - index) / 8f, index % 2 == 0 ? .75f : .125f, 1);
    }

    private static float depth(int sample, int count) {
        return count > 1 && sample == count - 1 ? 1 : .2f + .5f * (sample + 1) / (count + 1);
    }

    private final class SourceWriter implements SceneProcessor {
        private FrameBuffer source;
        private boolean initialized;
        @Override public void initialize(RenderManager rm, ViewPort vp) { initialized = true; }
        @Override public boolean isInitialized() { return initialized; }
        @Override public void postQueue(RenderQueue queue) { source = renderer.getCurrentFrameBuffer(); }
        @Override public void postFrame(FrameBuffer ignored) {
            sceneSamples = source.getSamples();
            require(sceneSamples >= 1 && sceneSamples <= 32, "fixture requires one sample-mask word");
            if (post.getMultisampleMode() == MultisampleMode.Sampled) require(sceneSamples == negotiated, "FPP scene sample negotiation differs");
            boolean multisample = sceneSamples > 1;
            if (multisample && !inspected) {
                com.jme3.math.Vector2f[] positions = ((com.jme3.renderer.opengl.GLRenderer) renderer).getFrameBufferSamplePositions(source);
                require(positions.length == sceneSamples, "sample position count differs from actual storage");
                for (com.jme3.math.Vector2f position : positions) {
                    require(Float.isFinite(position.x) && Float.isFinite(position.y)
                            && Math.abs(position.x) <= .5f && Math.abs(position.y) <= .5f,
                            "invalid sample position");
                }
            }
            boolean wasEnabled = multisample && (gles ? GLES20.glIsEnabled(GLES31.GL_SAMPLE_MASK) : GL11.glIsEnabled(GL32.GL_SAMPLE_MASK));
            int oldMask = multisample ? (gles ? GLES30.glGetIntegeri(GLES31.GL_SAMPLE_MASK_VALUE, 0)
                    : GL30.glGetIntegeri(GL32.GL_SAMPLE_MASK_VALUE, 0)) : 0;
            boolean coverage = renderer.getAlphaToCoverage();
            try {
                renderer.setAlphaToCoverage(false);
                if (multisample) { if (gles) GLES20.glEnable(GLES31.GL_SAMPLE_MASK); else GL11.glEnable(GL32.GL_SAMPLE_MASK); }
                fill.setBoolean("Pattern", state == 7 || state == 8);
                for (int sample = 0; sample < sceneSamples; sample++) {
                    if (multisample) mask(1 << sample);
                    fill.setColor("Color", color(sample));
                    fill.setFloat("Depth", depth(sample, sceneSamples));
                    draw(fill, source, width, height);
                }
                // ES storage is immutable; desktop keeps its historical mutable upload behavior.
                if (state == 6 && gles && !inspected) {
                    Texture2D color = (Texture2D) source.getColorTarget().getTexture();
                    Texture2D depth = (Texture2D) source.getDepthTarget().getTexture();
                    int colorId = color.getImage().getId(), depthId = depth.getImage().getId();
                    color.getImage().setUpdateNeeded();
                    depth.getImage().setUpdateNeeded();
                    renderer.setTexture(0, color);
                    renderer.setTexture(1, depth);
                    require(color.getImage().getId() == colorId && depth.getImage().getId() == depthId,
                            "unchanged image update replaced multisample storage");
                }
            } catch (com.jme3.renderer.TextureUnitException failure) {
                throw new IllegalStateException("Unable to rebind the fixture's texture units", failure);
            } finally {
                if (multisample) {
                    mask(oldMask);
                    if (!wasEnabled) { if (gles) GLES20.glDisable(GLES31.GL_SAMPLE_MASK); else GL11.glDisable(GL32.GL_SAMPLE_MASK); }
                }
                renderer.setAlphaToCoverage(coverage);
                renderManager.setCamera(cam, false, width, height);
            }
        }
        private void mask(int bits) { if (gles) GLES31.glSampleMaski(0, bits); else GL32.glSampleMaski(0, bits); }
        @Override public void preFrame(float tpf) { }
        @Override public void reshape(ViewPort vp, int w, int h) { }
        @Override public void setProfiler(AppProfiler profiler) { }
        @Override public void cleanup() { initialized = false; }
    }

    private final class PairFilter extends Filter {
        final double[] expected = new double[4];
        PairFilter() { super("Sample-paired example"); }
        @Override protected boolean isRequiresDepthTexture() { return true; }
        @Override protected void initFilter(AssetManager assets, RenderManager rm, ViewPort vp, int w, int h) {
            material = new Material(assets, ASSETS + "Paired.j3md");
        }
        @Override protected Material getMaterial() { return material; }
        @Override protected void postFilter(Renderer r, FrameBuffer buffer) {
            try {
                inspectInput(r, buffer);
            } catch (RuntimeException | Error failure) {
                handleError("multisample source inspection", failure);
                throw failure;
            }
        }
        private void inspectInput(Renderer r, FrameBuffer buffer) {
            if (inspected) return;
            Texture2D color = (Texture2D) material.getTextureParam("Texture").getTextureValue();
            Texture2D depth = (Texture2D) material.getTextureParam("DepthTexture").getTextureValue();
            int count = color.getImage().getMultiSamples();
            require(count == depth.getImage().getMultiSamples(), "FPP inputs have different sample counts");
            boolean paired = post.getMultisampleMode() == MultisampleMode.Sampled
                    || post.getMultisampleMode() == MultisampleMode.Automatic
                    && renderer.getCaps().contains(Caps.OpenGL32);
            require(count == (paired ? negotiated : 1), "FPP input policy differs");
            checkParameter(material, "NumSamples", count);
            checkParameter(material, "NumSamplesDepth", count);
            inspect.setTexture("Texture", color);
            inspect.setTexture("DepthTexture", depth);
            if (count > 1) { inspect.setInt("NumSamples", count); inspect.setInt("NumSamplesDepth", count); }
            else { inspect.clearParam("NumSamples"); inspect.clearParam("NumSamplesDepth"); }
            for (int c = 0; c < 4; c++) expected[c] = 0;
            double[] meanColor = new double[3];
            double meanDepth = 0;
            for (int sample = 0; sample < count; sample++) {
                inspect.setInt("Sample", sample);
                inspect.setBoolean("DepthOnly", false);
                draw(inspect, diagnostic, 1, 1);
                float[] rgba = TestMultisamplePostProcessing.this.read(diagnostic, 1, 1);
                inspect.setBoolean("DepthOnly", true);
                draw(inspect, diagnostic, 1, 1);
                float z = TestMultisamplePostProcessing.this.read(diagnostic, 1, 1)[0];
                if (paired) {
                    float[] known = color(sample).getColorArray();
                    for (int c = 0; c < 4; c++) near(rgba[c], known[c], 1e-6, "source color");
                    near(z, depth(sample, count), 2.0 / 16777215, "source depth");
                }
                for (int c = 0; c < 3; c++) {
                    meanColor[c] += rgba[c] / count;
                    expected[c] += (z >= 1 ? rgba[c] : rgba[c] * (.25 + .75 * z) + .1 * z) / count;
                }
                meanDepth += z / count;
                expected[3] += rgba[3] / count;
                checks++;
            }
            if (paired && count > 1) {
                double difference = 0;
                for (int c = 0; c < 3; c++) {
                    double separatelyAveraged = meanColor[c] * (.25 + .75 * meanDepth) + .1 * meanDepth;
                    difference = Math.max(difference, Math.abs(expected[c] - separatelyAveraged));
                }
                require(difference > .001, "fixture does not distinguish separately averaged inputs");
            }
            inspected = true;
            renderer.setFrameBuffer(buffer);
            renderManager.setCamera(cam, false, width, height);
        }
    }

    private final class ObservedFxaa extends FXAAFilter {
        @Override protected void postFilter(Renderer r, FrameBuffer buffer) {
            Material input = super.getMaterial();
            Texture2D texture = (Texture2D) input.getTextureParam("Texture").getTextureValue();
            require(texture.getImage().getMultiSamples() == 1, "bilinear FXAA must receive resolved color");
            checkParameter(input, "NumSamples", 1);
            inspected = true;
        }
    }

    private static void checkParameter(Material material, String name, int samples) {
        MatParam parameter = material.getParam(name);
        require(samples == 1 ? parameter == null : parameter != null && ((Integer) parameter.getValue()) == samples,
                "incorrect filter parameter " + name);
    }

    private static FrameBuffer target(int w, int h) {
        FrameBuffer result = new FrameBuffer(w, h, 1);
        result.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        return result;
    }

    private void draw(Material material, FrameBuffer target, int w, int h) {
        renderer.setFrameBuffer(target);
        renderManager.setCamera(passCamera, false, w, h);
        quad.setMaterial(material);
        renderManager.renderGeometry(quad);
    }

    private float[] read(FrameBuffer target, int w, int h) {
        FrameBuffer old = renderer.getCurrentFrameBuffer();
        Camera camera = renderManager.getCurrentCamera() == null ? cam : renderManager.getCurrentCamera();
        ByteBuffer bytes = BufferUtils.createByteBuffer(w * h * 16).order(ByteOrder.nativeOrder());
        try {
            renderer.setFrameBuffer(target);
            renderManager.setCamera(passCamera, false, w, h);
            renderer.readFrameBufferWithFormat(target, bytes, Image.Format.RGBA32F);
            float[] result = new float[w * h * 4];
            bytes.asFloatBuffer().get(result);
            return result;
        } finally {
            renderer.setFrameBuffer(old);
            renderManager.setCamera(camera, false, old == null ? camera.getWidth() : old.getWidth(),
                    old == null ? camera.getHeight() : old.getHeight());
            BufferUtils.destroyDirectBuffer(bytes);
        }
    }

    private void finish() {
        stopped = true;
        viewPort.removeProcessor(post);
        viewPort.removeProcessor(writer);
        renderer.deleteFrameBuffer(output);
        renderer.deleteFrameBuffer(diagnostic);
        viewPort.setOutputFrameBuffer(null);
        quad.setMaterial(null);
        for (VertexBuffer buffer : quad.getMesh().getBufferList()) {
            renderer.deleteBuffer(buffer);
            if (buffer.getData() != null) BufferUtils.destroyDirectBuffer(buffer.getData());
        }
        finished.countDown();
    }

    private static void near(double actual, double expected, double tolerance, String label) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                label + " actual=" + actual + " expected=" + expected);
    }
    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
