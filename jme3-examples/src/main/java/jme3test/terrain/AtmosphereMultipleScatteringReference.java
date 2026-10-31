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
package jme3test.terrain;

/**
 * Double-precision point-query reference for Hillaire et al. equations 5–10.
 * It assumes isotropic scattering for the second and later orders, uniform
 * ground albedo, a spherical non-refracting planet, and no terrain visibility.
 * The first-order source uses unit incident irradiance; therefore {@code psi}
 * is relative radiance transfer per incident irradiance (sr^-1), and callers
 * multiply it by their actual solar irradiance. The returned RGB {@code fMs}
 * factor excludes ground reflection and enters the amplification
 * {@code 1 / (1 - fMs)}.
 */
final class AtmosphereMultipleScatteringReference {
    private static final int REFERENCE_DIRECTIONS = 64;
    private static final int REFERENCE_SEGMENTS = 32;
    private static final double ISOTROPIC_PHASE = 1.0 / (4.0 * Math.PI);
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    private final ClearSkyAtmosphere.ModelParameters model = ClearSkyAtmosphere.modelParameters();
    private final int directions;
    private final int raySegments;

    AtmosphereMultipleScatteringReference() {
        this(REFERENCE_DIRECTIONS, REFERENCE_SEGMENTS);
    }

    /** Higher sample counts are intended for finite convergence references. */
    AtmosphereMultipleScatteringReference(int directions, int raySegments) {
        if ((directions != 64 && directions != 128) || (raySegments != 32 && raySegments != 96)) {
            throw new IllegalArgumentException("reference supports 64/128 directions and 32/96 ray segments");
        }
        this.directions = directions;
        this.raySegments = raySegments;
    }

    Result evaluate(double radiusMeters, double solarZenithCosine, double groundAlbedo) {
        double groundRadius = model.groundRadiusMeters();
        double topRadius = model.topRadiusMeters();
        if (!Double.isFinite(radiusMeters) || radiusMeters < groundRadius || radiusMeters > topRadius
                || !Double.isFinite(solarZenithCosine) || solarZenithCosine < -1 || solarZenithCosine > 1
                || !Double.isFinite(groundAlbedo) || groundAlbedo < 0 || groundAlbedo > 1) {
            throw new IllegalArgumentException("finite in-atmosphere radius, solar cosine, and albedo in [0,1] required");
        }

        double solarHorizontal = Math.sqrt(Math.max(0, 1 - solarZenithCosine * solarZenithCosine));
        double[] secondOrder = new double[3];
        double[] transfer = new double[3];
        double[] directSun = new double[3];
        double[] groundSun = new double[3];

        for (int i = 0; i < directions; i++) {
            // Equal-area Fibonacci sphere quadrature. The path direction points
            // from the sample toward the boundary; Eq. 5 uses its opposite.
            double y = 1.0 - 2.0 * ((i + .5) / directions);
            double radial = Math.sqrt(Math.max(0, 1 - y * y));
            double phi = i * GOLDEN_ANGLE;
            double dx = radial * Math.cos(phi);
            double dy = y;
            double dz = radial * Math.sin(phi);
            double distance = model.distanceToBoundary(radiusMeters, dy);
            boolean hitsGround = model.groundHit(radiusMeters, dy);
            if (!(distance > 0) && !hitsGround) continue;

            double tauR = 0, tauG = 0, tauB = 0;
            double l0R = 0, l0G = 0, l0B = 0;
            double fR = 0, fG = 0, fB = 0;
            double previousDistance = 0;
            double sunDotPath = dx * solarHorizontal + dy * solarZenithCosine;
            for (int segment = 1; segment <= raySegments; segment++) {
                double fraction = (double) segment / raySegments;
                double endDistance = distance * fraction * fraction;
                double segmentLength = endDistance - previousDistance;
                double sampleDistance = previousDistance + .5 * segmentLength;
                double sampleRadius = Math.sqrt(Math.max(0, radiusMeters * radiusMeters
                        + 2 * radiusMeters * dy * sampleDistance + sampleDistance * sampleDistance));
                double altitude = Math.max(0, sampleRadius - groundRadius);
                double densityR = model.rayleighDensity(altitude);
                double densityM = model.mieDensity(altitude);
                double localSunCosine = clamp((radiusMeters * solarZenithCosine
                        + sampleDistance * sunDotPath) / sampleRadius, -1, 1);
                model.sunTransmittance(sampleRadius, localSunCosine, raySegments, directSun);

                double extR = model.extinction(0, densityR, densityM);
                double extG = model.extinction(1, densityR, densityM);
                double extB = model.extinction(2, densityR, densityM);
                double attenuationR = Math.exp(-tauR);
                double attenuationG = Math.exp(-tauG);
                double attenuationB = Math.exp(-tauB);
                double segmentR = -Math.expm1(-extR * segmentLength) / extR;
                double segmentG = -Math.expm1(-extG * segmentLength) / extG;
                double segmentB = -Math.expm1(-extB * segmentLength) / extB;
                double scatterR = model.scattering(0, densityR, densityM);
                double scatterG = model.scattering(1, densityR, densityM);
                double scatterB = model.scattering(2, densityR, densityM);

                // Eq. 6: first-order in-scattering with isotropic phase and
                // unit incident irradiance. Eq. 8 transfer omits sunlight,
                // phase, and the ground boundary by definition.
                l0R += attenuationR * segmentR * scatterR * directSun[0] * ISOTROPIC_PHASE;
                l0G += attenuationG * segmentG * scatterG * directSun[1] * ISOTROPIC_PHASE;
                l0B += attenuationB * segmentB * scatterB * directSun[2] * ISOTROPIC_PHASE;
                fR += attenuationR * segmentR * scatterR;
                fG += attenuationG * segmentG * scatterG;
                fB += attenuationB * segmentB * scatterB;
                tauR += extR * segmentLength;
                tauG += extG * segmentLength;
                tauB += extB * segmentLength;
                previousDistance = endDistance;
            }

            if (hitsGround) {
                double groundCosine = clamp((radiusMeters * solarZenithCosine
                        + distance * sunDotPath) / groundRadius, -1, 1);
                model.sunTransmittance(groundRadius, groundCosine, raySegments, groundSun);
                double directGround = groundAlbedo * Math.max(groundCosine, 0) / Math.PI;
                l0R += Math.exp(-tauR) * directGround * groundSun[0];
                l0G += Math.exp(-tauG) * directGround * groundSun[1];
                l0B += Math.exp(-tauB) * directGround * groundSun[2];
            }

            // dω/(4π) for both isotropic integrations reduces to a direction mean.
            secondOrder[0] += l0R / directions;
            secondOrder[1] += l0G / directions;
            secondOrder[2] += l0B / directions;
            transfer[0] += fR / directions;
            transfer[1] += fG / directions;
            transfer[2] += fB / directions;
        }

        double[] psi = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            if (!Double.isFinite(transfer[channel]) || transfer[channel] < 0 || transfer[channel] >= 1
                    || !Double.isFinite(secondOrder[channel]) || secondOrder[channel] < 0) {
                throw new IllegalStateException("multiple scattering reference escaped its finite physical range");
            }
            psi[channel] = secondOrder[channel] / (1 - transfer[channel]);
        }
        return new Result(secondOrder, transfer, psi);
    }

    static final class Result {
        private final double[] secondOrder;
        private final double[] transfer;
        private final double[] psi;

        private Result(double[] secondOrder, double[] transfer, double[] psi) {
            this.secondOrder = secondOrder.clone();
            this.transfer = transfer.clone();
            this.psi = psi.clone();
        }

        double secondOrder(int channel) { return secondOrder[channel]; }
        double transfer(int channel) { return transfer[channel]; }
        double psi(int channel) { return psi[channel]; }
        double[] secondOrderRgb() { return secondOrder.clone(); }
        double[] transferRgb() { return transfer.clone(); }
        double[] psiRgb() { return psi.clone(); }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
