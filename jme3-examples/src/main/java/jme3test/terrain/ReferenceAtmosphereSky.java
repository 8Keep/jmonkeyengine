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
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureCubeMap;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.jme3.util.SkyFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/** Fixed, single-scattering atmospheric sky reference for the terrain example. */
public final class ReferenceAtmosphereSky {
    private static final int SIZE = 64;
    private static final float SOLAR_RADIUS = 0.004675f;
    private static final float SOLAR_SOLID_ANGLE = (float) (2 * Math.PI * (1 - Math.cos(SOLAR_RADIUS)));

    private final Image image;
    private final ArrayList<ByteBuffer> cpuFaces;
    private final TextureCubeMap cubeMap;
    private final Spatial spatial;
    private final long generationMillis;
    private final int texelCount = 6 * SIZE * SIZE;
    private boolean closed;

    /** Builds the original linear HDR cube on the calling thread. */
    public ReferenceAtmosphereSky(AssetManager assetManager, ClearSkyAtmosphere atmosphere) {
        long started = System.nanoTime();
        List<ByteBuffer> faces = new ArrayList<>(6);
        Vector3f direction = new Vector3f();
        ColorRGBA radiance = new ColorRGBA();
        try {
            for (int face = 0; face < 6; face++) {
                ByteBuffer bytes = BufferUtils.createByteBuffer(SIZE * SIZE * 4 * Float.BYTES)
                        .order(ByteOrder.nativeOrder());
                faces.add(bytes);
                FloatBuffer pixels = bytes.asFloatBuffer();
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        faceDirection(face, x, y, direction);
                        atmosphere.skyRadiance(direction, radiance);
                        pixels.put(radiance.r).put(radiance.g).put(radiance.b).put(1f);
                    }
                }
                bytes.position(0);
                bytes.limit(bytes.capacity());
            }
        } catch (RuntimeException | Error failure) {
            for (ByteBuffer face : faces) BufferUtils.destroyDirectBuffer(face);
            throw failure;
        }
        cpuFaces = new ArrayList<>(faces);
        image = new Image(Image.Format.RGBA32F, SIZE, SIZE, 0, cpuFaces, ColorSpace.Linear);
        TextureCubeMap generatedCubeMap;
        Spatial generatedSpatial;
        try {
            generatedCubeMap = new TextureCubeMap(image);
            generatedCubeMap.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            generatedCubeMap.setMagFilter(Texture.MagFilter.Bilinear);
            generatedCubeMap.setWrap(Texture.WrapMode.EdgeClamp);

            generatedSpatial = SkyFactory.createSky(assetManager, generatedCubeMap, SkyFactory.EnvMapType.CubeMap);
            Geometry sky = (Geometry) generatedSpatial;
            Material material = new Material(assetManager, "MatDefs/ReferenceAtmosphereSky.j3md");
            // SkyFactory uses an interior sphere: normals point against the
            // world viewing ray. Undo that sign for physical sun/sky directions.
            material.setVector3("NormalScale", new Vector3f(-1, -1, -1));
            material.setTexture("SkyMap", generatedCubeMap);
            Vector3f sunDirection = atmosphere.getSunDirection(new Vector3f()).normalizeLocal();
            ColorRGBA irradiance = atmosphere.getSunIrradiance(new ColorRGBA());
            material.setVector3("SunDirection", sunDirection);
            material.setColor("SolarRadiance", new ColorRGBA(
                    irradiance.r / SOLAR_SOLID_ANGLE,
                    irradiance.g / SOLAR_SOLID_ANGLE,
                    irradiance.b / SOLAR_SOLID_ANGLE, 1));
            sky.setMaterial(material);
            sky.setShadowMode(RenderQueue.ShadowMode.Off);
            sky.setQueueBucket(RenderQueue.Bucket.Sky);
            material.getAdditionalRenderState().setDepthWrite(false);
            material.getAdditionalRenderState().setDepthFunc(RenderState.TestFunction.Equal);
            material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        } catch (RuntimeException | Error failure) {
            for (ByteBuffer face : cpuFaces) BufferUtils.destroyDirectBuffer(face);
            image.setData((ArrayList<ByteBuffer>) null);
            throw failure;
        }
        cubeMap = generatedCubeMap;
        spatial = generatedSpatial;
        generationMillis = (System.nanoTime() - started) / 1_000_000;
    }

    private static void faceDirection(int face, int x, int y, Vector3f store) {
        float u = (2f * (x + 0.5f) / SIZE) - 1f;
        float v = (2f * (y + 0.5f) / SIZE) - 1f;
        switch (face) {
            case 0: store.set(1, -v, -u); break;
            case 1: store.set(-1, -v, u); break;
            case 2: store.set(u, 1, v); break;
            case 3: store.set(u, -1, -v); break;
            case 4: store.set(u, -v, 1); break;
            case 5: store.set(-u, -v, -1); break;
            default: throw new IllegalArgumentException("Invalid cube face " + face);
        }
        store.normalizeLocal();
    }

    public Spatial getSpatial() { return spatial; }
    public long getEncodedBytes() { return (long) texelCount * 4 * Float.BYTES; }
    public long getGenerationMillis() { return generationMillis; }
    public int getTexelCount() { return texelCount; }

    /** Fail with a useful message before uploading RGBA32F on unsupported devices. */
    public static void requireFloatTextureFiltering(Renderer renderer) {
        if (!renderer.getCaps().contains(Caps.FloatTextureFilter)) {
            throw new UnsupportedOperationException("Reference atmosphere sky requires float texture filtering (RGBA32F)");
        }
    }

    /** Call on the render thread after queued draws have completed. */
    public void close(Renderer renderer) {
        if (closed) return;
        renderer.deleteImage(image);
        for (ByteBuffer face : cpuFaces) BufferUtils.destroyDirectBuffer(face);
        image.setData((ArrayList<ByteBuffer>) null);
        closed = true;
    }

}
