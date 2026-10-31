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
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.renderer.Caps;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Finite actual HDR transport, depth composition and camera/recreation checks. */
public final class TestAtmosphereAerialPerspective extends SimpleApplication {
    private static final ColorRGBA SURFACE = new ColorRGBA(.8f, .4f, .2f, 1);
    private static final ColorRGBA BACKGROUND = new ColorRGBA(.13f, .27f, .42f, 1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile Throwable failure;
    private GpuAtmosphereSky sky;
    private ClearSkyAtmosphere atmosphere;
    private AtmosphereSegmentReference reference;
    private AtmosphereAerialPerspectiveFilter aerial;
    private FilterPostProcessor post;
    private Geometry plane;
    private FrameBuffer output;
    private int state, frames;
    private float distance;
    private long previousBuilds;
    private float elevation = 55;
    private boolean higherOrders = true;

    public static void main(String[] args) throws Exception {
        TestAtmosphereAerialPerspective app = new TestAtmosphereAerialPerspective();
        if (args.length > 1) app.elevation = Float.parseFloat(args[1]);
        if (args.length > 2) app.higherOrders = Boolean.parseBoolean(args[2]);
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length == 0 ? AppSettings.ANGLE_GLES3 : args[0]);
        settings.setResolution(65, 65);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(false);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (!app.finished.await(90, TimeUnit.SECONDS)) {
            app.stop(false);
            throw new IllegalStateException("aerial probe timed out");
        }
        app.stop(true);
        if (app.failure != null) throw new IllegalStateException("aerial probe failed", app.failure);
        System.out.println("ATMOSPHERE_AERIAL_PASS states=8 resize=true recreation=true higherOrders=" + app.higherOrders);
    }
    @Override public void handleError(String message, Throwable error) {
        fail(error == null ? new IllegalStateException(message) : error);
    }
    private void fail(Throwable error) { failure = error; error.printStackTrace(); finished.countDown(); }

    @Override public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            setDisplayFps(false);
            setDisplayStatView(false);
            viewPort.setBackgroundColor(BACKGROUND);
            atmosphere = new ClearSkyAtmosphere(elevation, 53);
            sky = new GpuAtmosphereSky(assetManager, renderManager, cam, atmosphere, higherOrders);
            reference = new AtmosphereSegmentReference(atmosphere);
            plane = new Geometry("opaque-surface", new Quad(1, 1));
            Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            material.setColor("Color", SURFACE);
            material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
            plane.setMaterial(material);
            rootNode.attachChild(plane);
            aerial = new AtmosphereAerialPerspectiveFilter(sky);
            post = new FilterPostProcessor(assetManager);
            post.setFrameBufferFormat(Image.Format.RGBA16F);
            post.setFrameBufferDepthFormat(Image.Format.Depth24);
            post.addFilter(aerial);
            output = createOutput(65, 65);
            viewPort.setOutputFrameBuffer(output);
            viewPort.addProcessor(post);
            configureState();
        } catch (Throwable error) { fail(error); }
    }

    private FrameBuffer createOutput(int width, int height) {
        FrameBuffer result = new FrameBuffer(width, height, 1);
        result.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA16F));
        result.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
        return result;
    }

    private void configureState() {
        if (state == 0) {
            cam.setParallelProjection(false);
            cam.setFrustumPerspective(55, 1, .1f, 2200);
            cam.setLocation(new Vector3f(0, 400, 0));
            cam.lookAtDirection(Vector3f.UNIT_Z, Vector3f.UNIT_Y);
        }
        distance = state == 0 ? 100 : 900;
        if (state == 2) {
            cam.setLocation(new Vector3f(80, 650, -10));
            cam.lookAtDirection(atmosphere.getSunDirection(new Vector3f()), Vector3f.UNIT_Y);
            cam.setFrustumPerspective(67, 1, .3f, 2200);
        }
        if (state == 3) {
            cam.setLocation(new Vector3f(80, 650, 0));
            cam.lookAtDirection(Vector3f.UNIT_Z, Vector3f.UNIT_Y);
            cam.setParallelProjection(true);
            cam.setFrustum(128, 2176, -80, 80, 80, -80);
        }
        if (state == 4) {
            // Resize both owned output and FilterPostProcessor, as a real scene does.
            post.cleanup();
            renderer.deleteFrameBuffer(output);
            cam.resize(73, 61, false);
            cam.setFrustum(128, 2176, -90, 90, 75, -75);
            output = createOutput(73, 61);
            viewPort.setOutputFrameBuffer(output);
            post.initialize(renderManager, viewPort);
        }
        if (state == 5) aerial.recreateAtlas(); // preceding readback synchronized its users
        if (state == 6) aerial.setEnabled(false);
        if (state == 7) {
            aerial.setEnabled(true);
            Matrix4f oldViewProjection = cam.getViewProjectionMatrix().clone();
            cam.setLocation(cam.getLocation().add(cam.getDirection().mult(64)));
            cam.setFrustum(64, 2112, -90, 90, 75, -75);
            if (!oldViewProjection.equals(cam.getViewProjectionMatrix())) {
                throw new IllegalStateException("orthographic cache fixture must preserve VP exactly");
            }
        }
        float size = cam.isParallelProjection() ? 20 : distance * .2f;
        plane.setLocalScale(size, size, 1);
        plane.setLocalRotation(cam.getRotation());
        plane.setLocalTranslation(cam.getLocation().add(cam.getDirection().mult(distance))
                .subtractLocal(cam.getLeft().mult(size * .5f)).subtractLocal(cam.getUp().mult(size * .5f)));
        frames = 0;
    }

    @Override public void simpleUpdate(float tpf) {
        if (failure != null || output == null || ++frames < 3) return;
        try {
            verifyComposite();
            long builds = aerial.getBuilds();
            if (state == 1 && builds != previousBuilds) throw new IllegalStateException("static camera rebaked unnecessarily");
            if ((state >= 2 && state <= 5 || state == 7) && builds <= previousBuilds) throw new IllegalStateException("camera/storage change did not rebuild");
            previousBuilds = builds;
            if (state == 0 || state == 2 || state == 3) verifyAtlas();
            if (++state < 8) configureState();
            else {
                measureBuilds();
                viewPort.removeProcessor(post);
                sky.close(renderer);
                rootNode.detachChild(plane);
                renderer.deleteFrameBuffer(output);
                output = null;
                finished.countDown();
            }
        } catch (Throwable error) { fail(error); }
    }

    private void verifyComposite() {
        ByteBuffer bytes = BufferUtils.createByteBuffer(output.getWidth() * output.getHeight() * 16).order(ByteOrder.nativeOrder());
        FrameBuffer prior = renderer.getCurrentFrameBuffer();
        try {
            renderer.readFrameBufferWithFormat(output, bytes, Image.Format.RGBA32F);
            FloatBuffer values = bytes.asFloatBuffer();
            int center = ((output.getHeight() / 2) * output.getWidth() + output.getWidth() / 2) * 4;
            AtmosphereSegmentReference.Result expected = evaluate(cam.getLocation(), cam.getDirection(), distance);
            for (int c = 0; c < 3; c++) {
                double lit = state == 6 ? channel(SURFACE, c)
                        : channel(SURFACE, c) * expected.transmittance(c) + expected.radiance(c);
                close("composite state=" + state + " channel=" + c, values.get(center + c), lit, .0015 + .03 * expected.radiance(c));
                // Sky/background has clear depth and must never receive a second atmosphere.
                close("clear-depth background", values.get((output.getWidth() + 1) * 4 + c), channel(BACKGROUND, c), .0005);
            }
            close("alpha", values.get(center + 3), 1, 0);
            System.out.println("AERIAL_COMPOSITE state=" + state + " depth=" + distance + " center="
                    + values.get(center) + "," + values.get(center + 1) + "," + values.get(center + 2));
        } finally { renderer.setFrameBuffer(prior); BufferUtils.destroyDirectBuffer(bytes); }
    }

    private void verifyAtlas() {
        ByteBuffer bytes = aerial.readAtlas();
        try {
            FloatBuffer values = bytes.asFloatBuffer();
            for (int i = 0; i < values.capacity(); i++) if (!Float.isFinite(values.get(i)) || values.get(i) < 0) {
                throw new IllegalStateException("non-finite/negative atlas channel=" + i);
            }
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) for (int c = 0; c < 3; c++) {
                close("zero radiance", texel(values, 0, x, y, c, false), 0, 0);
                close("zero transmittance", texel(values, 0, x, y, c, true), 1, 0);
                float previous = 1;
                for (int slice = 1; slice < 32; slice++) {
                    float current = texel(values, slice, x, y, c, true);
                    if (current > previous + .001f || current > 1) throw new IllegalStateException("non-monotone transmission");
                    previous = current;
                }
            }
            int checks = 0;
            double maxRadianceError = 0, maxTransmittanceError = 0;
            for (int slice : new int[]{1, 8, 16, 31}) for (int[] xy : new int[][]{{0, 0}, {16, 16}, {31, 0}, {0, 31}, {31, 31}}) {
                float x = cam.getFrustumLeft() + (cam.getFrustumRight() - cam.getFrustumLeft()) * xy[0] / 31;
                float y = cam.getFrustumBottom() + (cam.getFrustumTop() - cam.getFrustumBottom()) * xy[1] / 31;
                Vector3f origin = cam.getLocation().clone();
                Vector3f ray = cam.getDirection().clone();
                if (cam.isParallelProjection()) origin.addLocal(cam.getLeft().mult(-x)).addLocal(cam.getUp().mult(y));
                else ray.addLocal(cam.getLeft().mult(-x / cam.getFrustumNear())).addLocal(cam.getUp().mult(y / cam.getFrustumNear()));
                double path = cam.getFrustumFar() * Math.pow(slice / 31.0, 2) * ray.length();
                AtmosphereSegmentReference.Result expected = evaluate(origin, ray, path);
                for (int c = 0; c < 3; c++) {
                    maxRadianceError = Math.max(maxRadianceError, Math.abs(texel(values, slice, xy[0], xy[1], c, false) - expected.radiance(c)));
                    maxTransmittanceError = Math.max(maxTransmittanceError, Math.abs(texel(values, slice, xy[0], xy[1], c, true) - expected.transmittance(c)));
                    close("atlas L state=" + state + " slice=" + slice, texel(values, slice, xy[0], xy[1], c, false), expected.radiance(c), .00003 + .025 * expected.radiance(c));
                    close("atlas T", texel(values, slice, xy[0], xy[1], c, true), expected.transmittance(c), .0015);
                }
                checks++;
            }
            System.out.println("AERIAL_TRANSPORT state=" + state + " referenceRays=" + checks + " finiteChannels=" + values.capacity()
                    + " maxRadianceError=" + maxRadianceError + " maxTransmittanceError=" + maxTransmittanceError);
        } finally { BufferUtils.destroyDirectBuffer(bytes); }
    }

    private AtmosphereSegmentReference.Result evaluate(Vector3f origin, Vector3f direction, double length) {
        return reference.evaluate(origin, direction, length, 96, higherOrders ? (r, mu, rgb) -> {
            ColorRGBA value = sky.sampleMultiple(r, mu, new ColorRGBA());
            rgb[0] = value.r; rgb[1] = value.g; rgb[2] = value.b;
        } : null);
    }

    private void measureBuilds() {
        if (!renderer.getCaps().contains(Caps.GpuTimerQuery)) return;
        int query = renderer.generateProfilingTasks(1)[0];
        long[] elapsed = new long[10];
        for (int i = 0; i < elapsed.length; i++) {
            aerial.invalidate();
            renderer.startProfiling(query);
            aerial.updateAtlas(cam);
            renderer.stopProfiling();
            ByteBuffer synchronizedPixels = aerial.readAtlas();
            BufferUtils.destroyDirectBuffer(synchronizedPixels);
            if (!renderer.isTaskResultAvailable(query) || renderer.isGpuTimingDisjoint()) {
                throw new IllegalStateException("finite isolated atlas timing unavailable/disjoint");
            }
            elapsed[i] = renderer.getProfilingTime(query);
        }
        Arrays.sort(elapsed);
        System.out.println("AERIAL_BUILD_COST samples=10 gpuMedianMs=" + elapsed[5] / 1e6
                + " gpuMaxMs=" + elapsed[9] / 1e6 + " atlasBytes=" + aerial.getEncodedBytes());
    }

    private static float texel(FloatBuffer values, int slice, int x, int y, int channel, boolean transmission) {
        int column = slice % 8 + (transmission ? 8 : 0), row = slice / 8;
        return values.get(((row * 32 + y) * 512 + column * 32 + x) * 4 + channel);
    }
    private static float channel(ColorRGBA color, int c) { return c == 0 ? color.r : c == 1 ? color.g : color.b; }
    private static void close(String label, double actual, double expected, double tolerance) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance) {
            throw new IllegalStateException(label + " expected=" + expected + " actual=" + actual + " tolerance=" + tolerance);
        }
    }
}
