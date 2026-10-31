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
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Caps;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.renderer.Renderer;
import com.jme3.system.AppSettings;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Finite GPU sky-view LUT, mapping, horizon and display regression probe. */
public final class TestSkyViewAtmosphere extends SimpleApplication {
    private static final int WIDTH = 65, HEIGHT = 65;
    private static final double TWO_PI = 2 * Math.PI;
    private static final float SOLAR_RADIUS = .004675f;
    private static final int[][] TEXELS = {
        {0, 0}, {0, 31}, {128, 0}, {128, 31}, {255, 31},
        {0, 63}, {64, 63}, {128, 63}, {192, 63}, {255, 63},
        {0, 64}, {64, 64}, {128, 64}, {192, 64}, {255, 64},
        {0, 127}, {0, 95}, {128, 127}, {128, 95}, {255, 95}
    };
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Vector3f[] origins = {
        new Vector3f(0, 200, 0), new Vector3f(12000, 2500, -8000), new Vector3f(0, .5f, 0)
    };
    private volatile Throwable failure;
    private ClearSkyAtmosphere atmosphere;
    private GpuAtmosphereSky sky;
    private GpuAtmosphereSkyView skyView;
    private AtmosphereSegmentReference reference;
    private FrameBuffer output;
    private Material originalDisplay;
    private Mesh originalMesh;
    private int originIndex, poseIndex, frames;
    private boolean higherOrders = true, expectedBuild, expectedNoBuild, mapDue, recreated, orthographicChecked;
    private long baselineBuilds;
    private RayPose[] poses;
    private double maximumTransportError, maximumDisplayError;
    private int transportSamples, displaySamples;

    public static void main(String[] args) throws Exception {
        TestSkyViewAtmosphere app = new TestSkyViewAtmosphere();
        String rendererName = args.length > 0 ? args[0] : AppSettings.ANGLE_GLES3;
        if (args.length > 1) app.sunElevation = Float.parseFloat(args[1]);
        if (args.length > 2) app.higherOrders = Boolean.parseBoolean(args[2]);
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(rendererName);
        settings.setResolution(WIDTH, HEIGHT);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(false);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (!app.finished.await(120, TimeUnit.SECONDS)) {
            app.stop(false);
            throw new IllegalStateException("sky-view probe timed out");
        }
        app.stop(true);
        if (app.failure != null) throw new IllegalStateException("sky-view probe failed", app.failure);
        System.out.println("SKY_VIEW_PASS origins=3 transportSamples=" + app.transportSamples
                + " displaySamples=" + app.displaySamples + " maxTransportError=" + app.maximumTransportError
                + " maxDisplayError=" + app.maximumDisplayError + " recreate=true orthographic=true higherOrders=" + app.higherOrders);
    }

    private float sunElevation = 55;

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
            if (!renderer.getCaps().contains(Caps.FloatColorBufferRGBA)
                    || !renderer.getCaps().contains(Caps.FloatTextureFilter)) {
                throw new UnsupportedOperationException("sky-view probe requires filtered RGBA16F targets");
            }
            atmosphere = new ClearSkyAtmosphere(sunElevation, 53);
            sky = new GpuAtmosphereSky(assetManager, renderManager, cam, atmosphere, higherOrders);
            reference = new AtmosphereSegmentReference(atmosphere);
            originalDisplay = ((Geometry) sky.getSpatial()).getMaterial();
            originalMesh = ((Geometry) sky.getSpatial()).getMesh();
            skyView = new GpuAtmosphereSkyView(assetManager, sky);
            rootNode.attachChild(sky.getSpatial());
            viewPort.addProcessor(skyView);
            output = new FrameBuffer(WIDTH, HEIGHT, 1);
            output.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
            output.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
            viewPort.setOutputFrameBuffer(output);
            viewPort.setBackgroundColor(ColorRGBA.Black);
            configureOrigin(0);
            mapDue = true;
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void configureOrigin(int index) {
        originIndex = index;
        cam.resize(WIDTH, HEIGHT, false);
        cam.setFrustumPerspective(45, 1, .1f, 2200);
        cam.setLocation(origins[index]);
        poses = makePoses();
        poseIndex = 0;
        aim(poses[0]);
        frames = 0;
    }

    private RayPose[] makePoses() {
        Vector3f sun = sky.getSunDirection(new Vector3f());
        SkyViewMapping mapping = new SkyViewMapping(origins[originIndex], sun);
        double horizon = mapping.getHorizonElevation();
        return new RayPose[]{
            new RayPose("zenith", direction(mapping, 0, Math.PI / 2), 45),
            new RayPose("nadir", direction(mapping, 0, -Math.PI / 2), 45),
            new RayPose("horizon-sky", direction(mapping, 0, horizon + .02), 45),
            new RayPose("horizon-ground", direction(mapping, 0, horizon - .02), 45),
            new RayPose("seam-left", mapping.direction(.00001, latitudeV(mapping, horizon + .05), null), 45),
            new RayPose("seam-right", mapping.direction(.99999, latitudeV(mapping, horizon + .05), null), 45),
            new RayPose("sun", sun, 1),
            new RayPose("near-horizon-sky", direction(mapping, 0, horizon + .0001), 45),
            new RayPose("near-horizon-ground", direction(mapping, 0, horizon - .0001), 45)
        };
    }

    @Override public void simpleUpdate(float tpf) {
        if (failure != null || skyView == null || ++frames < 3) return;
        try {
            frames = 0;
            long builds = skyView.getBuilds();
            if (expectedBuild && builds <= baselineBuilds) throw new IllegalStateException("observer/recreation did not rebuild sky LUT");
            if (expectedNoBuild && builds != baselineBuilds) throw new IllegalStateException("yaw-only camera turn rebuilt sky LUT");
            expectedBuild = expectedNoBuild = false;

            if (mapDue) {
                verifyMapping(skyView.getMapping(), origins[originIndex]);
                verifyMap();
                verifySun();
                mapDue = false;
            }
            verifyDisplay(poses[poseIndex]);
            if (originIndex == 0 && poseIndex == 0 && !recreated) {
                recreated = true;
                baselineBuilds = builds;
                skyView.recreate();
                expectedBuild = true;
                mapDue = true;
                return;
            }
            if (++poseIndex < poses.length) {
                aim(poses[poseIndex]);
                baselineBuilds = builds;
                expectedNoBuild = true;
                return;
            }
            if (++originIndex < origins.length) {
                configureOrigin(originIndex);
                baselineBuilds = builds;
                expectedBuild = true;
                mapDue = true;
                return;
            }
            if (!orthographicChecked) {
                orthographicChecked = true;
                originIndex = origins.length - 1;
                poseIndex = poses.length - 1;
                cam.setParallelProjection(true);
                cam.setFrustum(.1f, 2200, -80, 80, 80, -80);
                baselineBuilds = builds;
                expectedNoBuild = true;
                return;
            }
            finishSuccess();
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void aim(RayPose pose) {
        float fov = pose.direction.dot(sky.getSunDirection(new Vector3f())) > .9999f ? 1 : pose.fov;
        cam.setParallelProjection(false);
        cam.setFrustumPerspective(fov, 1, .1f, 2200);
        Vector3f up = Math.abs(pose.direction.dot(Vector3f.UNIT_Y)) > .99f
                ? Vector3f.UNIT_X : Vector3f.UNIT_Y;
        cam.lookAtDirection(pose.direction, up);
    }

    private void verifyMap() {
        ByteBuffer bytes = skyView.readMap();
        try {
            FloatBuffer values = bytes.asFloatBuffer();
            double radianceSum = 0;
            for (int i = 0; i < values.capacity(); i++) {
                float value = values.get(i);
                if (i % 4 != 3) radianceSum += value;
                if (!Float.isFinite(value) || value < 0) throw new IllegalStateException("invalid sky map channel=" + i + " value=" + value);
            }
            SkyViewMapping mapping = skyView.getMapping();
            Vector3f albedo = sky.getGroundAlbedo(new Vector3f());
            double localMaximum = 0;
            List<int[]> points = new ArrayList<>(Arrays.asList(TEXELS));
            Vector2f sunUv = mapping.uv(sky.getSunDirection(new Vector3f()), null);
            points.add(new int[]{Math.min(255, (int) (sunUv.x * 256)), Math.min(127, Math.round(sunUv.y * 127))});
            for (int[] xy : points) {
                double u = (xy[0] + .5) / GpuAtmosphereSkyView.WIDTH;
                double v = xy[1] / (double) (GpuAtmosphereSkyView.HEIGHT - 1);
                Vector3f ray = mapping.direction(u, v, null);
                Vector2f roundTrip = mapping.uv(ray, null);
                Vector3f reconstructed = mapping.direction(roundTrip.x, roundTrip.y, null);
                if (ray.distance(reconstructed) > 2.0e-6f) throw new IllegalStateException("sky mapping round trip failed at " + xy[0] + "," + xy[1]);
                AtmosphereSegmentReference.Result expected = evaluateSky(origins[originIndex], ray, albedo);
                int base = (xy[1] * GpuAtmosphereSkyView.WIDTH + xy[0]) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    double actual = values.get(base + channel);
                    double referenceValue = expected.radiance(channel);
                    localMaximum = Math.max(localMaximum, Math.abs(actual - referenceValue));
                    close("sky LUT transport", actual, referenceValue, .0003 + .03 * referenceValue);
                    transportSamples++;
                }
            }
            if (!(radianceSum > 1)) throw new IllegalStateException("sky LUT probe must observe positive radiance");
            maximumTransportError = Math.max(maximumTransportError, localMaximum);
            System.out.println("SKY_VIEW_TRANSPORT origin=" + originIndex + " samples=" + points.size()
                    + " maxError=" + localMaximum + " bytes=" + skyView.getEncodedBytes());
        } finally {
            BufferUtils.destroyDirectBuffer(bytes);
        }
    }

    private void verifyMapping(SkyViewMapping mapping, Vector3f origin) {
        Vector3f up = mapping.getUp(null), forward = mapping.getForward(null), side = mapping.getSide(null);
        if (Math.abs(up.length() - 1) > 2e-6 || Math.abs(forward.length() - 1) > 2e-6
                || Math.abs(side.length() - 1) > 2e-6 || Math.abs(up.dot(forward)) > 2e-6
                || Math.abs(up.dot(side)) > 2e-6 || Math.abs(forward.dot(side)) > 2e-6
                || mapping.getOrigin(null).distance(origin) != 0) {
            throw new IllegalStateException("invalid observer basis/ownership");
        }
        SkyViewMapping verticalSun = new SkyViewMapping(origin, up);
        Vector3f fallback = verticalSun.getForward(null);
        if (!Float.isFinite(fallback.x) || Math.abs(fallback.length() - 1) > 2e-6
                || Math.abs(fallback.dot(up)) > 2e-6) throw new IllegalStateException("vertical-sun basis failed");
    }

    private void verifySun() {
        SkyViewMapping mapping = skyView.getMapping();
        Vector3f up = mapping.getUp(null), sun = sky.getSunDirection(new Vector3f());
        ClearSkyAtmosphere.ModelParameters model = ClearSkyAtmosphere.modelParameters();
        double[] transmission = model.sunTransmittance(mapping.getRadiusMeters(), up.dot(sun), 192);
        ColorRGBA actual = skyView.getSolarRadiance(new ColorRGBA());
        double solarScale = model.solarIrradiance() / (2 * Math.PI * (1 - Math.cos(.004675)));
        for (int c = 0; c < 3; c++) {
            double expected = transmission[c] * solarScale;
            close("observer sun", c == 0 ? actual.r : c == 1 ? actual.g : actual.b,
                    expected, .005 * expected + .0001);
        }
    }

    private void verifyDisplay(RayPose pose) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 16).order(ByteOrder.nativeOrder());
        ByteBuffer mapBytes = skyView.readMap();
        FrameBuffer saved = renderer.getCurrentFrameBuffer();
        try {
            renderer.readFrameBufferWithFormat(output, pixels, Image.Format.RGBA32F);
            FloatBuffer outputValues = pixels.asFloatBuffer();
            FloatBuffer mapValues = mapBytes.asFloatBuffer();
            int center = ((HEIGHT / 2) * WIDTH + WIDTH / 2) * 4;
            Vector3f ray = cam.getDirection().clone().normalizeLocal();
            Vector2f uv = skyView.getMapping().uv(ray, null);
            double[] expected = sampleMap(mapValues, uv.x, uv.y);
            double sunAngle = Math.acos(Math.max(-1, Math.min(1,
                    ray.dot(sky.getSunDirection(new Vector3f())))));
            if (sunAngle < SOLAR_RADIUS * .5) {
                ColorRGBA disk = skyView.getSolarRadiance(new ColorRGBA());
                expected[0] += disk.r;
                expected[1] += disk.g;
                expected[2] += disk.b;
            } else if (sunAngle < SOLAR_RADIUS * 1.5) {
                throw new IllegalStateException("probe ray unexpectedly overlaps the disk filter edge: " + pose.name);
            }
            for (int channel = 0; channel < 3; channel++) {
                double actual = outputValues.get(center + channel);
                double error = Math.abs(actual - expected[channel]);
                maximumDisplayError = Math.max(maximumDisplayError, error);
                close("sky display " + pose.name, actual, expected[channel], .00003 + .005 * Math.abs(expected[channel]));
                if (cam.isParallelProjection()) {
                    close("orthographic corner", outputValues.get(channel), expected[channel], .00003 + .005 * Math.abs(expected[channel]));
                    close("orthographic opposite corner", outputValues.get((WIDTH * HEIGHT - 1) * 4 + channel), expected[channel], .00003 + .005 * Math.abs(expected[channel]));
                }
                displaySamples++;
            }
            close("sky display alpha", outputValues.get(center + 3), 1, .001);
            System.out.println("SKY_VIEW_DISPLAY origin=" + originIndex + " pose=" + pose.name
                    + " center=" + outputValues.get(center) + "," + outputValues.get(center + 1) + "," + outputValues.get(center + 2));
        } finally {
            renderer.setFrameBuffer(saved);
            BufferUtils.destroyDirectBuffer(pixels);
            BufferUtils.destroyDirectBuffer(mapBytes);
        }
    }

    private double[] sampleMap(FloatBuffer values, double u, double v) {
        if (v >= .5) v = Math.max(v, 64.0 / 127.0);
        else v = Math.min(v, 63.0 / 127.0);
        double x = u * GpuAtmosphereSkyView.WIDTH - .5;
        int x0 = (int) Math.floor(x), x1 = x0 + 1;
        double fx = x - x0;
        x0 = Math.floorMod(x0, GpuAtmosphereSkyView.WIDTH);
        x1 = Math.floorMod(x1, GpuAtmosphereSkyView.WIDTH);
        double y = v * (GpuAtmosphereSkyView.HEIGHT - 1);
        int y0 = (int) Math.floor(y), y1 = Math.min(y0 + 1, GpuAtmosphereSkyView.HEIGHT - 1);
        double fy = y - y0;
        double[] result = new double[3];
        for (int c = 0; c < 3; c++) {
            double a = mix(values.get((y0 * GpuAtmosphereSkyView.WIDTH + x0) * 4 + c),
                    values.get((y0 * GpuAtmosphereSkyView.WIDTH + x1) * 4 + c), fx);
            double b = mix(values.get((y1 * GpuAtmosphereSkyView.WIDTH + x0) * 4 + c),
                    values.get((y1 * GpuAtmosphereSkyView.WIDTH + x1) * 4 + c), fx);
            result[c] = mix(a, b, fy);
        }
        return result;
    }

    private AtmosphereSegmentReference.Result evaluateSky(Vector3f origin, Vector3f ray, Vector3f albedo) {
        return reference.evaluateSky(origin, ray, 192, higherOrders ? (radius, mu, rgb) -> {
            ColorRGBA psi = sky.sampleMultiple(radius, mu, new ColorRGBA());
            rgb[0] = psi.r;
            rgb[1] = psi.g;
            rgb[2] = psi.b;
        } : null, albedo);
    }

    private static Vector3f direction(SkyViewMapping mapping, double phi, double latitude) {
        double delta = latitude - mapping.getHorizonElevation();
        double halfRange = delta >= 0 ? Math.PI / 2 - mapping.getHorizonElevation()
                : Math.PI / 2 + mapping.getHorizonElevation();
        double q = Math.copySign(Math.sqrt(Math.abs(delta) / halfRange), delta);
        return mapping.direction((phi + Math.PI) / TWO_PI, .5 + .5 * q, null);
    }

    private static double latitudeV(SkyViewMapping mapping, double latitude) {
        double delta = latitude - mapping.getHorizonElevation();
        double halfRange = delta >= 0 ? Math.PI / 2 - mapping.getHorizonElevation()
                : Math.PI / 2 + mapping.getHorizonElevation();
        return .5 + .5 * Math.copySign(Math.sqrt(Math.abs(delta) / halfRange), delta);
    }

    private void finishSuccess() {
        measureBuilds();
        viewPort.removeProcessor(skyView);
        if (((Geometry) sky.getSpatial()).getMaterial() != originalDisplay
                || ((Geometry) sky.getSpatial()).getMesh() != originalMesh) {
            throw new IllegalStateException("sky display material/mesh not restored");
        }
        rootNode.detachChild(sky.getSpatial());
        sky.close(renderer);
        renderer.deleteFrameBuffer(output);
        viewPort.setOutputFrameBuffer(null);
        output = null;
        finished.countDown();
    }

    private void measureBuilds() {
        if (!renderer.getCaps().contains(Caps.GpuTimerQuery)) return;
        int query = renderer.generateProfilingTasks(1)[0];
        long[] elapsed = new long[10];
        for (int i = 0; i < elapsed.length; i++) {
            skyView.invalidate();
            renderer.startProfiling(query);
            skyView.update(cam);
            renderer.stopProfiling();
            ByteBuffer synchronizedPixels = skyView.readMap();
            BufferUtils.destroyDirectBuffer(synchronizedPixels);
            if (!renderer.isTaskResultAvailable(query) || renderer.isGpuTimingDisjoint()) {
                throw new IllegalStateException("finite isolated atlas timing unavailable/disjoint");
            }
            elapsed[i] = renderer.getProfilingTime(query);
        }
        Arrays.sort(elapsed);
        System.out.println("SKY_VIEW_BUILD_COST samples=10 gpuMedianMs=" + elapsed[5] / 1e6
                + " gpuMaxMs=" + elapsed[9] / 1e6 + " imageBytes=" + skyView.getEncodedBytes());
    }

    private static double mix(double a, double b, double t) { return a * (1 - t) + b * t; }

    private static void close(String label, double actual, double expected, double tolerance) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance) {
            throw new IllegalStateException(label + " expected=" + expected + " actual=" + actual + " tolerance=" + tolerance);
        }
    }

    private static final class RayPose {
        final String name;
        final Vector3f direction;
        final float fov;
        RayPose(String name, Vector3f direction, float fov) {
            this.name = name;
            this.direction = direction;
            this.fov = fov;
        }
    }
}
