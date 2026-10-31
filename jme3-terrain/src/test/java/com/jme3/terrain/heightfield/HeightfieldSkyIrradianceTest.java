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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDER AND CONTRIBUTORS
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
import com.jme3.material.MaterialDef;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.shader.VarType;
import com.jme3.texture.Image;
import com.jme3.texture.TextureCubeMap;
import com.jme3.texture.image.ColorSpace;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeightfieldSkyIrradianceTest {
    private static final int FACE_SIZE = 32;

    @Test
    void constantAndLinearCubeRadianceMatchAnalyticIrradianceOverPi() {
        double[] constant = {.23, .41, .67};
        HeightfieldSkyIrradiance uniform = HeightfieldSkyIrradiance.fromCubeMap(
                cube(constant, new double[3], ColorSpace.Linear));
        assertColor(uniform.evaluate(Vector3f.UNIT_X, null), constant, 0.004);
        assertColor(uniform.evaluate(Vector3f.UNIT_Y.negate(), null), constant, 0.004);

        double[] base = {.52, .39, .31};
        double[] vertical = {.28, .19, .13};
        TextureCubeMap environment = cube(base, vertical, ColorSpace.Linear);
        HeightfieldSkyIrradiance projected = HeightfieldSkyIrradiance.fromCubeMap(environment);
        for (Vector3f normal : new Vector3f[]{Vector3f.UNIT_Y, Vector3f.UNIT_Y.negate(),
                Vector3f.UNIT_X, new Vector3f(1, 2, -3)}) {
            Vector3f unit = normal.normalize();
            double[] expected = new double[3];
            for (int channel = 0; channel < 3; channel++) {
                expected[channel] = base[channel] + (2.0 / 3.0) * vertical[channel] * unit.y;
            }
            assertColor(projected.evaluate(normal, null), expected, 0.012);
        }

        // The prepared result owns only coefficients; later source texture
        // edits cannot change an already projected irradiance field.
        FloatBuffer firstFace = environment.getImage().getData(0).asFloatBuffer();
        for (int i = 0; i < FACE_SIZE * FACE_SIZE; i++) {
            firstFace.put(i * 4, 0).put(i * 4 + 1, 0).put(i * 4 + 2, 0);
        }
        assertColor(projected.evaluate(Vector3f.UNIT_Y, null),
                new double[]{base[0] + 2.0 / 3 * vertical[0],
                    base[1] + 2.0 / 3 * vertical[1], base[2] + 2.0 / 3 * vertical[2]}, 0.012);
    }

    @Test
    void preparedCoefficientsAndMaterialBindingsAreDeepCopies() {
        Vector3f[] input = zeroCoefficients();
        input[0].set(.25f, .5f, .75f);
        input[1].set(-.2f, .1f, -.3f); // Negative SH terms are valid.
        HeightfieldSkyIrradiance sky = HeightfieldSkyIrradiance.fromPreparedCoefficients(input);
        input[0].set(9, 9, 9);
        Vector3f[] returned = sky.copyPreparedCoefficients();
        assertNotSame(input[0], returned[0]);
        assertEquals(.25f, returned[0].x, 0);
        returned[0].set(-7, -7, -7);
        assertEquals(.25f, sky.copyPreparedCoefficients()[0].x, 0);

        MaterialDef definition = new MaterialDef(null, "HeightfieldSkyIrradianceTest");
        definition.addMaterialParam(VarType.Boolean, "UseHeightfieldSkyIrradiance", false);
        definition.addMaterialParam(VarType.Vector3Array, "SkyIrradianceSH", null);
        Material material = new Material(definition);
        sky.bind(material);
        assertEquals(Boolean.TRUE, material.getParam("UseHeightfieldSkyIrradiance").getValue());
        Vector3f[] bound = (Vector3f[]) material.getParam("SkyIrradianceSH").getValue();
        assertEquals(.25f, bound[0].x, 0);
        assertNotSame(sky.copyPreparedCoefficients()[0], bound[0]);
        bound[0].set(4, 4, 4);
        assertEquals(.25f, sky.copyPreparedCoefficients()[0].x, 0);

        ColorRGBA result = new ColorRGBA();
        assertSame(result, sky.evaluate(Vector3f.UNIT_Y, result));
        assertEquals(1, result.a, 0);
        HeightfieldSkyIrradiance negative = HeightfieldSkyIrradiance.fromPreparedCoefficients(
                new Vector3f[]{new Vector3f(-1, -2, -3), new Vector3f(-1, -1, -1),
                    new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(),
                    new Vector3f(), new Vector3f(), new Vector3f()});
        ColorRGBA clamped = negative.evaluate(Vector3f.UNIT_Y, null);
        assertEquals(0, clamped.r, 0);
        assertEquals(0, clamped.g, 0);
        assertEquals(0, clamped.b, 0);
    }

    @Test
    void malformedInputsAndNonlinearOrGpuOnlyCubesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> HeightfieldSkyIrradiance.fromPreparedCoefficients(new Vector3f[8]));
        Vector3f[] missing = zeroCoefficients();
        missing[4] = null;
        assertThrows(IllegalArgumentException.class,
                () -> HeightfieldSkyIrradiance.fromPreparedCoefficients(missing));
        Vector3f[] nonfinite = zeroCoefficients();
        nonfinite[2].x = Float.POSITIVE_INFINITY;
        assertThrows(IllegalArgumentException.class,
                () -> HeightfieldSkyIrradiance.fromPreparedCoefficients(nonfinite));

        HeightfieldSkyIrradiance zero = HeightfieldSkyIrradiance.fromPreparedCoefficients(zeroCoefficients());
        assertThrows(IllegalArgumentException.class, () -> zero.evaluate(Vector3f.ZERO, null));
        assertThrows(IllegalArgumentException.class,
                () -> zero.evaluate(new Vector3f(Float.NaN, 1, 0), null));
        assertThrows(IllegalArgumentException.class,
                () -> HeightfieldSkyIrradiance.fromCubeMap(cube(new double[3], new double[3], ColorSpace.sRGB)));
        assertThrows(IllegalArgumentException.class,
                () -> HeightfieldSkyIrradiance.fromCubeMap(new TextureCubeMap(FACE_SIZE, FACE_SIZE, Image.Format.RGBA32F)));
    }

    private static TextureCubeMap cube(double[] base, double[] vertical, ColorSpace colorSpace) {
        ArrayList<ByteBuffer> faces = new ArrayList<>(6);
        Vector3f direction = new Vector3f();
        for (int face = 0; face < 6; face++) {
            ByteBuffer bytes = ByteBuffer.allocate(FACE_SIZE * FACE_SIZE * 4 * Float.BYTES)
                    .order(ByteOrder.nativeOrder());
            FloatBuffer pixels = bytes.asFloatBuffer();
            for (int y = 0; y < FACE_SIZE; y++) {
                for (int x = 0; x < FACE_SIZE; x++) {
                    EnvMapUtils.getVectorFromCubemapFaceTexCoord(x, y, FACE_SIZE, face,
                            direction, EnvMapUtils.FixSeamsMethod.None);
                    for (int channel = 0; channel < 3; channel++) {
                        pixels.put((float) (base[channel] + vertical[channel] * direction.y));
                    }
                    pixels.put(1);
                }
            }
            bytes.position(0);
            faces.add(bytes);
        }
        Image image = new Image(Image.Format.RGBA32F, FACE_SIZE, FACE_SIZE, 0, faces, colorSpace);
        return new TextureCubeMap(image);
    }

    private static Vector3f[] zeroCoefficients() {
        Vector3f[] coefficients = new Vector3f[HeightfieldSkyIrradiance.COEFFICIENT_COUNT];
        Arrays.setAll(coefficients, ignored -> new Vector3f());
        return coefficients;
    }

    private static void assertColor(ColorRGBA actual, double[] expected, double tolerance) {
        assertTrue(Float.isFinite(actual.r) && Float.isFinite(actual.g) && Float.isFinite(actual.b));
        assertEquals(expected[0], actual.r, tolerance);
        assertEquals(expected[1], actual.g, tolerance);
        assertEquals(expected[2], actual.b, tolerance);
    }
}
