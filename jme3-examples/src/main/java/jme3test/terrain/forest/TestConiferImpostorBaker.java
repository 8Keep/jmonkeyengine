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

import com.jme3.math.FastMath;
import com.jme3.math.ColorRGBA;
import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;
import com.jme3.texture.image.ImageRaster;
import com.jme3.util.MipMapGenerator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Finite CPU fixture for semantic color/normal/depth mip generation. */
public final class TestConiferImpostorBaker {
    private static final double EPSILON = 0.012;

    private TestConiferImpostorBaker() { }

    public static void main(String[] args) {
        int[] full = ConiferImpostorBaker.mipSizes(128, 256, 4);
        check(full.length == 9 && full[full.length - 1] == 4, "full chain includes 1x1");
        long perFrame = sum(full) * 3L;
        check(perFrame * ConiferImpostorFrames.COUNT == 34_078_980L,
                "65 complete semantic mip chains use exact payload bytes");
        checkSemanticFiltering();
        checkStraightAlphaMipCoverage();
        System.out.println("CONIFER_IMPOSTOR_BAKER_CPU_PASS bytes=" + (perFrame * 65));
    }

    private static void checkSemanticFiltering() {
        int width = 4, height = 4;
        int[] colorSizes = ConiferImpostorBaker.mipSizes(width, height, 4);
        int[] normalSizes = ConiferImpostorBaker.mipSizes(width, height, 8);
        ByteBuffer color = ByteBuffer.allocate(sum(colorSizes));
        ByteBuffer normalDepth = ByteBuffer.allocate(sum(normalSizes)).order(ByteOrder.nativeOrder());

        // Four of sixteen source texels pass alpha test. Their 2x2 average
        // cells are .25, .20, .125, and 0, so the mip scaler must lift only the
        // first cell across the .42 cutoff to retain the 25% covered area.
        putSample(color, normalDepth, 0, 0, .5);
        putSample(color, normalDepth, 1, 0, .5);
        putSample(color, normalDepth, 0, 2, .8);
        putSample(color, normalDepth, 2, 0, .5);
        double coverageError = ConiferImpostorBaker.buildSemanticMips(
                color, normalDepth, width, height, colorSizes);
        check(coverageError <= .25 + 1e-12, "tiny 1x1 coverage loss is the unavoidable discrete bound");

        double alpha = unsigned(color.get(64 + 3)) / 255.0;
        check(alpha >= OriginalConiferMaterial.FROND_ALPHA_THRESHOLD,
                "first 2x2 cell preserves alpha-test coverage");
        check(unsigned(color.get(68 + 3)) / 255.0 < OriginalConiferMaterial.FROND_ALPHA_THRESHOLD,
                "next 2x2 cell remains below alpha cutoff");
        double red = srgbToLinear(unsigned(color.get(64)) / 255.0);
        check(Math.abs(red / alpha - 1.0) < EPSILON,
                "linear premultiplied albedo remains unchanged after alpha rescale");
        int np = 128; // mip level 1 starts after 16 source RGBA16F pixels
        double nx = FastMath.convertHalfToFloat(normalDepth.getShort(np));
        double ny = FastMath.convertHalfToFloat(normalDepth.getShort(np + 2));
        double nz = FastMath.convertHalfToFloat(normalDepth.getShort(np + 4));
        double depth = FastMath.convertHalfToFloat(normalDepth.getShort(np + 6));
        check(nx > 0 && ny < 0 && nz < 0, "signed object normal components survive filtering");
        check(depth < 0, "negative signed front depth survives filtering");
        check(Math.abs(nx / alpha - .2) < EPSILON
                        && Math.abs(ny / alpha + .3) < EPSILON
                        && Math.abs(nz / alpha + .9) < EPSILON
                        && Math.abs(depth / alpha + 2.0) < EPSILON,
                "normal and signed depth stay premultiplied by the shared color coverage");
    }

    private static void putSample(ByteBuffer color, ByteBuffer normalDepth,
            int x, int y, double alpha) {
        double coverage = Math.round(alpha * 255) / 255.0;
        int pixel = y * 4 + x;
        int cp = pixel * 4;
        color.put(cp, (byte) encodeSrgb((float) coverage));
        color.put(cp + 1, (byte) encodeSrgb((float) (.4 * coverage)));
        color.put(cp + 2, (byte) encodeSrgb((float) (.2 * coverage)));
        color.put(cp + 3, (byte) Math.round(coverage * 255));
        int np = pixel * 8;
        normalDepth.putShort(np, FastMath.convertFloatToHalf((float) (.2 * coverage)));
        normalDepth.putShort(np + 2, FastMath.convertFloatToHalf((float) (-.3 * coverage)));
        normalDepth.putShort(np + 4, FastMath.convertFloatToHalf((float) (-.9 * coverage)));
        normalDepth.putShort(np + 6, FastMath.convertFloatToHalf((float) (-2 * coverage)));
    }

    private static void checkStraightAlphaMipCoverage() {
        ByteBuffer data = ByteBuffer.allocate(4 * 4 * 4);
        Image image = new Image(Image.Format.RGBA8, 4, 4, data, null, ColorSpace.sRGB);
        ImageRaster base = ImageRaster.create(image, 0, 0, false);
        ColorRGBA color = new ColorRGBA(.7f, .35f, .16f, 0f);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) base.setPixel(x, y, color);
        setAlpha(base, 0, 0, .5f); setAlpha(base, 1, 0, .5f);
        setAlpha(base, 0, 2, .8f); setAlpha(base, 2, 0, .5f);
        MipMapGenerator.generateMipMaps(image, true, true);

        int[] sizes = image.getMipMapSizes().clone();
        float[][] rgb = snapshotRgb(image);
        double error = OriginalConiferMaterial.preserveAlphaTestCoverage(
                image, OriginalConiferMaterial.FROND_ALPHA_THRESHOLD);
        check(Arrays.equals(sizes, image.getMipMapSizes()), "coverage scaling keeps mip layout");
        check(error >= .25 - 1e-12 && error <= .25 + 1e-12,
                "single-texel mip reports its unavoidable 25% coverage quantization");
        check(coverageAt(image, 0) == .25 && coverageAt(image, 1) == .25,
                "representable base and 2x2 mip coverage are retained");
        checkRgbUnchanged(image, rgb);
    }

    private static void setAlpha(ImageRaster raster, int x, int y, float alpha) {
        ColorRGBA pixel = raster.getPixel(x, y, new ColorRGBA());
        pixel.a = alpha;
        raster.setPixel(x, y, pixel);
    }

    private static float[][] snapshotRgb(Image image) {
        float[][] result = new float[image.getMipMapSizes().length][];
        for (int level = 0; level < result.length; level++) {
            ImageRaster raster = ImageRaster.create(image, 0, level, false);
            float[] channels = result[level] = new float[raster.getWidth() * raster.getHeight() * 3];
            int i = 0;
            for (int y = 0; y < raster.getHeight(); y++) for (int x = 0; x < raster.getWidth(); x++) {
                ColorRGBA pixel = raster.getPixel(x, y, new ColorRGBA());
                channels[i++] = pixel.r; channels[i++] = pixel.g; channels[i++] = pixel.b;
            }
        }
        return result;
    }

    private static void checkRgbUnchanged(Image image, float[][] before) {
        for (int level = 0; level < before.length; level++) {
            ImageRaster raster = ImageRaster.create(image, 0, level, false);
            int i = 0;
            for (int y = 0; y < raster.getHeight(); y++) for (int x = 0; x < raster.getWidth(); x++) {
                ColorRGBA pixel = raster.getPixel(x, y, new ColorRGBA());
                check(Float.floatToIntBits(pixel.r) == Float.floatToIntBits(before[level][i++])
                                && Float.floatToIntBits(pixel.g) == Float.floatToIntBits(before[level][i++])
                                && Float.floatToIntBits(pixel.b) == Float.floatToIntBits(before[level][i++]),
                        "straight RGB is unchanged at mip " + level);
            }
        }
    }

    private static double coverageAt(Image image, int level) {
        ImageRaster raster = ImageRaster.create(image, 0, level, false);
        int covered = 0, total = raster.getWidth() * raster.getHeight();
        for (int y = 0; y < raster.getHeight(); y++) for (int x = 0; x < raster.getWidth(); x++) {
            if (raster.getPixel(x, y, new ColorRGBA()).a >= OriginalConiferMaterial.FROND_ALPHA_THRESHOLD) covered++;
        }
        return (double) covered / total;
    }

    private static int encodeSrgb(float linear) {
        double value = linear <= .0031308 ? 12.92 * linear
                : 1.055 * Math.pow(linear, 1 / 2.4) - .055;
        return (int) Math.round(value * 255);
    }
    private static double srgbToLinear(double value) {
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }
    private static int unsigned(byte value) { return value & 255; }
    private static int sum(int[] values) { return Arrays.stream(values).sum(); }
    private static void check(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
