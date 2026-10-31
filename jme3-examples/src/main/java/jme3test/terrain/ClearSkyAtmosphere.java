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

/**
 * Bounded CPU single-scattering Earth atmosphere reference. Uses broad RGB bands,
 * no ozone, no ground reflection, and no multiple scattering. Coefficients are
 * representative RGB Rayleigh scattering (5.8e-6, 13.5e-6, 33.1e-6 m^-1),
 * grey aerosol scattering/extinction, and 8 km / 1.2 km exponential scale heights.
 * Phase and transport equations follow Bruneton et al. reference functions:
 * https://ebruneton.github.io/precomputed_atmospheric_scattering/atmosphere/functions.glsl.html
 * Values are relative irradiance/radiance, not spectrally calibrated.
 */
public final class ClearSkyAtmosphere {
    private static final double GROUND_RADIUS_METERS = 6_360_000.0;
    private static final double TOP_RADIUS_METERS = 6_460_000.0;
    private static final double OBSERVER_ALTITUDE_METERS = 200.0;
    private static final double RAYLEIGH_SCALE_HEIGHT_METERS = 8_000.0;
    private static final double MIE_SCALE_HEIGHT_METERS = 1_200.0;
    private static final double MIE_ASYMMETRY = 0.8;
    // A single common scene exposure scale, applied to direct and scattered
    // solar light together. These relative units are not absolute photometry.
    private static final double SOLAR_IRRADIANCE = 4.0;
    private static final int VIEW_STEPS = 24, SUN_STEPS = 24;
    private static final double[] RAYLEIGH_SCATTERING = {5.8e-6, 1.35e-5, 3.31e-5};
    private static final double[] MIE_SCATTERING = {2.0e-5, 2.0e-5, 2.0e-5};
    private static final double[] MIE_EXTINCTION = {2.1e-5, 2.1e-5, 2.1e-5};
    private static final ModelParameters MODEL_PARAMETERS = new ModelParameters();
    private final double sx, sy, sz;
    private final int viewSteps, sunSteps;

    /** @param sunElevationDegrees elevation in [-90,90]; azimuth is clockwise from +Z toward +X. */
    public ClearSkyAtmosphere(float sunElevationDegrees, float sunAzimuthDegrees) {
        this(sunElevationDegrees, sunAzimuthDegrees, VIEW_STEPS, SUN_STEPS);
    }

    ClearSkyAtmosphere(float sunElevationDegrees, float sunAzimuthDegrees, int viewSteps, int sunSteps) {
        if (viewSteps < 8 || viewSteps > 128 || sunSteps < 8 || sunSteps > 128)
            throw new IllegalArgumentException("integration counts must be in [8,128]");
        if (!Float.isFinite(sunElevationDegrees) || sunElevationDegrees < -90 || sunElevationDegrees > 90
                || !Float.isFinite(sunAzimuthDegrees)) throw new IllegalArgumentException("invalid sun angles");
        double e = Math.toRadians(sunElevationDegrees), a = Math.toRadians(sunAzimuthDegrees);
        sx = Math.cos(e) * Math.sin(a); sy = Math.sin(e); sz = Math.cos(e) * Math.cos(a);
        this.viewSteps = viewSteps; this.sunSteps = sunSteps;
    }

    /** Unit direction from observer toward sun. */
    public Vector3f getSunDirection(Vector3f store) {
        if (store == null) store = new Vector3f();
        return store.set((float) sx, (float) sy, (float) sz);
    }


    /** Direct normal irradiance at the fixed 200 m observer, relative broad-band units. */
    public ColorRGBA getSunIrradiance(ColorRGBA store) {
        double[] t = sunTransmittance(GROUND_RADIUS_METERS + OBSERVER_ALTITUDE_METERS, sy);
        return set(store, SOLAR_IRRADIANCE * t[0], SOLAR_IRRADIANCE * t[1], SOLAR_IRRADIANCE * t[2]);
    }

    /** Linear sky radiance excluding the solar disk; rays terminating at ground are integrated to it. */
    public ColorRGBA skyRadiance(Vector3f direction, ColorRGBA store) {
        if (direction == null || !Float.isFinite(direction.x) || !Float.isFinite(direction.y)
                || !Float.isFinite(direction.z)) throw new IllegalArgumentException("direction must be finite");
        double inputX = direction.x, inputY = direction.y, inputZ = direction.z;
        double len = Math.sqrt(inputX * inputX + inputY * inputY + inputZ * inputZ);
        if (!(len > 0)) throw new IllegalArgumentException("direction must be nonzero");
        double x = direction.x / len, y = direction.y / len, z = direction.z / len, r = GROUND_RADIUS_METERS + OBSERVER_ALTITUDE_METERS;
        double distance = boundary(r, y);
        if (!(distance > 0)) return set(store, 0, 0, 0);
        double nu = clamp(x * sx + y * sy + z * sz, -1, 1);
        double pr = 3 * (1 + nu * nu) / (16 * Math.PI), pm = miePhase(nu);
        double[] tau = {0, 0, 0}, result = {0, 0, 0};
        double previousDistance = 0.0;
        for (int i = 1; i <= viewSteps; i++) {
            double fraction = (double) i / viewSteps;
            double distanceAlongRay = distance * fraction * fraction;
            double segmentLength = distanceAlongRay - previousDistance;
            double d = previousDistance + .5 * segmentLength;
            double px = d * x, py = r + d * y, pz = d * z;
            double radius = Math.sqrt(px * px + py * py + pz * pz);
            double h = Math.max(0, radius - GROUND_RADIUS_METERS), rhoR = Math.exp(-h / RAYLEIGH_SCALE_HEIGHT_METERS), rhoM = Math.exp(-h / MIE_SCALE_HEIGHT_METERS);
            double[] sun = sunTransmittance(radius, clamp((px * sx + py * sy + pz * sz) / radius, -1, 1));
            for (int c = 0; c < 3; c++) {
                double ext = extinction(c, rhoR, rhoM);
                double source = RAYLEIGH_SCATTERING[c] * rhoR * pr + MIE_SCATTERING[c] * rhoM * pm;
                double integratedView = Math.exp(-tau[c]) * -Math.expm1(-ext * segmentLength) / ext;
                result[c] += sun[c] * source * integratedView;
                tau[c] += ext * segmentLength;
            }
            previousDistance = distanceAlongRay;
        }
        return set(store, SOLAR_IRRADIANCE * result[0], SOLAR_IRRADIANCE * result[1], SOLAR_IRRADIANCE * result[2]);
    }

    /** Cosine-weighted upper-hemisphere irradiance / PI for ambient diffuse fill. */
    public ColorRGBA diffuseFill(ColorRGBA store) {
        final int elevations = 8, azimuths = 16;
        double[] sum = {0, 0, 0};
        Vector3f d = new Vector3f(); ColorRGBA radiance = new ColorRGBA();
        double dMu = 1.0 / elevations, dPhi = 2 * Math.PI / azimuths;
        for (int iy = 0; iy < elevations; iy++) {
            double mu = (iy + .5) * dMu, h = Math.sqrt(1 - mu * mu);
            for (int ix = 0; ix < azimuths; ix++) {
                double phi = (ix + .5) * dPhi;
                d.set((float) (h * Math.cos(phi)), (float) mu, (float) (h * Math.sin(phi)));
                skyRadiance(d, radiance);
                double w = mu * dMu * dPhi / Math.PI;
                sum[0] += radiance.r * w; sum[1] += radiance.g * w; sum[2] += radiance.b * w;
            }
        }
        return set(store, sum[0], sum[1], sum[2]);
    }

    private static double boundary(double r, double mu) {
        if (groundHit(r, mu)) {
            double q = r * r * (mu * mu - 1) + GROUND_RADIUS_METERS * GROUND_RADIUS_METERS;
            return Math.max(0, -r * mu - Math.sqrt(Math.max(0, q)));
        }
        double q = r * r * (mu * mu - 1) + TOP_RADIUS_METERS * TOP_RADIUS_METERS;
        return Math.max(0, -r * mu + Math.sqrt(Math.max(0, q)));
    }
    private static boolean groundHit(double r, double mu) {
        return mu < 0 && r * r * (mu * mu - 1) + GROUND_RADIUS_METERS * GROUND_RADIUS_METERS >= 0;
    }

    /** Beer-Lambert transmittance with trapezoidal density per finite segment. */
    private double[] sunTransmittance(double r, double mu) {
        return MODEL_PARAMETERS.sunTransmittance(r, mu, sunSteps);
    }
    private static double extinction(int channel, double rayleighDensity, double mieDensity) {
        return RAYLEIGH_SCATTERING[channel] * rayleighDensity + MIE_EXTINCTION[channel] * mieDensity;
    }

    /** Immutable scalar access to the model used by CPU references and shader hosts. */
    static ModelParameters modelParameters() {
        return MODEL_PARAMETERS;
    }

    static final class ModelParameters {
        private ModelParameters() { }

        double groundRadiusMeters() { return GROUND_RADIUS_METERS; }
        double topRadiusMeters() { return TOP_RADIUS_METERS; }
        double observerAltitudeMeters() { return OBSERVER_ALTITUDE_METERS; }
        double rayleighScaleHeightMeters() { return RAYLEIGH_SCALE_HEIGHT_METERS; }
        double mieScaleHeightMeters() { return MIE_SCALE_HEIGHT_METERS; }
        double mieAsymmetry() { return MIE_ASYMMETRY; }
        double solarIrradiance() { return SOLAR_IRRADIANCE; }
        double rayleighScattering(int channel) { return RAYLEIGH_SCATTERING[channel]; }
        double mieScattering(int channel) { return MIE_SCATTERING[channel]; }
        double mieExtinction(int channel) { return MIE_EXTINCTION[channel]; }
        double rayleighDensity(double altitudeMeters) {
            return Math.exp(-Math.max(0, altitudeMeters) / RAYLEIGH_SCALE_HEIGHT_METERS);
        }
        double mieDensity(double altitudeMeters) {
            return Math.exp(-Math.max(0, altitudeMeters) / MIE_SCALE_HEIGHT_METERS);
        }
        double extinction(int channel, double rayleighDensity, double mieDensity) {
            return ClearSkyAtmosphere.extinction(channel, rayleighDensity, mieDensity);
        }
        double scattering(int channel, double rayleighDensity, double mieDensity) {
            return RAYLEIGH_SCATTERING[channel] * rayleighDensity
                    + MIE_SCATTERING[channel] * mieDensity;
        }
        boolean groundHit(double radius, double cosineZenith) {
            return ClearSkyAtmosphere.groundHit(radius, cosineZenith);
        }
        double distanceToBoundary(double radius, double cosineZenith) {
            return ClearSkyAtmosphere.boundary(radius, cosineZenith);
        }

        /** Fill caller storage with RGB Beer-Lambert transmission; no model arrays escape. */
        void sunTransmittance(double radius, double cosineZenith, int steps, double[] store) {
            if (steps < 1 || store == null || store.length < 3) {
                throw new IllegalArgumentException("positive integration steps and three-channel storage required");
            }
            if (groundHit(radius, cosineZenith)) {
                store[0] = store[1] = store[2] = 0;
                return;
            }
            double disc = radius * radius * (cosineZenith * cosineZenith - 1)
                    + TOP_RADIUS_METERS * TOP_RADIUS_METERS;
            double length = Math.max(0, -radius * cosineZenith + Math.sqrt(Math.max(0, disc)));
            double h0 = Math.max(0, radius - GROUND_RADIUS_METERS);
            double rr0 = Math.exp(-h0 / RAYLEIGH_SCALE_HEIGHT_METERS);
            double rm0 = Math.exp(-h0 / MIE_SCALE_HEIGHT_METERS);
            double tauR = 0, tauG = 0, tauB = 0;
            double previousDistance = 0.0;
            for (int i = 1; i <= steps; i++) {
                double fraction = (double) i / steps;
                double d = length * fraction * fraction;
                double segmentLength = d - previousDistance;
                double sampleRadius = Math.sqrt(Math.max(0, radius * radius
                        + 2 * radius * cosineZenith * d + d * d));
                double h = Math.max(0, sampleRadius - GROUND_RADIUS_METERS);
                double rr = Math.exp(-h / RAYLEIGH_SCALE_HEIGHT_METERS);
                double rm = Math.exp(-h / MIE_SCALE_HEIGHT_METERS);
                double avgR = .5 * (rr0 + rr), avgM = .5 * (rm0 + rm);
                tauR += extinction(0, avgR, avgM) * segmentLength;
                tauG += extinction(1, avgR, avgM) * segmentLength;
                tauB += extinction(2, avgR, avgM) * segmentLength;
                rr0 = rr;
                rm0 = rm;
                previousDistance = d;
            }
            store[0] = Math.exp(-tauR);
            store[1] = Math.exp(-tauG);
            store[2] = Math.exp(-tauB);
        }

        double[] sunTransmittance(double radius, double cosineZenith, int steps) {
            double[] result = new double[3];
            sunTransmittance(radius, cosineZenith, steps, result);
            return result;
        }
    }
    /** Normalized Cornette-Shanks aerosol phase function. */
    private static double miePhase(double nu) {
        double g2 = MIE_ASYMMETRY * MIE_ASYMMETRY, d = Math.max(1e-12, 1 + g2 - 2 * MIE_ASYMMETRY * nu);
        return 3 * (1 - g2) * (1 + nu * nu) / (8 * Math.PI * (2 + g2) * Math.pow(d, 1.5));
    }
    private static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }
    private static ColorRGBA set(ColorRGBA out, double r, double g, double b) {
        if (!Double.isFinite(r) || !Double.isFinite(g) || !Double.isFinite(b))
            throw new IllegalStateException("non-finite atmosphere result");
        if (out == null) out = new ColorRGBA();
        return out.set((float) r, (float) g, (float) b, 1);
    }
}
