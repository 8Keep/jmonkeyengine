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
package jme3test.terrain.forest;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ImageRaster;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.MipMapGenerator;

/** Material factory for the original conifer's opaque wood and alpha-tested fronds. */
public final class OriginalConiferMaterial {
    public static final String DEFINITION = "Common/MatDefs/Terrain/Forest/OriginalConifer.j3md";
    public static final String FROND_TEXTURE = "Textures/Terrain/Forest/conifer-frond-v1.png";
    public static final float FROND_ALPHA_THRESHOLD = .42f;

    private OriginalConiferMaterial() {}

    public static Material create(AssetManager assets) {
        return create(assets, new Vector3f(-.35f, -.82f, -.45f).normalizeLocal(),
                new ColorRGBA(4.08407f, 3.70708f, 3.01593f, 1f),
                new ColorRGBA(.34f, .39f, .31f, 1f));
    }

    /**
     * Create the prototype material. {@code sunColor} is linear incident
     * irradiance; the diffuse shader divides its Lambertian response by pi.
     */
    public static Material create(AssetManager assets, Vector3f sunDirection,
            ColorRGBA sunColor, ColorRGBA ambientColor) {
        Material material = new Material(assets, DEFINITION);
        TextureKey key = new TextureKey(FROND_TEXTURE, true);
        key.setGenerateMips(false);
        key.setAnisotropy(4);
        Texture texture = assets.loadTexture(key);
        Image image = texture.getImage().clone();
        image.setColorSpace(ColorSpace.sRGB);
        MipMapGenerator.generateMipMaps(image, true, true);
        preserveAlphaTestCoverage(image, FROND_ALPHA_THRESHOLD);
        texture.setImage(image);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
        texture.setMagFilter(Texture.MagFilter.Bilinear);
        texture.setMinFilter(Texture.MinFilter.Trilinear);
        material.setTexture("FrondTexture", texture);
        material.setFloat("AlphaDiscardThreshold", FROND_ALPHA_THRESHOLD);
        material.setColor("SunColor", sunColor);
        material.setColor("AmbientColor", ambientColor);
        material.setVector3("SunDirection", sunDirection.normalize());
        return material;
    }

    /**
     * Preserve base-level alpha-test coverage in each mip, changing alpha only.
     * Tiny levels can have too few texels to represent the base fraction; the
     * returned maximum error reports that discrete limit.
     */
    static double preserveAlphaTestCoverage(Image image, float threshold) {
        if (image == null || !Float.isFinite(threshold) || threshold < 0 || threshold > 1
                || image.getMipMapSizes() == null || image.getData() == null
                || image.getData().size() != 1 || !hasByteAlpha(image.getFormat())
                || !ImageRaster.isSupported(image.getFormat())) {
            throw new IllegalArgumentException("a single-slice, byte-alpha image with mip data is required");
        }
        int levels = image.getMipMapSizes().length;
        ImageRaster base = ImageRaster.create(image, 0, 0, false);
        ColorRGBA pixel = new ColorRGBA();
        int baseCount = base.getWidth() * base.getHeight();
        int baseCovered = 0;
        for (int y = 0; y < base.getHeight(); y++) for (int x = 0; x < base.getWidth(); x++) {
            int alpha = alphaByte(base.getPixel(x, y, pixel).a);
            if (alpha / 255f >= threshold) baseCovered++;
        }
        double targetCoverage = (double) baseCovered / baseCount;
        double maxError = 0;
        for (int level = 1; level < levels; level++) {
            ImageRaster raster = ImageRaster.create(image, 0, level, false);
            int width = raster.getWidth(), height = raster.getHeight(), count = width * height;
            int[] histogram = new int[256];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                histogram[alphaByte(raster.getPixel(x, y, pixel).a)]++;
            }
            double low = 0, high = 1024, bestScale = 1;
            double bestError = Math.abs(coverageAtScale(histogram, count, threshold, bestScale) - targetCoverage);
            for (int i = 0; i < 32; i++) {
                double scale = (low + high) * .5;
                double covered = coverageAtScale(histogram, count, threshold, scale);
                double error = Math.abs(covered - targetCoverage);
                if (error < bestError) {
                    bestError = error;
                    bestScale = scale;
                }
                if (covered > targetCoverage) high = scale; else low = scale;
            }
            maxError = Math.max(maxError, bestError);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                raster.getPixel(x, y, pixel);
                int alpha = alphaByte(pixel.a);
                int adjusted = (int) Math.round(Math.min(1.0, alpha / 255.0 * bestScale) * 255.0);
                pixel.a = adjusted / 255f;
                raster.setPixel(x, y, pixel);
            }
        }
        return maxError;
    }

    private static int alphaByte(float alpha) {
        return Math.max(0, Math.min(255, Math.round(alpha * 255f)));
    }

    private static double coverageAtScale(int[] histogram, int count, float threshold, double scale) {
        int covered = 0;
        for (int alpha = 0; alpha < histogram.length; alpha++) {
            int adjusted = Math.max(0, Math.min(255, (int) Math.round(alpha * scale)));
            if (adjusted / 255f >= threshold) covered += histogram[alpha];
        }
        return (double) covered / count;
    }

    private static boolean hasByteAlpha(Image.Format format) {
        return format == Image.Format.RGBA8 || format == Image.Format.ABGR8
                || format == Image.Format.ARGB8 || format == Image.Format.BGRA8;
    }
}
