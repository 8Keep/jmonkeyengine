/*
 * Copyright (c) 2009-2024 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

/**
 * Deterministic, asset-free synthetic alpine terrain for the terrain examples.
 * Coordinates are world metres over an approximately 1024 by 1024 metre area.
 * This is a scenic height function, not an erosion or hydrology simulation.
 */
final class ScenicHeightField {

    private ScenicHeightField() {
    }

    /**
     * Samples a continuous height in metres at world-space x/z coordinates.
     * The foreground (small z) opens into a lower valley, with broad, winding
     * mountain ridges rising behind it. Fine deterministic detail keeps even
     * metre-spaced samples from looking mathematically smooth.
     */
    static float height(double x, double z) {
        // Two smooth warp bands bend the long ridges and break up their edges.
        double wx = x + 22.0 * noise(x * 0.0046, z * 0.0041)
                + 5.0 * noise(x * 0.020, z * 0.017);
        double wz = z + 24.0 * noise((x + 173.0) * 0.0040, (z - 91.0) * 0.0048)
                + 4.5 * noise((x - 37.0) * 0.019, (z + 211.0) * 0.016);

        double firstLine = 414.0 + 65.0 * Math.sin(wx * 0.0057 + 0.35)
                + 24.0 * Math.sin(wx * 0.014 + 1.7);
        double secondLine = 704.0 + 77.0 * Math.sin(wx * 0.0042 + 2.15)
                + 27.0 * Math.sin(wx * 0.0125 + 0.8);
        double farLine = 947.0 + 54.0 * Math.sin(wx * 0.0050 + 1.1);

        double first = ridge(wz - firstLine, 112.0,
                254.0 * (0.93 + 0.12 * noise(wx * 0.006, wz * 0.006)));
        double second = ridge(wz - secondLine, 128.0,
                184.0 * (0.94 + 0.13 * noise((wx + 83.0) * 0.005, wz * 0.005)));
        double far = ridge(wz - farLine, 105.0,
                116.0 * (0.92 + 0.14 * noise(wx * 0.006, (wz + 49.0) * 0.006)));

        // Rounded summit groups add a few high points without cutting the
        // silhouettes into triangular peaks.
        double summitA = 31.0 * gaussian(wx - 318.0, wz - 431.0, 82.0, 67.0);
        double summitB = 38.0 * gaussian(wx - 762.0, wz - 387.0, 94.0, 74.0);
        double summitC = 28.0 * gaussian(wx - 583.0, wz - 735.0, 101.0, 79.0);

        double base = 19.0 + 4.5 * noise(wx * 0.007, wz * 0.007)
                + 1.8 * noise(wx * 0.025, wz * 0.023);

        // Nested smooth value-noise bands provide broad shoulders through
        // metre-scale rocky breakup while remaining continuous between samples.
        double detail = 3.0 * noise(wx * 0.030, wz * 0.030)
                + 1.45 * noise((wx + 19.0) * 0.078, (wz - 7.0) * 0.078)
                + 0.66 * noise(wx * 0.19, (wz + 13.0) * 0.19)
                + 0.31 * noise((wx - 5.0) * 0.43, (wz + 31.0) * 0.43);

        return (float) (base + first + second + far + summitA + summitB + summitC + detail);
    }

    private static double ridge(double distance, double width, double amplitude) {
        double normalized = distance / width;
        return amplitude * Math.exp(-0.5 * normalized * normalized);
    }

    private static double gaussian(double x, double z, double widthX, double widthZ) {
        double nx = x / widthX;
        double nz = z / widthZ;
        return Math.exp(-0.5 * (nx * nx + nz * nz));
    }

    /** Smooth, deterministic value noise in approximately [-1, 1]. */
    private static double noise(double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double tx = fade(x - x0);
        double tz = fade(z - z0);

        double a = value(x0, z0);
        double b = value(x0 + 1, z0);
        double c = value(x0, z0 + 1);
        double d = value(x0 + 1, z0 + 1);
        double lower = a + (b - a) * tx;
        double upper = c + (d - c) * tx;
        return lower + (upper - lower) * tz;
    }

    private static double fade(double value) {
        // Quintic interpolation has zero first and second derivatives at cells.
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    private static double value(int x, int z) {
        int hash = x * 0x8da6b343 ^ z * 0xd8163841 ^ 0xcb1ab31f;
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        hash *= 0x846ca68b;
        hash ^= hash >>> 16;
        return (hash & 0x00ffffff) * (2.0 / 16777215.0) - 1.0;
    }
}
