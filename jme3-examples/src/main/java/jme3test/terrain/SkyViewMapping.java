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

import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;

/** Immutable horizon-warped latitude/longitude mapping for one atmosphere observer. */
final class SkyViewMapping {
    private static final double PI = Math.PI;
    private static final double TWO_PI = 2 * Math.PI;
    private static final double TANGENT_EPSILON = 1.0e-6;

    private final double originX, originY, originZ;
    private final double radius, altitude, horizonElevation;
    private final double upX, upY, upZ;
    private final double forwardX, forwardY, forwardZ;
    private final double sideX, sideY, sideZ;

    SkyViewMapping(Vector3f localOriginMeters, Vector3f sunDirection) {
        if (!finite(localOriginMeters) || !finite(sunDirection)) {
            throw new IllegalArgumentException("finite observer and sun vectors are required");
        }
        double sunLength = length(sunDirection.x, sunDirection.y, sunDirection.z);
        if (!(sunLength > 0) || !Double.isFinite(sunLength)) {
            throw new IllegalArgumentException("sun direction must be nonzero");
        }

        ClearSkyAtmosphere.ModelParameters model = ClearSkyAtmosphere.modelParameters();
        originX = localOriginMeters.x;
        originY = localOriginMeters.y;
        originZ = localOriginMeters.z;
        double planetY = originY + model.groundRadiusMeters();
        radius = length(originX, planetY, originZ);
        if (!Double.isFinite(radius) || radius < model.groundRadiusMeters()
                || radius > model.topRadiusMeters()) {
            throw new IllegalArgumentException("observer must lie within the spherical atmosphere");
        }
        altitude = radius - model.groundRadiusMeters();
        double horizonRoot = Math.sqrt(Math.max(0, altitude * (2 * model.groundRadiusMeters() + altitude)));
        horizonElevation = -Math.atan2(horizonRoot, model.groundRadiusMeters());
        upX = originX / radius;
        upY = planetY / radius;
        upZ = originZ / radius;

        double sx = sunDirection.x / sunLength;
        double sy = sunDirection.y / sunLength;
        double sz = sunDirection.z / sunLength;
        double sunUp = sx * upX + sy * upY + sz * upZ;
        double fx = sx - sunUp * upX;
        double fy = sy - sunUp * upY;
        double fz = sz - sunUp * upZ;
        double tangentLength = length(fx, fy, fz);
        if (tangentLength <= TANGENT_EPSILON) {
            // Pick the least parallel fixed world axis, avoiding any dependence
            // on camera yaw when the sun is near the radial direction.
            double ax = Math.abs(upX), ay = Math.abs(upY), az = Math.abs(upZ);
            if (ax <= ay && ax <= az) {
                fx = 1 - upX * upX;
                fy = -upX * upY;
                fz = -upX * upZ;
            } else if (ay <= az) {
                fx = -upY * upX;
                fy = 1 - upY * upY;
                fz = -upY * upZ;
            } else {
                fx = -upZ * upX;
                fy = -upZ * upY;
                fz = 1 - upZ * upZ;
            }
            tangentLength = length(fx, fy, fz);
        }
        forwardX = fx / tangentLength;
        forwardY = fy / tangentLength;
        forwardZ = fz / tangentLength;
        sideX = upY * forwardZ - upZ * forwardY;
        sideY = upZ * forwardX - upX * forwardZ;
        sideZ = upX * forwardY - upY * forwardX;
    }

    /** Convert normalized LUT coordinates to a unit world-space ray. */
    Vector3f direction(double u, double v, Vector3f store) {
        if (!Double.isFinite(u) || !Double.isFinite(v) || u < 0 || u > 1 || v < 0 || v > 1) {
            throw new IllegalArgumentException("sky LUT coordinates must be finite and in [0,1]");
        }
        double phi = TWO_PI * u - PI;
        double q = 2 * v - 1;
        double latitude = horizonElevation + (q >= 0
                ? (PI / 2 - horizonElevation) * q * Math.abs(q)
                : (PI / 2 + horizonElevation) * q * Math.abs(q));
        double horizontal = Math.cos(latitude);
        double cosPhi = Math.cos(phi), sinPhi = Math.sin(phi);
        double x = horizontal * (cosPhi * forwardX + sinPhi * sideX) + Math.sin(latitude) * upX;
        double y = horizontal * (cosPhi * forwardY + sinPhi * sideY) + Math.sin(latitude) * upY;
        double z = horizontal * (cosPhi * forwardZ + sinPhi * sideZ) + Math.sin(latitude) * upZ;
        if (store == null) store = new Vector3f();
        return store.set((float) x, (float) y, (float) z);
    }

    /** Convert a finite nonzero world-space ray to normalized LUT coordinates. */
    Vector2f uv(Vector3f direction, Vector2f store) {
        if (!finite(direction)) throw new IllegalArgumentException("finite ray direction is required");
        double rayLength = length(direction.x, direction.y, direction.z);
        if (!(rayLength > 0) || !Double.isFinite(rayLength)) {
            throw new IllegalArgumentException("ray direction must be nonzero");
        }
        double x = direction.x / rayLength;
        double y = direction.y / rayLength;
        double z = direction.z / rayLength;
        double up = clamp(x * upX + y * upY + z * upZ, -1, 1);
        double latitude = Math.asin(up);
        double delta = latitude - horizonElevation;
        double halfRange = delta >= 0 ? PI / 2 - horizonElevation : PI / 2 + horizonElevation;
        double q = Math.copySign(Math.sqrt(Math.abs(delta) / halfRange), delta);
        double v = 0.5 + 0.5 * q;
        double forward = x * forwardX + y * forwardY + z * forwardZ;
        double side = x * sideX + y * sideY + z * sideZ;
        double phi = Math.atan2(side, forward);
        double u = (phi + PI) / TWO_PI;
        if (u >= 1) u = 0;
        if (store == null) store = new Vector2f();
        return store.set((float) u, (float) v);
    }

    Vector3f getUp(Vector3f store) { return set(store, upX, upY, upZ); }
    Vector3f getForward(Vector3f store) { return set(store, forwardX, forwardY, forwardZ); }
    Vector3f getSide(Vector3f store) { return set(store, sideX, sideY, sideZ); }
    Vector3f getOrigin(Vector3f store) { return set(store, originX, originY, originZ); }
    double getAltitudeMeters() { return altitude; }
    double getRadiusMeters() { return radius; }
    double getHorizonElevation() { return horizonElevation; }

    private static Vector3f set(Vector3f store, double x, double y, double z) {
        if (store == null) store = new Vector3f();
        return store.set((float) x, (float) y, (float) z);
    }

    private static boolean finite(Vector3f vector) {
        return vector != null && Float.isFinite(vector.x)
                && Float.isFinite(vector.y) && Float.isFinite(vector.z);
    }

    private static double length(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
