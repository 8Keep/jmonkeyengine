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

import com.jme3.math.Vector3f;

/**
 * Double-precision finite-segment CPU reference for camera-to-surface transport
 * through {@link ClearSkyAtmosphere}'s broad RGB atmosphere. Coordinates are
 * local meters: the flat terrain plane y=0 is tangent to the spherical planet
 * at (0,-groundRadius,0). Radiance is in the model's relative scene units;
 * transmittance is unitless. No surface reflection is included. The transport
 * integration follows the finite Beer-Lambert formulation used by Hillaire's
 * real-time atmosphere model, with independent direct-sun transmittance.
 */
final class AtmosphereSegmentReference {
    private static final int SUN_TRANSMITTANCE_STEPS = 96;
    private static final double PI = Math.PI;

    private final ClearSkyAtmosphere.ModelParameters model;
    private final double sunX, sunY, sunZ;

    AtmosphereSegmentReference(ClearSkyAtmosphere atmosphere) {
        if (atmosphere == null) throw new IllegalArgumentException("atmosphere is required");
        model = ClearSkyAtmosphere.modelParameters();
        Vector3f sun = atmosphere.getSunDirection(new Vector3f());
        sunX = sun.x;
        sunY = sun.y;
        sunZ = sun.z;
    }

    /**
     * Integrate from a local-space origin for at most {@code distanceMeters}.
     * The path is shortened at the first planet or atmosphere boundary.
     * {@code higherOrders}, when present, supplies unit-solar-radiance Psi RGB
     * at each midpoint; it contributes isotropically as scattering*Psi.
     *
     * @param originMeters local origin in meters, inside or on the atmosphere
     * @param viewDirection finite nonzero direction toward the surface
     * @param distanceMeters requested finite nonnegative path length
     * @param segments positive number of quadratically spaced midpoint segments
     * @param higherOrders optional unit-solar Psi lookup; null disables it
     * @return immutable linear RGB radiance and transmittance
     */
    Result evaluate(Vector3f originMeters, Vector3f viewDirection, double distanceMeters,
            int segments, MultipleScatteringSource higherOrders) {
        if (!finite(originMeters) || !finite(viewDirection)
                || !(distanceMeters >= 0) || !Double.isFinite(distanceMeters)
                || segments < 1 || segments > 4096) {
            throw new IllegalArgumentException("finite vectors, nonnegative distance, and 1..4096 segments required");
        }
        double dx = viewDirection.x, dy = viewDirection.y, dz = viewDirection.z;
        double directionLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(directionLength > 0) || !Double.isFinite(directionLength)) {
            throw new IllegalArgumentException("view direction must be nonzero");
        }
        dx /= directionLength;
        dy /= directionLength;
        dz /= directionLength;

        double px = originMeters.x;
        double py = originMeters.y + model.groundRadiusMeters();
        double pz = originMeters.z;
        double radius = Math.sqrt(px * px + py * py + pz * pz);
        if (!Double.isFinite(radius) || radius < model.groundRadiusMeters()
                || radius > model.topRadiusMeters()) {
            throw new IllegalArgumentException("origin must lie within the spherical atmosphere");
        }
        if (distanceMeters == 0) return new Result(0, 0, 0, 1, 1, 1);

        double radialCosine = (px * dx + py * dy + pz * dz) / radius;
        double boundaryDistance = model.distanceToBoundary(radius, radialCosine);
        double pathLength = Math.min(distanceMeters, boundaryDistance);
        if (!(pathLength > 0)) return new Result(0, 0, 0, 1, 1, 1);

        double[] radiance = new double[3];
        double[] transmittance = {1, 1, 1};
        double[] sunTransmittance = new double[3];
        double[] psi = new double[3];
        double cosinePhase = clamp(dx * sunX + dy * sunY + dz * sunZ, -1, 1);
        double rayleighPhase = 3 * (1 + cosinePhase * cosinePhase) / (16 * PI);
        double miePhase = miePhase(cosinePhase, model.mieAsymmetry());
        double previousDistance = 0;

        for (int i = 1; i <= segments; i++) {
            double fraction = (double) i / segments;
            double currentDistance = pathLength * fraction * fraction;
            double segmentLength = currentDistance - previousDistance;
            double midpoint = previousDistance + .5 * segmentLength;
            double sampleX = px + midpoint * dx;
            double sampleY = py + midpoint * dy;
            double sampleZ = pz + midpoint * dz;
            double sampleRadius = Math.sqrt(sampleX * sampleX + sampleY * sampleY + sampleZ * sampleZ);
            double altitude = Math.max(0, sampleRadius - model.groundRadiusMeters());
            double rayleighDensity = model.rayleighDensity(altitude);
            double mieDensity = model.mieDensity(altitude);
            double sunZenithCosine = clamp((sampleX * sunX + sampleY * sunY + sampleZ * sunZ)
                    / sampleRadius, -1, 1);
            model.sunTransmittance(sampleRadius, sunZenithCosine,
                    SUN_TRANSMITTANCE_STEPS, sunTransmittance);
            if (higherOrders == null) {
                psi[0] = psi[1] = psi[2] = 0;
            } else {
                higherOrders.sample(sampleRadius, sunZenithCosine, psi);
                if (!finite(psi[0]) || !finite(psi[1]) || !finite(psi[2])) {
                    throw new IllegalArgumentException("higher-order source must return finite RGB");
                }
            }

            for (int channel = 0; channel < 3; channel++) {
                double extinction = model.extinction(channel, rayleighDensity, mieDensity);
                double scattering = model.scattering(channel, rayleighDensity, mieDensity);
                double directSource = model.rayleighScattering(channel) * rayleighDensity * rayleighPhase
                        + model.mieScattering(channel) * mieDensity * miePhase;
                double source = directSource * sunTransmittance[channel] + scattering * psi[channel];
                double integratedSegment = extinction == 0 ? segmentLength
                        : -Math.expm1(-extinction * segmentLength) / extinction;
                radiance[channel] += transmittance[channel] * source * integratedSegment;
                transmittance[channel] *= Math.exp(-extinction * segmentLength);
            }
            previousDistance = currentDistance;
        }

        double solar = model.solarIrradiance();
        return new Result(radiance[0] * solar, radiance[1] * solar, radiance[2] * solar,
                transmittance[0], transmittance[1], transmittance[2]);
    }

    private static double miePhase(double cosine, double asymmetry) {
        double g2 = asymmetry * asymmetry;
        double denominator = Math.max(1.0e-12, 1 + g2 - 2 * asymmetry * cosine);
        return 3 * (1 - g2) * (1 + cosine * cosine)
                / (8 * PI * (2 + g2) * Math.pow(denominator, 1.5));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean finite(Vector3f vector) {
        return vector != null && Float.isFinite(vector.x)
                && Float.isFinite(vector.y) && Float.isFinite(vector.z);
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    @FunctionalInterface
    interface MultipleScatteringSource {
        /** Fill {@code unitSolarRadianceStore} with unit-solar Psi RGB. */
        void sample(double radiusMeters, double sunZenithCosine, double[] unitSolarRadianceStore);
    }

    static final class Result {
        private final double radianceRed, radianceGreen, radianceBlue;
        private final double transmittanceRed, transmittanceGreen, transmittanceBlue;

        private Result(double radianceRed, double radianceGreen, double radianceBlue,
                double transmittanceRed, double transmittanceGreen, double transmittanceBlue) {
            this.radianceRed = radianceRed;
            this.radianceGreen = radianceGreen;
            this.radianceBlue = radianceBlue;
            this.transmittanceRed = transmittanceRed;
            this.transmittanceGreen = transmittanceGreen;
            this.transmittanceBlue = transmittanceBlue;
        }

        double radiance(int channel) {
            switch (channel) {
                case 0: return radianceRed;
                case 1: return radianceGreen;
                case 2: return radianceBlue;
                default: throw new IndexOutOfBoundsException("RGB channel must be in [0,2]");
            }
        }

        double transmittance(int channel) {
            switch (channel) {
                case 0: return transmittanceRed;
                case 1: return transmittanceGreen;
                case 2: return transmittanceBlue;
                default: throw new IndexOutOfBoundsException("RGB channel must be in [0,2]");
            }
        }
    }
}
