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

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.MatParamOverride;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.TextureCubeMap;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.jme3.util.SkyFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Portable fragment-pass atmosphere bake. Fixed observer, broad RGB, isotropic
 * higher-order scattering and a uniform planet ground are explicit controls,
 * not terrain extent, aerial perspective or dynamic lighting updates. Bake
 * between viewport passes, using the ordinary camera projection (not an active
 * GUI projection override or a custom scissor).
 */
final class GpuAtmosphereSky implements AtmosphereSky {
    static final int TRANSMITTANCE_WIDTH = 256, TRANSMITTANCE_HEIGHT = 64;
    static final int MULTIPLE_SIZE = 32, CUBE_SIZE = 64;
    private static final float SOLAR_RADIUS = .004675f;
    private final Thread owner = Thread.currentThread();
    private final Renderer renderer;
    private final ClearSkyAtmosphere atmosphere;
    private final ClearSkyAtmosphere.ModelParameters model = ClearSkyAtmosphere.modelParameters();
    private final ArrayList<ByteBuffer> replayBuffers = new ArrayList<>();
    private final List<Texture> textures = new ArrayList<>();
    private final Texture2D transmittance;
    private final Texture2D multipleScattering;
    private final TextureCubeMap cube;
    private final Spatial spatial;
    private final ColorRGBA sunIrradiance = new ColorRGBA();
    private final ColorRGBA diffuseFill = new ColorRGBA(0, 0, 0, 1);
    private FloatBuffer capturedTransmittance, capturedMultiple;
    private final long generationMillis;
    private final boolean higherOrders;
    private final Vector3f groundAlbedo;
    private boolean closed;

    GpuAtmosphereSky(AssetManager assets, RenderManager manager, Camera restoreCamera,
            ClearSkyAtmosphere atmosphere, boolean higherOrders) {
        this(assets, manager, restoreCamera, atmosphere, higherOrders, new Vector3f(.3f, .3f, .3f));
    }

    GpuAtmosphereSky(AssetManager assets, RenderManager manager, Camera restoreCamera,
            ClearSkyAtmosphere atmosphere, boolean higherOrders, Vector3f groundAlbedo) {
        long started = System.nanoTime();
        if (assets == null || manager == null || restoreCamera == null || atmosphere == null
                || groundAlbedo == null || !validAlbedo(groundAlbedo.x)
                || !validAlbedo(groundAlbedo.y) || !validAlbedo(groundAlbedo.z)) {
            throw new IllegalArgumentException("render owner, model and finite ground albedo in [0,1] required");
        }
        this.renderer = manager.getRenderer();
        this.atmosphere = atmosphere;
        this.higherOrders = higherOrders;
        this.groundAlbedo = groundAlbedo.clone();
        if (!renderer.getCaps().contains(Caps.FloatTextureFilter)
                || !renderer.getCaps().contains(Caps.FloatColorBufferRGBA)) {
            throw new UnsupportedOperationException("GPU atmosphere requires filtered RGBA32F render targets");
        }
        transmittance = texture(TRANSMITTANCE_WIDTH, TRANSMITTANCE_HEIGHT);
        multipleScattering = texture(MULTIPLE_SIZE, MULTIPLE_SIZE);
        cube = new TextureCubeMap(CUBE_SIZE, CUBE_SIZE, Image.Format.RGBA32F);
        configure(cube);
        textures.add(cube);
        Geometry quad = new Geometry("atmosphere-pass", new Quad(1, 1));
        Camera passCamera = new Camera(1, 1);
        FrameBuffer previousTarget = renderer.getCurrentFrameBuffer();
        Camera previousCamera = manager.getCurrentCamera() == null ? restoreCamera : manager.getCurrentCamera();
        ArrayList<FrameBuffer> targets = new ArrayList<>();
        Material previousMaterial = manager.getForcedMaterial();
        String previousTechnique = manager.getForcedTechnique();
        RenderState previousState = manager.getForcedRenderState();
        List<MatParamOverride> previousOverrides = new ArrayList<>(manager.getForcedMatParams());
        Spatial result;
        try {
            manager.setForcedMaterial(null);
            manager.setForcedTechnique(null);
            manager.setForcedRenderState(null);
            manager.getForcedMatParams().clear();
            Material transmissionPass = material(assets, "AtmosphereTransmittance", this.groundAlbedo);
            FrameBuffer target = target(transmittance, -1);
            targets.add(target);
            ByteBuffer transmission = drawRead(manager, passCamera, quad, transmissionPass, target);
            validatePixels(transmission, "transmittance", true, false);
            transmittance.getImage().setData(transmission);
            capturedTransmittance = transmission.asFloatBuffer();

            Material multiplePass = material(assets, "AtmosphereMultipleScattering", this.groundAlbedo);
            multiplePass.setTexture("TransmittanceMap", transmittance);
            target = target(multipleScattering, -1);
            targets.add(target);
            ByteBuffer multiple = drawRead(manager, passCamera, quad, multiplePass, target);
            validatePixels(multiple, "multiple scattering", false, true);
            multipleScattering.getImage().setData(multiple);
            capturedMultiple = multiple.asFloatBuffer();

            Material skyPass = material(assets, "AtmosphereSkyCube", this.groundAlbedo);
            skyPass.setTexture("TransmittanceMap", transmittance);
            skyPass.setTexture("MultipleScatteringMap", multipleScattering);
            skyPass.setFloat("ObserverHeight", (float) (model.observerAltitudeMeters() / 1000));
            skyPass.setVector3("SunDirection", atmosphere.getSunDirection(new Vector3f()));
            skyPass.setFloat("SolarIrradiance", (float) model.solarIrradiance());
            skyPass.setBoolean("MultipleScattering", higherOrders);
            ArrayList<ByteBuffer> faces = new ArrayList<>(6);
            double[] fill = new double[3];
            for (int face = 0; face < 6; face++) {
                skyPass.setInt("CubeFace", face);
                target = target(cube, face);
                targets.add(target);
                ByteBuffer pixels = drawRead(manager, passCamera, quad, skyPass, target);
                validatePixels(pixels, "sky face " + face, false, false);
                faces.add(pixels);
                integrateFill(face, pixels.asFloatBuffer(), fill);
            }
            cube.getImage().setData(faces);
            diffuseFill.set((float) fill[0], (float) fill[1], (float) fill[2], 1);
            Vector3f sun = atmosphere.getSunDirection(new Vector3f());
            ColorRGBA transmissionAtSun = sampleTransmission(model.groundRadiusMeters()
                    + model.observerAltitudeMeters(), sun.y, new ColorRGBA());
            float solar = (float) model.solarIrradiance();
            sunIrradiance.set(transmissionAtSun.r * solar, transmissionAtSun.g * solar,
                    transmissionAtSun.b * solar, 1);
            result = SkyFactory.createSky(assets, cube, SkyFactory.EnvMapType.CubeMap);
            Material display = new Material(assets, "MatDefs/ReferenceAtmosphereSky.j3md");
            display.setVector3("NormalScale", new Vector3f(-1, -1, -1));
            display.setTexture("SkyMap", cube);
            display.setVector3("SunDirection", sun);
            float solidAngle = (float) (2 * Math.PI * (1 - Math.cos(SOLAR_RADIUS)));
            display.setColor("SolarRadiance", new ColorRGBA(sunIrradiance.r / solidAngle,
                    sunIrradiance.g / solidAngle, sunIrradiance.b / solidAngle, 1));
            result.setMaterial(display);
            result.setQueueBucket(RenderQueue.Bucket.Sky);
            result.setShadowMode(RenderQueue.ShadowMode.Off);
        } catch (RuntimeException | Error failure) {
            try { releaseImages(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        } finally {
            manager.setForcedMaterial(previousMaterial);
            manager.setForcedTechnique(previousTechnique);
            manager.setForcedRenderState(previousState);
            manager.getForcedMatParams().clear();
            manager.getForcedMatParams().addAll(previousOverrides);
            renderer.setFrameBuffer(previousTarget);
            manager.setCamera(previousCamera, false,
                    previousTarget == null ? previousCamera.getWidth() : previousTarget.getWidth(),
                    previousTarget == null ? previousCamera.getHeight() : previousTarget.getHeight());
            for (FrameBuffer target : targets) renderer.deleteFrameBuffer(target);
            Mesh mesh = quad.getMesh();
            for (VertexBuffer buffer : mesh.getBufferList()) {
                renderer.deleteBuffer(buffer);
                if (buffer.getData() != null) BufferUtils.destroyDirectBuffer(buffer.getData());
            }
        }
        spatial = result;
        generationMillis = (System.nanoTime() - started) / 1_000_000;
    }

    void requireLiveRenderer(Renderer consumer) {
        checkOwner();
        if (consumer != renderer) throw new IllegalArgumentException("atmosphere requires its creating renderer");
    }

    Vector3f getSunDirection(Vector3f store) { checkOwner(); return atmosphere.getSunDirection(store); }
    Vector3f getGroundAlbedo(Vector3f store) { checkOwner(); return store.set(groundAlbedo); }

    Material createAerialMaterial(AssetManager assets) {
        return createViewMaterial(assets, "AtmosphereAerial");
    }

    Material createSkyViewMaterial(AssetManager assets) {
        return createViewMaterial(assets, "AtmosphereSkyView");
    }

    private Material createViewMaterial(AssetManager assets, String definition) {
        checkOwner();
        Material result = material(assets, definition, groundAlbedo);
        result.setTexture("TransmittanceMap", transmittance);
        result.setTexture("MultipleScatteringMap", multipleScattering);
        result.setVector3("SunDirection", atmosphere.getSunDirection(new Vector3f()));
        result.setFloat("SolarIrradiance", (float) model.solarIrradiance());
        result.setBoolean("MultipleScattering", higherOrders);
        return result;
    }

    ColorRGBA sampleMultiple(double radiusMeters, double cosineSunZenith, ColorRGBA store) {
        checkOwner();
        return sample(capturedMultiple, MULTIPLE_SIZE, MULTIPLE_SIZE,
                .5 + .5 * cosineSunZenith,
                (radiusMeters - model.groundRadiusMeters())
                        / (model.topRadiusMeters() - model.groundRadiusMeters()), store);
    }

    private static boolean validAlbedo(float x) { return Float.isFinite(x) && x >= 0 && x <= 1; }

    private Texture2D texture(int width, int height) {
        Texture2D result = new Texture2D(width, height, Image.Format.RGBA32F);
        configure(result);
        textures.add(result);
        return result;
    }
    private static void configure(Texture texture) {
        texture.getImage().setColorSpace(ColorSpace.Linear);
        texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        texture.setMagFilter(Texture.MagFilter.Bilinear);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
    }
    private FrameBuffer target(Texture texture, int face) {
        Image image = texture.getImage();
        FrameBuffer result = new FrameBuffer(image.getWidth(), image.getHeight(), 1);
        FrameBuffer.FrameBufferTextureTarget attachment = FrameBuffer.FrameBufferTarget.newTarget(texture);
        if (face >= 0) attachment.face(face);
        result.addColorTarget(attachment);
        return result;
    }
    private Material material(AssetManager assets, String definition, Vector3f groundAlbedo) {
        Material result = new Material(assets, "MatDefs/" + definition + ".j3md");
        result.setVector2("AtmosphereRadii", new Vector2f((float) (model.groundRadiusMeters() / 1000),
                (float) (model.topRadiusMeters() / 1000)));
        result.setVector4("Rayleigh", new Vector4f((float) (model.rayleighScattering(0) * 1000),
                (float) (model.rayleighScattering(1) * 1000),
                (float) (model.rayleighScattering(2) * 1000),
                (float) (model.rayleighScaleHeightMeters() / 1000)));
        result.setVector4("Mie", new Vector4f((float) (model.mieScattering(0) * 1000),
                (float) (model.mieExtinction(0) * 1000),
                (float) (model.mieScaleHeightMeters() / 1000), (float) model.mieAsymmetry()));
        if (result.getMaterialDef().getMaterialParam("GroundAlbedo") != null) {
            result.setVector3("GroundAlbedo", groundAlbedo.clone());
        }
        return result;
    }
    private ByteBuffer drawRead(RenderManager manager, Camera camera, Geometry quad,
            Material material, FrameBuffer target) {
        renderer.setFrameBuffer(target);
        manager.setCamera(camera, false, target.getWidth(), target.getHeight());
        quad.setMaterial(material);
        manager.renderGeometry(quad);
        ByteBuffer pixels = BufferUtils.createByteBuffer(target.getWidth() * target.getHeight() * 16)
                .order(ByteOrder.nativeOrder());
        replayBuffers.add(pixels);
        renderer.readFrameBufferWithFormat(target, pixels, Image.Format.RGBA32F);
        pixels.rewind();
        return pixels;
    }
    private static void validatePixels(ByteBuffer bytes, String pass, boolean transmission, boolean transfer) {
        FloatBuffer values = bytes.asFloatBuffer();
        for (int i = 0; i < values.capacity(); i++) {
            float value = values.get(i);
            if (!Float.isFinite(value) || value < 0 || transmission && value > 1
                    || transfer && i % 4 == 3 && value >= 1) {
                throw new IllegalStateException(pass + " escaped its physical range at channel " + i + ": " + value);
            }
        }
    }
    private static void integrateFill(int face, FloatBuffer pixels, double[] sum) {
        Vector3f direction = new Vector3f();
        for (int y = 0; y < CUBE_SIZE; y++) for (int x = 0; x < CUBE_SIZE; x++) {
            float u = 2f * (x + .5f) / CUBE_SIZE - 1, v = 2f * (y + .5f) / CUBE_SIZE - 1;
            faceDirection(face, u, v, direction);
            if (direction.y <= 0) continue;
            double weight = direction.y * 4.0 / (CUBE_SIZE * CUBE_SIZE * Math.PI * Math.pow(1 + u * u + v * v, 1.5));
            int index = (y * CUBE_SIZE + x) * 4;
            for (int c = 0; c < 3; c++) sum[c] += pixels.get(index + c) * weight;
        }
    }
    static void faceDirection(int face, float u, float v, Vector3f direction) {
        switch (face) {
            case 0: direction.set(1, -v, -u); break;
            case 1: direction.set(-1, -v, u); break;
            case 2: direction.set(u, 1, v); break;
            case 3: direction.set(u, -1, -v); break;
            case 4: direction.set(u, -v, 1); break;
            case 5: direction.set(-u, -v, -1); break;
            default: throw new IllegalArgumentException("cube face must be in [0,5]");
        }
        direction.normalizeLocal();
    }

    ColorRGBA sampleTransmission(double radiusMeters, double cosineZenith, ColorRGBA store) {
        checkOwner();
        if (ClearSkyAtmosphere.modelParameters().groundHit(radiusMeters, cosineZenith)) return store.set(0, 0, 0, 1);
        double r = radiusMeters / 1000, ground = model.groundRadiusMeters() / 1000;
        double top = model.topRadiusMeters() / 1000;
        double h = Math.sqrt((top - ground) * (top + ground));
        double rho = Math.sqrt(Math.max(0, (r - ground) * (r + ground)));
        double distance = -r * cosineZenith + Math.sqrt(r * r * (cosineZenith * cosineZenith - 1) + top * top);
        double u = (distance - (top - r)) / (rho + h - (top - r));
        return sample(capturedTransmittance, TRANSMITTANCE_WIDTH, TRANSMITTANCE_HEIGHT, u, rho / h, store);
    }
    ColorRGBA capturedMultiple(int x, int y, ColorRGBA store) {
        checkOwner();
        if (x < 0 || x >= MULTIPLE_SIZE || y < 0 || y >= MULTIPLE_SIZE) throw new IndexOutOfBoundsException();
        int index = (y * MULTIPLE_SIZE + x) * 4;
        return store.set(capturedMultiple.get(index), capturedMultiple.get(index + 1),
                capturedMultiple.get(index + 2), capturedMultiple.get(index + 3));
    }
    private static ColorRGBA sample(FloatBuffer values, int width, int height, double u, double v, ColorRGBA store) {
        double x = Math.max(0, Math.min(1, u)) * (width - 1), y = Math.max(0, Math.min(1, v)) * (height - 1);
        int ix = (int) x, iy = (int) y, nx = Math.min(ix + 1, width - 1), ny = Math.min(iy + 1, height - 1);
        double fx = x - ix, fy = y - iy;
        float[] channels = new float[4];
        for (int c = 0; c < 4; c++) {
            double a = values.get((iy * width + ix) * 4 + c) * (1 - fx) + values.get((iy * width + nx) * 4 + c) * fx;
            double b = values.get((ny * width + ix) * 4 + c) * (1 - fx) + values.get((ny * width + nx) * 4 + c) * fx;
            channels[c] = (float) (a * (1 - fy) + b * fy);
        }
        return store.set(channels[0], channels[1], channels[2], channels[3]);
    }
    private void checkOwner() {
        if (Thread.currentThread() != owner || closed) throw new IllegalStateException("GPU atmosphere belongs to its live render owner");
    }
    @Override public Spatial getSpatial() { checkOwner(); return spatial; }
    @Override public TextureCubeMap getSkyMap() { checkOwner(); return cube; }
    @Override public ColorRGBA getSunIrradiance(ColorRGBA store) { checkOwner(); return (store == null ? new ColorRGBA() : store).set(sunIrradiance); }
    @Override public ColorRGBA getDiffuseFill(ColorRGBA store) { checkOwner(); return (store == null ? new ColorRGBA() : store).set(diffuseFill); }
    @Override public long getEncodedBytes() { return (long) (TRANSMITTANCE_WIDTH * TRANSMITTANCE_HEIGHT + MULTIPLE_SIZE * MULTIPLE_SIZE + 6 * CUBE_SIZE * CUBE_SIZE) * 16; }
    @Override public long getGenerationMillis() { return generationMillis; }
    @Override public int getTexelCount() { return 6 * CUBE_SIZE * CUBE_SIZE; }
    @Override public void close(Renderer ownerRenderer) {
        if (closed) return;
        checkOwner();
        if (ownerRenderer != renderer) throw new IllegalArgumentException("close requires the creating renderer");
        releaseImages();
        closed = true;
    }
    private void releaseImages() {
        for (Texture texture : textures) {
            renderer.deleteImage(texture.getImage());
            texture.getImage().setData((ArrayList<ByteBuffer>) null);
        }
        for (ByteBuffer bytes : replayBuffers) BufferUtils.destroyDirectBuffer(bytes);
        replayBuffers.clear();
        capturedTransmittance = capturedMultiple = null;
    }
}
