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
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.system.AppSettings;
import com.jme3.terrain.heightfield.HeightfieldGrid;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldSkyIrradiance;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureArray;
import com.jme3.texture.TextureCubeMap;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Actual HDR checks for normalized SH diffuse light and additive-pass ownership. */
public final class TestHeightfieldSkyIrradiance extends SimpleApplication {
    private static final int SIZE = 65, CELLS = 8;
    private static final Vector3f BASE = new Vector3f(.52f, .39f, .31f);
    private static final Vector3f BAND = new Vector3f(.28f, .19f, .13f);
    private static final Vector3f ENV_DIRECTION = new Vector3f(.2f, .7f, -.3f).normalizeLocal();
    private static final Vector3f[] NORMALS = {Vector3f.UNIT_Y, new Vector3f(0, .6f, .8f),
            new Vector3f(.8f, .6f, 0), new Vector3f(0, -1, 0)};
    private final CountDownLatch finished = new CountDownLatch(1);
    private final List<DirectionalLight> extraLights = new ArrayList<>();
    private volatile Throwable failure;
    private FrameBuffer target;
    private TextureArray heights;
    private Material material;
    private Node pivot;
    private AmbientLight carrier;
    private HeightfieldSkyIrradiance constantSky, linearSky;
    private final ColorRGBA legacy = new ColorRGBA();
    private final ColorRGBA linear = new ColorRGBA();
    private final ColorRGBA direct = new ColorRGBA();
    private int index, frames;

    public static void main(String[] args) throws Exception {
        TestHeightfieldSkyIrradiance app = new TestHeightfieldSkyIrradiance();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length == 0 ? AppSettings.ANGLE_GLES3 : args[0]);
        settings.setResolution(SIZE, SIZE);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(false);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (!app.finished.await(60, TimeUnit.SECONDS)) {
            app.stop(false);
            throw new IllegalStateException("sky irradiance native probe timed out");
        }
        app.stop(true);
        if (app.failure != null) throw new IllegalStateException("sky irradiance native probe failed", app.failure);
        System.out.println("HEIGHTFIELD_SKY_IRRADIANCE_PASS normals=4 states=20 nonzeroDirectionalLights=5 batchLimit=1");
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
            HeightfieldGrid.requireSupported(renderer);
            renderManager.setSinglePassLightBatchSize(1);
            HeightfieldPage page = new HeightfieldPage(CELLS, 1, new float[(CELLS + 3) * (CELLS + 3)]);
            heights = new TextureArray(Collections.singletonList(page.createImage()));
            heights.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            heights.setMagFilter(Texture.MagFilter.Nearest);
            heights.setWrap(Texture.WrapMode.EdgeClamp);
            Geometry plane = new Geometry("sky-normal-probe", new HeightfieldGrid(CELLS).createPatchMesh(page));
            plane.setLocalTranslation(-CELLS / 2f, 0, -CELLS / 2f);
            material = new Material(assetManager, "Common/MatDefs/Terrain/Heightfield/Heightfield.j3md");
            material.setTexture("HeightPages", heights);
            material.setFloat("SampleSpacing", 1);
            material.setColor("GrassColor", ColorRGBA.White);
            material.setColor("RockColor", ColorRGBA.White);
            material.setColor("SnowColor", ColorRGBA.White);
            plane.setMaterial(material);
            plane.setQueueBucket(RenderQueue.Bucket.Opaque);
            pivot = new Node("normal-pivot");
            pivot.attachChild(plane);
            rootNode.attachChild(pivot);
            carrier = new AmbientLight();
            carrier.setColor(ColorRGBA.White);
            rootNode.addLight(carrier);
            for (int i = 0; i < 5; i++) {
                DirectionalLight light = new DirectionalLight();
                light.setDirection(new Vector3f(-.55f, -1, -.42f).normalizeLocal());
                light.setColor(new ColorRGBA(.09f + i * .01f, .05f + i * .01f, .07f + i * .02f, 1));
                extraLights.add(light);
            }
            constantSky = HeightfieldSkyIrradiance.fromCubeMap(environment(false));
            linearSky = HeightfieldSkyIrradiance.fromCubeMap(environment(true));
            target = new FrameBuffer(SIZE, SIZE, 1);
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
            target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
            viewPort.setOutputFrameBuffer(target);
            applyState();
        } catch (Throwable error) { fail(error); }
    }

    private void applyState() {
        Vector3f normal = NORMALS[index / 5];
        Quaternion rotation;
        if (normal.y < -.99f) rotation = new Quaternion().fromAngleAxis((float) Math.PI, Vector3f.UNIT_X);
        else if (normal.y > .99f) rotation = new Quaternion();
        else rotation = new Quaternion().fromAngleAxis((float) Math.acos(normal.y), Vector3f.UNIT_Y.cross(normal).normalizeLocal());
        pivot.setLocalRotation(rotation);
        cam.setParallelProjection(true);
        cam.setFrustum(.1f, 100, -5, 5, 5, -5);
        cam.setLocation(normal.mult(8));
        cam.lookAt(Vector3f.ZERO, Math.abs(normal.y) > .99f ? Vector3f.UNIT_Z : Vector3f.UNIT_Y);
        for (DirectionalLight light : extraLights) rootNode.removeLight(light);
        int state = index % 5;
        if (state == 0 || state == 3) material.setBoolean("UseHeightfieldSkyIrradiance", false);
        else if (state == 1) constantSky.bind(material);
        else linearSky.bind(material);
        rootNode.removeLight(carrier);
        if (state == 0) rootNode.addLight(carrier);
        if (state >= 3) for (DirectionalLight light : extraLights) rootNode.addLight(light);
        frames = 0;
    }
    @Override public void simpleUpdate(float tpf) {
        if (failure != null || target == null || ++frames < 3) return;
        try {
            ColorRGBA actual = readCenter();
            int state = index % 5;
            Vector3f normal = NORMALS[index / 5];
            if (state == 0) {
                if (!Float.isFinite(actual.r) || actual.r < .02f || actual.g < .02f || actual.b < .02f) {
                    throw new IllegalStateException("probe must observe a lit terrain surface");
                }
                legacy.set(actual);
            } else if (state == 3) {
                direct.set(actual);
            } else {
                float facing = .32f + .68f * Math.max(0, Math.min(1, normal.y * .5f + .5f));
                float directional = state == 1 ? 0 : (2f / 3f) * normal.dot(ENV_DIRECTION);
                ColorRGBA expected = state == 4 ? linear.add(direct) : new ColorRGBA(
                        legacy.r / facing * (BASE.x + BAND.x * directional),
                        legacy.g / facing * (BASE.y + BAND.y * directional),
                        legacy.b / facing * (BASE.z + BAND.z * directional), 1);
                expected.a = 1;
                compare(actual, expected);
                if (state == 2) linear.set(actual);
                System.out.println("SKY_IRRADIANCE_HDR normal=" + normal + " state=" + state
                        + " expected=" + expected + " actual=" + actual);
            }
            if (++index < 20) applyState();
            else {
                rootNode.detachChild(pivot);
                renderer.deleteImage(heights.getImage());
                for (ByteBuffer data : heights.getImage().getData()) BufferUtils.destroyDirectBuffer(data);
                heights.getImage().setData((ArrayList<ByteBuffer>) null);
                renderer.deleteFrameBuffer(target);
                viewPort.setOutputFrameBuffer(null);
                target = null;
                finished.countDown();
            }
        } catch (Throwable error) { fail(error); }
    }
    private ColorRGBA readCenter() {
        ByteBuffer bytes = BufferUtils.createByteBuffer(SIZE * SIZE * 16).order(ByteOrder.nativeOrder());
        FrameBuffer prior = renderer.getCurrentFrameBuffer();
        try {
            renderer.readFrameBufferWithFormat(target, bytes, Image.Format.RGBA32F);
            FloatBuffer values = bytes.asFloatBuffer();
            int center = ((SIZE / 2) * SIZE + SIZE / 2) * 4;
            return new ColorRGBA(values.get(center), values.get(center + 1), values.get(center + 2), 1);
        } finally { renderer.setFrameBuffer(prior); BufferUtils.destroyDirectBuffer(bytes); }
    }
    private void compare(ColorRGBA actual, ColorRGBA expected) {
        float[] a = {actual.r, actual.g, actual.b}, e = {expected.r, expected.g, expected.b};
        for (int c = 0; c < 3; c++) {
            float tolerance = .0003f + .004f * Math.abs(e[c]);
            if (!Float.isFinite(a[c]) || Math.abs(a[c] - e[c]) > tolerance) {
                throw new IllegalStateException("normal/state " + index + " channel " + c
                        + " expected=" + e[c] + " actual=" + a[c] + " tolerance=" + tolerance);
            }
        }
    }
    private static TextureCubeMap environment(boolean gradient) {
        int side = 32;
        ArrayList<ByteBuffer> faces = new ArrayList<>(6);
        Vector3f direction = new Vector3f();
        for (int face = 0; face < 6; face++) {
            ByteBuffer bytes = ByteBuffer.allocate(side * side * 16).order(ByteOrder.nativeOrder());
            FloatBuffer data = bytes.asFloatBuffer();
            for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) {
                GpuAtmosphereSky.faceDirection(face, 2f * (x + .5f) / side - 1, 2f * (y + .5f) / side - 1, direction);
                float value = gradient ? direction.dot(ENV_DIRECTION) : 0;
                data.put(BASE.x + BAND.x * value).put(BASE.y + BAND.y * value).put(BASE.z + BAND.z * value).put(1);
            }
            faces.add(bytes);
        }
        return new TextureCubeMap(new Image(Image.Format.RGBA32F, side, side, 0, faces, ColorSpace.Linear));
    }
}
