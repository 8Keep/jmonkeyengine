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
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.material.Material;
import com.jme3.math.FastMath;
import com.jme3.math.Vector2f;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Renderer;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldSurfacePage;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.TextureArray;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Fixed-resident, immutable scenic material cache for one 256-cell heightfield.
 * It builds sixteen 64-cell pages, each with four filtered levels and a two-cell
 * gutter, then retains the complete CPU image payload for context replay.
 */
final class ScenicSurfacePages implements AutoCloseable {
    static final int SOURCE_CELLS = 256;
    static final float CANONICAL_SPACING = 0.5f;
    static final int PAGE_RESOLUTION = 64;
    static final int MIP_COUNT = 4;
    static final int PHYSICAL_SIDE = 68;
    static final int PAGE_COUNT = 16;
    static final int LAYER_COUNT = PAGE_COUNT * MIP_COUNT;

    private final Renderer renderer;
    private final TextureArray albedoPages;
    private final TextureArray normalPages;
    private final TextureArray semanticPages;
    private final Texture2D pageTable;
    private final List<ByteBuffer> ownedBuffers;
    private final long payloadBytes;
    private final long buildNanos;
    private boolean closed;

    ScenicSurfacePages(Renderer renderer, HeightfieldPage canonicalPage, int revision) {
        if (renderer == null || canonicalPage == null) {
            throw new IllegalArgumentException("renderer and canonical page are required");
        }
        if (canonicalPage.getCells() != SOURCE_CELLS
                || Float.compare(canonicalPage.getSampleSpacing(), CANONICAL_SPACING) != 0) {
            throw new IllegalArgumentException("scenic cache requires a 256-cell page at 0.5 spacing");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be nonnegative");
        }
        if (!renderer.getCaps().contains(Caps.TextureArray)
                || !renderer.getCaps().contains(Caps.FloatTexture)
                || !renderer.getCaps().contains(Caps.FloatTextureFilter)
                || !renderer.getCaps().contains(Caps.HalfFloatTexture)
                || !renderer.getCaps().contains(Caps.HalfFloatTextureFilter)) {
            throw new UnsupportedOperationException("scenic surface pages require filtered float and half-float texture arrays");
        }
        this.renderer = renderer;

        long startNanos = System.nanoTime();
        List<ByteBuffer> buffers = new ArrayList<>();
        List<Image> arrayImages = new ArrayList<>();
        TextureArray newAlbedo = null;
        TextureArray newNormal = null;
        TextureArray newSemantic = null;
        Texture2D newTable = null;
        try {
            HeightfieldSurfacePage.Revision surfaceRevision =
                    new HeightfieldSurfacePage.Revision(revision, revision, revision);
            HeightfieldSurfacePage.Source source = new ScenicSource(canonicalPage);
            for (int pageZ = 0; pageZ < 4; pageZ++) {
                for (int pageX = 0; pageX < 4; pageX++) {
                    HeightfieldSurfacePage page = new HeightfieldSurfacePage(surfaceRevision,
                            (long) pageX * PAGE_RESOLUTION, (long) pageZ * PAGE_RESOLUTION,
                            PAGE_RESOLUTION, 1, MIP_COUNT, source);
                    for (int mip = 0; mip < MIP_COUNT; mip++) {
                        Image albedo = padded(page.createAlbedoImage(mip), Image.Format.RGBA16F, 8, buffers);
                        Image normal = padded(page.createNormalImage(mip), Image.Format.RGBA32F, 16, buffers);
                        Image semantic = padded(page.createSemanticImage(mip), Image.Format.Luminance8, 1, buffers);
                        arrayImages.add(albedo);
                        arrayImages.add(normal);
                        arrayImages.add(semantic);
                    }
                }
            }

            List<Image> albedoLayers = new ArrayList<>(LAYER_COUNT);
            List<Image> normalLayers = new ArrayList<>(LAYER_COUNT);
            List<Image> semanticLayers = new ArrayList<>(LAYER_COUNT);
            for (int i = 0; i < arrayImages.size(); i += 3) {
                albedoLayers.add(arrayImages.get(i));
                normalLayers.add(arrayImages.get(i + 1));
                semanticLayers.add(arrayImages.get(i + 2));
            }
            newAlbedo = new TextureArray(albedoLayers);
            newNormal = new TextureArray(normalLayers);
            newSemantic = new TextureArray(semanticLayers);
            configureArray(newAlbedo, false);
            configureArray(newNormal, false);
            configureArray(newSemantic, true);

            ByteBuffer tableData = BufferUtils.createByteBuffer(4 * 4 * 4 * Float.BYTES)
                    .order(ByteOrder.nativeOrder());
            buffers.add(tableData);
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    int slot = z * 4 + x;
                    tableData.putFloat(x * PAGE_RESOLUTION).putFloat(z * PAGE_RESOLUTION)
                            .putFloat(1).putFloat(slot * MIP_COUNT);
                }
            }
            tableData.flip();
            newTable = new Texture2D(new Image(Image.Format.RGBA32F, 4, 4,
                    tableData, ColorSpace.Linear));
            configureTexture(newTable, true);

            this.albedoPages = newAlbedo;
            this.normalPages = newNormal;
            this.semanticPages = newSemantic;
            this.pageTable = newTable;
            this.ownedBuffers = Collections.unmodifiableList(new ArrayList<>(buffers));
            this.payloadBytes = (long) LAYER_COUNT * PHYSICAL_SIDE * PHYSICAL_SIDE * (8 + 16 + 1)
                    + 4L * 4 * 4 * Float.BYTES;
            this.buildNanos = System.nanoTime() - startNanos;
        } catch (RuntimeException | Error failure) {
            deletePartial(newAlbedo, newNormal, newSemantic, newTable, arrayImages, buffers);
            throw failure;
        }
    }

    /** Binds this complete fixed cache to the supplied material. */
    void bind(Material material) {
        ensureOpen();
        HeightfieldSurfaceBindings.bind(material, albedoPages, normalPages, semanticPages,
                pageTable, PHYSICAL_SIDE, HeightfieldSurfacePage.GUTTER, MIP_COUNT,
                new Vector2f(0, 0), SOURCE_CELLS * CANONICAL_SPACING, CANONICAL_SPACING);
    }

    /** Forces all four retained images through the renderer's normal upload path. */
    void upload() throws com.jme3.renderer.TextureUnitException {
        ensureOpen();
        renderer.setTexture(0, albedoPages);
        renderer.setTexture(1, normalPages);
        renderer.setTexture(2, semanticPages);
        renderer.setTexture(3, pageTable);
    }

    long getPayloadBytes() {
        return payloadBytes;
    }

    long getBuildNanos() {
        return buildNanos;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        renderer.deleteImage(albedoPages.getImage());
        renderer.deleteImage(normalPages.getImage());
        renderer.deleteImage(semanticPages.getImage());
        renderer.deleteImage(pageTable.getImage());
        for (ByteBuffer buffer : ownedBuffers) {
            BufferUtils.destroyDirectBuffer(buffer);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("scenic surface pages are closed");
        }
    }

    private static void configureArray(TextureArray texture, boolean categorical) {
        if (categorical) {
            configureTexture(texture, true);
        } else {
            texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            texture.setMagFilter(Texture.MagFilter.Bilinear);
            texture.setWrap(Texture.WrapMode.EdgeClamp);
            texture.setAnisotropicFilter(1);
        }
    }

    private static void configureTexture(Texture texture, boolean nearest) {
        texture.setMinFilter(nearest ? Texture.MinFilter.NearestNoMipMaps : Texture.MinFilter.BilinearNoMipMaps);
        texture.setMagFilter(nearest ? Texture.MagFilter.Nearest : Texture.MagFilter.Bilinear);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
    }

    private static Image padded(Image source, Image.Format format, int bytesPerTexel,
            List<ByteBuffer> owned) {
        int side = source.getWidth();
        if (side != source.getHeight() || side > PHYSICAL_SIDE) {
            BufferUtils.destroyDirectBuffer(source.getData(0));
            throw new IllegalArgumentException("encoded surface level has an unexpected size");
        }
        ByteBuffer input = source.getData(0).duplicate();
        ByteBuffer output = BufferUtils.createByteBuffer(PHYSICAL_SIDE * PHYSICAL_SIDE * bytesPerTexel)
                .order(ByteOrder.nativeOrder());
        owned.add(output);
        try {
            for (int z = 0; z < PHYSICAL_SIDE; z++) {
                int sourceZ = Math.min(z, side - 1);
                for (int x = 0; x < PHYSICAL_SIDE; x++) {
                    int sourceX = Math.min(x, side - 1);
                    int sourceIndex = (sourceZ * side + sourceX) * bytesPerTexel;
                    for (int b = 0; b < bytesPerTexel; b++) {
                        output.put(input.get(sourceIndex + b));
                    }
                }
            }
            output.flip();
            return new Image(format, PHYSICAL_SIDE, PHYSICAL_SIDE, output, ColorSpace.Linear);
        } finally {
            BufferUtils.destroyDirectBuffer(source.getData(0));
        }
    }

    private void deletePartial(TextureArray albedo, TextureArray normal, TextureArray semantic,
            Texture2D table, List<Image> layers, List<ByteBuffer> buffers) {
        Set<Image> unique = Collections.newSetFromMap(new IdentityHashMap<Image, Boolean>());
        if (albedo != null) unique.add(albedo.getImage());
        if (normal != null) unique.add(normal.getImage());
        if (semantic != null) unique.add(semantic.getImage());
        if (table != null) unique.add(table.getImage());
        for (Image image : unique) renderer.deleteImage(image);
        for (Image image : layers) {
            ByteBuffer data = image.getData(0);
            if (data != null) buffers.add(data);
        }
        Set<ByteBuffer> uniqueBuffers = Collections.newSetFromMap(new IdentityHashMap<ByteBuffer, Boolean>());
        uniqueBuffers.addAll(buffers);
        for (ByteBuffer buffer : uniqueBuffers) BufferUtils.destroyDirectBuffer(buffer);
    }

    /** Height-aware, deterministic surface source; noise depends only on coordinates. */
    private static final class ScenicSource implements HeightfieldSurfacePage.Source {
        private final HeightfieldPage page;

        ScenicSource(HeightfieldPage page) {
            this.page = page;
        }

        @Override
        public void sample(long x, long z, HeightfieldSurfacePage.Sample store) {
            double wx = x * (double) CANONICAL_SPACING;
            double wz = z * (double) CANONICAL_SPACING;
            // Surface texels represent canonical cell centers, not grid vertices.
            double cx = x + .5, cz = z + .5;
            float h = heightAt(cx, cz);
            float dx = (heightAt(cx + 1, cz) - heightAt(cx - 1, cz)) / (2 * CANONICAL_SPACING);
            float dz = (heightAt(cx, cz + 1) - heightAt(cx, cz - 1)) / (2 * CANONICAL_SPACING);
            float slope = FastMath.sqrt(dx * dx + dz * dz);
            float noise = smoothNoise(wx, wz);
            float detail = smoothNoise(wx * 0.37 + 19.1, wz * 0.37 - 7.3);

            // Snow settles on shelves and summits; steep faces expose rock.
            float snow = smoothstep(27f, 34f, h + (noise - .5f) * 2.0f)
                    * (1 - smoothstep(.6f, 1.25f, slope));
            float rock = smoothstep(.65f, 1.45f, slope) * (1 - snow);
            float soil = smoothstep(8f, 15f, h) * (1 - smoothstep(20f, 26f, h)) * (1 - rock);
            float grass = Math.max(0, 1 - Math.max(rock, Math.max(snow, soil * .72f)));
            float sum = grass + soil + rock + snow;
            grass /= sum;
            soil /= sum;
            rock /= sum;
            snow /= sum;

            float variation = 0.94f + 0.12f * noise + 0.035f * detail;
            store.albedoR = clamp01((grass * .20f + soil * .34f + rock * .37f + snow * .76f) * variation);
            store.albedoG = clamp01((grass * .33f + soil * .25f + rock * .35f + snow * .79f) * variation);
            store.albedoB = clamp01((grass * .12f + soil * .14f + rock * .32f + snow * .83f) * variation);
            store.semanticId = snow > .48f ? 3 : rock > .48f ? 1 : soil > .48f ? 2 : 0;

            double bumpX = (smoothNoise(wx + .11, wz) - smoothNoise(wx - .11, wz)) * .045;
            double bumpZ = (smoothNoise(wx, wz + .11) - smoothNoise(wx, wz - .11)) * .045;
            // These are tangent-space material moments. The vertex shader
            // already supplies terrain slope; adding it here would apply it twice.
            double nx = bumpX;
            double ny = 1;
            double nz = bumpZ;
            double inverseLength = 1.0 / Math.sqrt(nx * nx + ny * ny + nz * nz);
            store.normalX = (float) (nx * inverseLength);
            store.normalY = (float) (ny * inverseLength);
            store.normalZ = (float) (nz * inverseLength);
            store.roughness = clamp01(.84f - rock * .14f - snow * .20f + (detail - .5f) * .04f);
        }

        private float heightAt(double x, double z) {
            double cx = Math.max(0, Math.min(SOURCE_CELLS, x));
            double cz = Math.max(0, Math.min(SOURCE_CELLS, z));
            int x0 = (int) Math.floor(cx);
            int z0 = (int) Math.floor(cz);
            int x1 = Math.min(x0 + 1, SOURCE_CELLS);
            int z1 = Math.min(z0 + 1, SOURCE_CELLS);
            double tx = cx - x0;
            double tz = cz - z0;
            double a = page.getHeight(x0, z0) * (1 - tx) + page.getHeight(x1, z0) * tx;
            double b = page.getHeight(x0, z1) * (1 - tx) + page.getHeight(x1, z1) * tx;
            return (float) (a * (1 - tz) + b * tz);
        }
    }

    private static float smoothNoise(double x, double z) {
        long ix = (long) Math.floor(x);
        long iz = (long) Math.floor(z);
        double fx = x - ix;
        double fz = z - iz;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = hashUnit(ix, iz) * (1 - fx) + hashUnit(ix + 1, iz) * fx;
        double b = hashUnit(ix, iz + 1) * (1 - fx) + hashUnit(ix + 1, iz + 1) * fx;
        return (float) (a * (1 - fz) + b * fz);
    }

    private static double hashUnit(long x, long z) {
        long value = x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = clamp01((value - edge0) / (edge1 - edge0));
        return t * t * (3 - 2 * t);
    }

    private static float clamp01(float value) {
        return Math.max(0, Math.min(1, value));
    }
}
