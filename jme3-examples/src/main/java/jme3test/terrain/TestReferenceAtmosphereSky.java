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
import com.jme3.asset.AssetEventListener;
import com.jme3.asset.AssetKey;
import com.jme3.material.MatParamOverride;
import com.jme3.renderer.Camera;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Renderer;
import com.jme3.system.AppSettings;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Finite native validation of sky direction, transport and failed-bake recovery. */
public final class TestReferenceAtmosphereSky extends SimpleApplication {
    private static final int SIZE = 65;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final List<Probe> probes = new ArrayList<>();
    private AtmosphereSky sky;
    private boolean gpuMode;
    private float sunElevation = 55;
    private ClearSkyAtmosphere atmosphere;
    private ClearSkyAtmosphere validationAtmosphere;
    private FrameBuffer target;
    private int probeIndex;
    private int completedFrames;
    private volatile Throwable failure;

    private TestReferenceAtmosphereSky() {
        super();
    }

    public static void main(String[] args) throws Exception {
        TestReferenceAtmosphereSky app = new TestReferenceAtmosphereSky();
        for (String argument : args) {
            if ("--gpu".equals(argument)) app.gpuMode = true;
            else if (argument.startsWith("--sun-elevation=")) {
                app.sunElevation = Float.parseFloat(argument.substring("--sun-elevation=".length()));
            }
        }
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length > 0 ? args[0] : AppSettings.ANGLE_GLES3);
        settings.setResolution(SIZE, SIZE);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(false);
        settings.setGraphicsDebug(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (!app.finished.await(90, TimeUnit.SECONDS)) {
            app.stop(false);
            throw new IllegalStateException("Atmosphere sky render check timed out");
        }
        app.stop(true);
        if (app.failure != null) {
            throw new IllegalStateException("Atmosphere sky render check failed", app.failure);
        }
        System.out.println((app.gpuMode ? "GPU_ATMOSPHERE_SKY_PASS" : "REFERENCE_ATMOSPHERE_SKY_PASS")
                + " probes=" + app.probes.size());
    }

    @Override
    public void handleError(String message, Throwable error) {
        fail(error == null ? new IllegalStateException(message) : error);
    }

    @Override
    public void simpleInitApp() {
        try {
            ReferenceAtmosphereSky.requireFloatTextureFiltering(renderer);
            flyCam.setEnabled(false);
            setDisplayFps(false);
            setDisplayStatView(false);
            atmosphere = new ClearSkyAtmosphere(sunElevation, 53);
            validationAtmosphere = gpuMode ? new ClearSkyAtmosphere(sunElevation, 53, 96, 96) : atmosphere;
            target = new FrameBuffer(SIZE, SIZE, 1);
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
            target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
            viewPort.setOutputFrameBuffer(target);
            renderer.setFrameBuffer(target);
            renderManager.setCamera(cam, false, SIZE, SIZE);
            if (gpuMode) verifyPartialBakeFailure();
            sky = gpuMode ? new GpuAtmosphereSky(assetManager, renderManager, cam, atmosphere,
                    false, Vector3f.ZERO) : new ReferenceAtmosphereSky(assetManager, atmosphere);
            if (renderer.getCurrentFrameBuffer() != target || renderManager.getCurrentCamera() != cam) {
                throw new IllegalStateException("atmosphere generation did not restore the physical target and camera");
            }
            rootNode.attachChild(sky.getSpatial());

            Vector3f sun = atmosphere.getSunDirection(new Vector3f()).normalizeLocal();
            Vector3f oppositeAzimuth = new Vector3f(-sun.x, sun.y, -sun.z).normalizeLocal();
            addProbe("upper-sky", new Vector3f(.3f, .8f, .4f), 45, false);
            addProbe("ground", new Vector3f(0, -1, 0), 45, false);
            addProbe("south-east", new Vector3f(.6f, .1f, .7f), 45, false);
            addProbe("north-east", new Vector3f(-.6f, .1f, .7f), 45, false);
            addProbe("opposite-sun-azimuth", oppositeAzimuth, 45, false);
            addProbe("solar-disk", sun, 1, true);
            aimAt(probes.get(0));
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void addProbe(String name, Vector3f direction, float fovDegrees, boolean solar) {
        direction.normalizeLocal();
        Vector3f up = Math.abs(direction.dot(Vector3f.UNIT_Y)) > .98f ? Vector3f.UNIT_Z : Vector3f.UNIT_Y;
        ColorRGBA expected = validationAtmosphere.skyRadiance(direction, new ColorRGBA());
        if (solar) {
            ColorRGBA irradiance = validationAtmosphere.getSunIrradiance(new ColorRGBA());
            float omega = (float) (2 * Math.PI * (1 - Math.cos(.004675)));
            expected.addLocal(irradiance.mult(1f / omega));
        }
        probes.add(new Probe(name, direction, up, fovDegrees, solar, expected));
    }

    private void aimAt(Probe probe) {
        cam.setFrustumPerspective(probe.fovDegrees, 1f, .1f, 1000f);
        cam.setLocation(Vector3f.ZERO);
        cam.lookAtDirection(probe.direction, probe.up);
        completedFrames = 0;
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (failure != null || probeIndex >= probes.size()) return;
        // simpleUpdate runs before drawing; capture after two completed frames.
        if (++completedFrames < 3) return;
        try {
            Probe probe = probes.get(probeIndex);
            ColorRGBA actual = readCenter(renderer, target);
            compare(probe, actual);
            probeIndex++;
            if (probeIndex == probes.size()) {
                sky.getSpatial().removeFromParent();
                sky.close(renderer); // readback has drained the final sky draw.
                sky = null;
                if (gpuMode) verifyGpuTransport();
                renderer.deleteFrameBuffer(target);
                target = null;
                viewPort.setOutputFrameBuffer(null);
                finished.countDown();
            } else {
                aimAt(probes.get(probeIndex));
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void verifyPartialBakeFailure() {
        Camera priorCamera = renderManager.getCurrentCamera();
        FrameBuffer priorTarget = renderer.getCurrentFrameBuffer();
        List<MatParamOverride> overrides = new ArrayList<>(renderManager.getForcedMatParams());
        IllegalStateException injected = new IllegalStateException("injected atmosphere second-pass failure");
        AssetEventListener fault = new AssetEventListener() {
            @Override public void assetLoaded(AssetKey key) { }
            @Override public void assetDependencyNotFound(AssetKey parent, AssetKey dependency) { }
            @Override public void assetRequested(AssetKey key) {
                if ("MatDefs/AtmosphereMultipleScattering.j3md".equals(key.getName())) throw injected;
            }
        };
        assetManager.addAssetEventListener(fault);
        try {
            GpuAtmosphereSky unexpected = new GpuAtmosphereSky(assetManager, renderManager, cam, atmosphere, true);
            unexpected.close(renderer);
            throw new IllegalStateException("atmosphere failure was not injected");
        } catch (RuntimeException failure) {
            if (failure != injected) throw failure;
        } finally {
            assetManager.removeAssetEventListener(fault);
        }
        if (renderer.getCurrentFrameBuffer() != priorTarget || renderManager.getCurrentCamera() != priorCamera
                || !renderManager.getForcedMatParams().equals(overrides)) {
            throw new IllegalStateException("failed atmosphere bake changed caller state");
        }
        System.out.println("GPU_ATMOSPHERE_FAILURE_PASS after=transmittance callerState=preserved");
    }

    private void verifyGpuTransport() {
        // The GUI viewport may be the preceding frame's active camera. Compare
        // with the actual caller state rather than assuming the main camera.
        Camera priorCamera = renderManager.getCurrentCamera();
        FrameBuffer priorTarget = renderer.getCurrentFrameBuffer();
        GpuAtmosphereSky generated = new GpuAtmosphereSky(assetManager, renderManager, cam, atmosphere, true);
        sky = generated;
        try {
            if (renderer.getCurrentFrameBuffer() != priorTarget || renderManager.getCurrentCamera() != priorCamera) {
                throw new IllegalStateException("multiple-scattering bake changed its caller target/camera");
            }
            ClearSkyAtmosphere.ModelParameters model = ClearSkyAtmosphere.modelParameters();
            for (double altitude : new double[]{0, 200, 10_000, 50_000}) {
                for (double cosine : new double[]{-.1, .01, .2, 1}) {
                    double radius = model.groundRadiusMeters() + altitude;
                    double[] expected = model.sunTransmittance(radius, cosine, 96);
                    ColorRGBA actual = generated.sampleTransmission(radius, cosine, new ColorRGBA());
                    float[] channels = {actual.r, actual.g, actual.b};
                    for (int c = 0; c < 3; c++) {
                        double tolerance = .003 + .02 * expected[c];
                        if (Math.abs(channels[c] - expected[c]) > tolerance) {
                            throw new IllegalStateException("GPU transmission differs at altitude=" + altitude
                                    + " cosine=" + cosine + " channel=" + c + " expected=" + expected[c]
                                    + " actual=" + channels[c]);
                        }
                    }
                }
            }
            AtmosphereMultipleScatteringReference reference = new AtmosphereMultipleScatteringReference(128, 96);
            for (int[] point : new int[][]{{0, 0}, {16, 0}, {24, 0}, {31, 0}, {16, 2}, {24, 8}, {31, 24}}) {
                int x = point[0], y = point[1];
                double radius = model.groundRadiusMeters()
                        + (model.topRadiusMeters() - model.groundRadiusMeters()) * y / (GpuAtmosphereSky.MULTIPLE_SIZE - 1);
                double cosine = 2.0 * x / (GpuAtmosphereSky.MULTIPLE_SIZE - 1) - 1;
                AtmosphereMultipleScatteringReference.Result expected = reference.evaluate(radius, cosine, .3);
                ColorRGBA actual = generated.capturedMultiple(x, y, new ColorRGBA());
                double[] values = {actual.r, actual.g, actual.b};
                double transfer = 0;
                for (int c = 0; c < 3; c++) {
                    transfer = Math.max(transfer, expected.transfer(c));
                    double tolerance = .0003 + .06 * expected.psi(c);
                    if (Math.abs(values[c] - expected.psi(c)) > tolerance) {
                        throw new IllegalStateException("GPU multiple scattering differs at " + x + "," + y
                                + " channel=" + c + " expected=" + expected.psi(c) + " actual=" + values[c]);
                    }
                }
                if (Math.abs(actual.a - transfer) > .02) {
                    throw new IllegalStateException("GPU multiple scattering transfer differs at " + x + "," + y);
                }
                System.out.printf("ATMOSPHERE_MULTIPLE_PROBE x=%d y=%d psi=%s maxTransfer=%.7g%n", x, y, actual, transfer);
            }
            System.out.println("GPU_ATMOSPHERE_TRANSPORT_PASS transmissionRays=16 multiplePoints=7 payloadBytes="
                    + generated.getEncodedBytes() + " generationMs=" + generated.getGenerationMillis());
        } finally {
            generated.close(renderer);
            sky = null;
        }
    }

    private static ColorRGBA readCenter(Renderer renderer, FrameBuffer target) {
        ByteBuffer bytes = BufferUtils.createByteBuffer(SIZE * SIZE * 4 * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        try {
            renderer.readFrameBufferWithFormat(target, bytes, Image.Format.RGBA32F);
            FloatBuffer values = bytes.asFloatBuffer();
            int center = ((SIZE / 2) * SIZE + SIZE / 2) * 4;
            return new ColorRGBA(values.get(center), values.get(center + 1), values.get(center + 2), 1);
        } finally {
            renderer.setFrameBuffer(target); // owned target, not the reported main override
            BufferUtils.destroyDirectBuffer(bytes);
        }
    }

    private void compare(Probe probe, ColorRGBA actual) {
        ColorRGBA expected = probe.expected;
        compareChannel(probe, "red", actual.r, expected.r);
        compareChannel(probe, "green", actual.g, expected.g);
        compareChannel(probe, "blue", actual.b, expected.b);
        System.out.printf("SKY_PROBE %s expected=(%.7g,%.7g,%.7g) actual=(%.7g,%.7g,%.7g)%n",
                probe.name, expected.r, expected.g, expected.b, actual.r, actual.g, actual.b);
    }

    private static void compareChannel(Probe probe, String channel, float actual, float expected) {
        if (!Float.isFinite(actual)) throw new IllegalStateException(probe.name + " " + channel + " is non-finite");
        float relative = probe.solar ? .005f : .02f;
        float tolerance = Math.max(1e-4f, Math.abs(expected) * relative);
        if (Math.abs(actual - expected) > tolerance) {
            throw new IllegalStateException(probe.name + " " + channel + " expected " + expected
                    + " but got " + actual + " (tolerance " + tolerance + ")");
        }
    }

    private void fail(Throwable error) {
        if (failure == null) failure = error;
        if (sky != null && renderer != null) {
            try { sky.getSpatial().removeFromParent(); sky.close(renderer); } catch (Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
        }
        if (target != null && renderer != null) {
            try {
                viewPort.setOutputFrameBuffer(null);
                renderer.deleteFrameBuffer(target);
                target = null;
            } catch (Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
        }
        finished.countDown();
    }

    private static final class Probe {
        private final String name;
        private final Vector3f direction;
        private final Vector3f up;
        private final float fovDegrees;
        private final boolean solar;
        private final ColorRGBA expected;

        private Probe(String name, Vector3f direction, Vector3f up, float fovDegrees,
                boolean solar, ColorRGBA expected) {
            this.name = name;
            this.direction = direction;
            this.up = up;
            this.fovDegrees = fovDegrees;
            this.solar = solar;
            this.expected = expected;
        }
    }
}
