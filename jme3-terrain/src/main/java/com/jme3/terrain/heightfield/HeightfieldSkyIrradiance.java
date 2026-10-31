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
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.terrain.heightfield;

import com.jme3.environment.util.EnvMapUtils;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.shader.VarType;
import com.jme3.texture.Image;
import com.jme3.texture.TextureCubeMap;
import com.jme3.texture.image.ColorSpace;
import java.util.List;
import java.util.ArrayList;
import java.nio.ByteBuffer;

/**
 * Immutable prepared spherical-harmonic diffuse irradiance from a linear
 * environment cube. The nine RGB coefficients use the engine convention from
 * {@link EnvMapUtils#getSphericalHarmonicsCoefficents(TextureCubeMap,
 * EnvMapUtils.FixSeamsMethod)} followed by
 * {@link EnvMapUtils#prepareShCoefs(Vector3f[])}; evaluation returns irradiance
 * divided by pi and clamps negative reconstruction lobes to zero.
 */
public final class HeightfieldSkyIrradiance {
    public static final int COEFFICIENT_COUNT = EnvMapUtils.NUM_SH_COEFFICIENT;
    private final Vector3f[] preparedCoefficients;

    private HeightfieldSkyIrradiance(Vector3f[] coefficients) {
        preparedCoefficients = copyAndValidate(coefficients);
    }

    /**
     * Project a CPU-backed linear cube map into prepared irradiance
     * coefficients. The cube map and its image remain caller-owned.
     * Texel-center directions use {@link EnvMapUtils.FixSeamsMethod#None}.
     */
    public static HeightfieldSkyIrradiance fromCubeMap(TextureCubeMap cubeMap) {
        if (cubeMap == null || cubeMap.getImage() == null) {
            throw new IllegalArgumentException("CPU-backed linear cube map is required");
        }
        Image image = cubeMap.getImage();
        List<ByteBuffer> faces = image.getData();
        if (image.getWidth() < 1 || image.getHeight() != image.getWidth()
                || image.getColorSpace() != ColorSpace.Linear
                || faces == null || faces.size() < 6) {
            throw new IllegalArgumentException("cube map must be square, linear, and contain six CPU faces");
        }
        for (int face = 0; face < 6; face++) {
            if (faces.get(face) == null) {
                throw new IllegalArgumentException("cube map CPU face " + face + " has no pixel data");
            }
        }
        // ImageRaster advances buffer positions while reading. Give the core
        // projector independent views, preserving the caller's replay/upload
        // cursors without copying pixels or retaining ownership of the source.
        ArrayList<ByteBuffer> views = new ArrayList<>(6);
        for (int face = 0; face < 6; face++) {
            ByteBuffer view = faces.get(face).duplicate().order(faces.get(face).order());
            view.rewind();
            views.add(view);
        }
        Image projectionImage = new Image(image.getFormat(), image.getWidth(), image.getHeight(),
                0, views, image.getColorSpace());
        if (image.getMipMapSizes() != null) projectionImage.setMipMapSizes(image.getMipMapSizes().clone());
        Vector3f[] coefficients = EnvMapUtils.getSphericalHarmonicsCoefficents(
                new TextureCubeMap(projectionImage), EnvMapUtils.FixSeamsMethod.None);
        EnvMapUtils.prepareShCoefs(coefficients);
        return new HeightfieldSkyIrradiance(coefficients);
    }

    /** Create from coefficients already processed by {@code prepareShCoefs}. */
    public static HeightfieldSkyIrradiance fromPreparedCoefficients(Vector3f[] coefficients) {
        return new HeightfieldSkyIrradiance(coefficients);
    }

    /** Return a deep copy of the prepared RGB coefficients. */
    public Vector3f[] copyPreparedCoefficients() {
        return copyAndValidate(preparedCoefficients);
    }

    /** Evaluate nonnegative diffuse irradiance divided by pi for a world normal. */
    public ColorRGBA evaluate(Vector3f worldNormal, ColorRGBA store) {
        if (worldNormal == null || !Float.isFinite(worldNormal.x)
                || !Float.isFinite(worldNormal.y) || !Float.isFinite(worldNormal.z)) {
            throw new IllegalArgumentException("world normal must be finite and nonzero");
        }
        double x = worldNormal.x, y = worldNormal.y, z = worldNormal.z;
        double length = Math.sqrt(x * x + y * y + z * z);
        if (!(length > 0)) throw new IllegalArgumentException("world normal must be finite and nonzero");
        x /= length;
        y /= length;
        z /= length;

        double[] basis = {
            1, y, z, x, y * x, y * z, 3 * z * z - 1, z * x, x * x - y * y
        };
        double red = 0, green = 0, blue = 0;
        for (int i = 0; i < COEFFICIENT_COUNT; i++) {
            Vector3f coefficient = preparedCoefficients[i];
            red += coefficient.x * basis[i];
            green += coefficient.y * basis[i];
            blue += coefficient.z * basis[i];
        }
        red = Math.max(0, red);
        green = Math.max(0, green);
        blue = Math.max(0, blue);
        if (red > Float.MAX_VALUE || green > Float.MAX_VALUE || blue > Float.MAX_VALUE) {
            throw new IllegalStateException("prepared irradiance exceeds finite ColorRGBA range");
        }
        if (store == null) store = new ColorRGBA();
        return store.set((float) red, (float) green, (float) blue, 1);
    }

    /**
     * Bind the prepared irradiance to an opted-in heightfield material.
     * Heightfield.j3md uses the image-based lighting logic's first-pass marker,
     * so this replaces artistic ambient fill without requiring an AmbientLight
     * or repeating sky energy on later additive light batches.
     */
    public void bind(Material material) {
        if (material == null) throw new IllegalArgumentException("material is required");
        material.setParam("SkyIrradianceSH", VarType.Vector3Array, copyPreparedCoefficients());
        material.setBoolean("UseHeightfieldSkyIrradiance", true);
    }

    private static Vector3f[] copyAndValidate(Vector3f[] coefficients) {
        if (coefficients == null || coefficients.length != COEFFICIENT_COUNT) {
            throw new IllegalArgumentException("exactly nine prepared SH coefficients are required");
        }
        Vector3f[] copy = new Vector3f[COEFFICIENT_COUNT];
        for (int i = 0; i < COEFFICIENT_COUNT; i++) {
            Vector3f value = coefficients[i];
            if (value == null || !Float.isFinite(value.x) || !Float.isFinite(value.y)
                    || !Float.isFinite(value.z)) {
                throw new IllegalArgumentException("prepared SH coefficient " + i + " must be finite");
            }
            copy[i] = value.clone();
        }
        return copy;
    }
}
