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

import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;

/** Focused CPU checks for ClearSkyAtmosphere; run with assertions enabled. */
public final class TestClearSkyAtmosphere {
    private TestClearSkyAtmosphere() {}

    public static void main(String[] args) {
        ClearSkyAtmosphere high = new ClearSkyAtmosphere(50, 35);
        ClearSkyAtmosphere low = new ClearSkyAtmosphere(8, 35);
        ColorRGBA highSun = high.getSunIrradiance(null), lowSun = low.getSunIrradiance(null);
        check(finite(highSun) && finite(lowSun), "sun transmission finite");
        check(highSun.r > lowSun.r && highSun.g > lowSun.g && highSun.b > lowSun.b,
                "higher sun has greater transmission");

        ColorRGBA sky = high.skyRadiance(new Vector3f(0, 1, 0), null);
        check(finite(sky) && sky.b > sky.r && sky.g > sky.r && sky.b > 0,
                "zenith sky is nonzero and blue weighted");
        Vector3f a = new Vector3f(1, .2f, 0).normalizeLocal();
        Vector3f b = new Vector3f(-1, .2f, 0).normalizeLocal();
        ColorRGBA ra = high.skyRadiance(a, null), rb = high.skyRadiance(b, null);
        ColorRGBA mirrored = new ClearSkyAtmosphere(50, -35).skyRadiance(b, null);
        check(close(ra, mirrored, 1e-6f), "azimuth reflection reciprocity");
        check(finite(ra) && finite(rb), "directional sky finite");

        double maximumRelativeError = 0;
        for (float elevation : new float[]{2, 12, 55}) {
            ClearSkyAtmosphere coarse = new ClearSkyAtmosphere(elevation, 35, 24, 24);
            ClearSkyAtmosphere dense = new ClearSkyAtmosphere(elevation, 35, 96, 96);
            for (Vector3f direction : new Vector3f[]{new Vector3f(0, .002f, 1),
                    new Vector3f(0, 1, 0), new Vector3f(.6f, .1f, .7f)}) {
                ColorRGBA actual = coarse.skyRadiance(direction, null);
                ColorRGBA reference = dense.skyRadiance(direction, null);
                double relativeError = Math.max(relative(actual.r, reference.r),
                        Math.max(relative(actual.g, reference.g), relative(actual.b, reference.b)));
                maximumRelativeError = Math.max(maximumRelativeError, relativeError);
                check(relativeError < .08, "24-step radiance converges within 8% at sun " + elevation);
            }
        }
        System.out.println("ATMOSPHERE_CONVERGENCE rays=9 defaultSteps=24 referenceSteps=96 maxRelativeError="
                + maximumRelativeError);
        ColorRGBA belowHorizonSun = new ClearSkyAtmosphere(-5, 35).getSunIrradiance(null);
        check(belowHorizonSun.r == 0 && belowHorizonSun.g == 0 && belowHorizonSun.b == 0,
                "ground occults below-horizon sun");

        ColorRGBA fill = high.diffuseFill(null);
        check(finite(fill) && fill.r > 0 && fill.g > 0 && fill.b > 0, "diffuse fill is positive");

        ClearSkyAtmosphere.ModelParameters model = ClearSkyAtmosphere.modelParameters();
        double radius = model.groundRadiusMeters() + 200;
        AtmosphereMultipleScatteringReference coarseReference = new AtmosphereMultipleScatteringReference();
        AtmosphereMultipleScatteringReference.Result coarseResult =
                coarseReference.evaluate(radius, .75, .3);
        AtmosphereMultipleScatteringReference.Result noGround =
                coarseReference.evaluate(radius, .75, 0);
        AtmosphereMultipleScatteringReference.Result denseResult =
                new AtmosphereMultipleScatteringReference(128, 96).evaluate(radius, .75, .3);
        for (int channel = 0; channel < 3; channel++) {
            double psi = coarseResult.psi(channel);
            double secondOrder = coarseResult.secondOrder(channel);
            double transfer = coarseResult.transfer(channel);
            check(Double.isFinite(psi) && psi >= secondOrder && secondOrder > 0,
                    "second-order isotropic source and geometric-series result are finite");
            check(Double.isFinite(transfer) && transfer >= 0 && transfer < 1,
                    "unit scattering-transfer factor remains below one");
            check(noGround.transfer(channel) == transfer && noGround.psi(channel) < psi,
                    "ground reflection affects second-order source but not transfer");
            check(relative(psi, denseResult.psi(channel)) < .25
                    && relative(transfer, denseResult.transfer(channel)) < .08,
                    "64/32 reference converges toward 128/96 integration");
        }
        double[] exposedTransfer = coarseResult.transferRgb();
        exposedTransfer[0] = -1;
        check(coarseResult.transfer(0) >= 0, "diagnostic channel arrays are defensive copies");

        boolean rejected = false;
        try { high.skyRadiance(new Vector3f(Float.NaN, 1, 0), null); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "non-finite direction is rejected");
        System.out.println("CLEAR_SKY_ATMOSPHERE_PASS");
    }

    private static boolean finite(ColorRGBA c) {
        return Float.isFinite(c.r) && Float.isFinite(c.g) && Float.isFinite(c.b);
    }
    private static boolean close(ColorRGBA a, ColorRGBA b, float tolerance) {
        return Math.abs(a.r-b.r) < tolerance && Math.abs(a.g-b.g) < tolerance && Math.abs(a.b-b.b) < tolerance;
    }
    private static double relative(float a, float b) {
        return Math.abs(a - b) / Math.max(1.0e-8, Math.max(Math.abs(a), Math.abs(b)));
    }
    private static double relative(double a, double b) {
        return Math.abs(a - b) / Math.max(1.0e-12, Math.max(Math.abs(a), Math.abs(b)));
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
