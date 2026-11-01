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

import com.jme3.math.Vector3f;
import com.jme3.terrain.heightfield.HeightfieldHierarchy.Node;

/**
 * Samples a patch's current, morph-displaced triangle surface at a fixed
 * source-lattice XZ location. This object reuses its vertex scratch storage and
 * is thread-confined. The caller supplies a ready node page for each query and
 * retains responsibility for page lifetime and ownership.
 */
public final class HeightfieldSurfaceSampler {
    // Vertices are exact float outputs of the shader; only double barycentric
    // arithmetic needs slack. A float-ULP allowance can accept the wrong face.
    private static final double BARYCENTRIC_TOLERANCE = 1e-12;

    private final Vector3f a = new Vector3f();
    private final Vector3f b = new Vector3f();
    private final Vector3f c = new Vector3f();
    private final Vector3f d = new Vector3f();

    /**
     * Return world-local patch Y at the supplied source-fine-lattice point.
     * Patch and page use the hierarchy's source-local coordinate frame; page
     * coordinates are node-local and already decimated by the node stride.
     *
     * @param patch current immutable patch morph state
     * @param nodePage caller-owned ready node page matching the patch grid
     * @param sourceFineX source-lattice X coordinate, possibly fractional
     * @param sourceFineZ source-lattice Z coordinate, possibly fractional
     * @return interpolated local height in page units
     * @throws IllegalArgumentException for invalid, mismatched, or out-of-node inputs
     */
    public double height(HeightfieldLod.Patch patch, HeightfieldPage nodePage,
            double sourceFineX, double sourceFineZ) {
        if (patch == null || nodePage == null) {
            throw new IllegalArgumentException("patch and node page are required");
        }
        if (!Double.isFinite(sourceFineX) || !Double.isFinite(sourceFineZ)) {
            throw new IllegalArgumentException("source coordinates must be finite");
        }
        Node node = patch.getNode();
        int stride = node.getStride();
        int span = node.getSpan();
        if (stride < 1 || span <= 0 || span % stride != 0
                || nodePage.getCells() != span / stride) {
            throw new IllegalArgumentException("node page cells must match the patch span and stride");
        }
        float spacing = nodePage.getSampleSpacing();
        if (!Float.isFinite(spacing) || spacing <= 0f) {
            throw new IllegalArgumentException("node page spacing must be positive and finite");
        }
        double maxFineX = (double) node.getOriginX() + span;
        double maxFineZ = (double) node.getOriginZ() + span;
        if (sourceFineX < node.getOriginX() || sourceFineX > maxFineX
                || sourceFineZ < node.getOriginZ() || sourceFineZ > maxFineZ) {
            throw new IllegalArgumentException("source point lies outside the patch node");
        }
        float interior = patch.getMorph();
        float west = patch.getEdgeMorph(0);
        float east = patch.getEdgeMorph(1);
        float south = patch.getEdgeMorph(2);
        float north = patch.getEdgeMorph(3);
        if (!unit(interior) || !unit(west) || !unit(east) || !unit(south) || !unit(north)) {
            throw new IllegalArgumentException("patch morph weights must be finite and in [0,1]");
        }

        int cells = nodePage.getCells();
        double localX = (sourceFineX - node.getOriginX()) / stride;
        double localZ = (sourceFineZ - node.getOriginZ()) / stride;
        int cellX = localX == cells ? cells - 1 : (int) Math.floor(localX);
        int cellZ = localZ == cells ? cells - 1 : (int) Math.floor(localZ);

        // Away from morphed boundary strips, the page reconstruction is exact
        // only when integer grid * spacing is represented exactly as float.
        // Power-of-two spacing guarantees this for the bounded page grid.
        if (exactGridProducts(spacing) && interior == 0f
                && !(cellX == 0 && west > 0f)
                && !(cellX == cells - 1 && east > 0f)
                && !(cellZ == 0 && south > 0f)
                && !(cellZ == cells - 1 && north > 0f)) {
            return HeightfieldReconstruction.height(nodePage, localX, localZ, 1);
        }

        double queryX = localX * spacing;
        double queryZ = localZ * spacing;
        double tolerance = BARYCENTRIC_TOLERANCE;
        int minX = Math.max(0, cellX - 1), maxX = Math.min(cells - 1, cellX + 1);
        int minZ = Math.max(0, cellZ - 1), maxZ = Math.min(cells - 1, cellZ + 1);
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                vertex(nodePage, cells, x, z, interior, west, east, south, north, a);
                vertex(nodePage, cells, x + 1, z + 1, interior, west, east, south, north, d);
                vertex(nodePage, cells, x + 1, z, interior, west, east, south, north, b);
                double height = triangleHeight(a, d, b, queryX, queryZ, tolerance);
                if (Double.isFinite(height)) return height;

                vertex(nodePage, cells, x, z + 1, interior, west, east, south, north, c);
                height = triangleHeight(a, c, d, queryX, queryZ, tolerance);
                if (Double.isFinite(height)) return height;
            }
        }
        throw new IllegalArgumentException("no nondegenerate morphed triangle covers source point ("
                + sourceFineX + ", " + sourceFineZ + ") in node (" + node.getOriginX() + ", "
                + node.getOriginZ() + ") span=" + span + " stride=" + stride + " grid=("
                + localX + ", " + localZ + ") morph=" + interior + " edges=[" + west + ", "
                + east + ", " + south + ", " + north + "]");
    }

    private static void vertex(HeightfieldPage page, int cells,
            int x, int z, float interior, float west, float east, float south, float north,
            Vector3f store) {
        float alpha = interior;
        if (x == 0) alpha = Math.max(alpha, west);
        if (x == cells) alpha = Math.max(alpha, east);
        if (z == 0) alpha = Math.max(alpha, south);
        if (z == cells) alpha = Math.max(alpha, north);
        // HeightfieldSampling.glsllib leaves the four patch corners fixed.
        if ((x == 0 || x == cells) && (z == 0 || z == cells)) alpha = 0f;
        HeightfieldReconstruction.morphVertex(page, x, z, 1, alpha, store);
    }

    private static double triangleHeight(Vector3f a, Vector3f b, Vector3f c,
            double x, double z, double tolerance) {
        double abX = (double) b.x - a.x;
        double abZ = (double) b.z - a.z;
        double acX = (double) c.x - a.x;
        double acZ = (double) c.z - a.z;
        double denominator = abX * acZ - abZ * acX;
        // Float vertex coordinates are exactly representable in double, and
        // their products stay in double range. Preserve even very small valid
        // triangles near the end of a morph; only exact collapse is degenerate.
        if (!Double.isFinite(denominator) || denominator == 0.0) return Double.NaN;
        double apX = x - a.x;
        double apZ = z - a.z;
        double weightB = (apX * acZ - apZ * acX) / denominator;
        double weightC = (abX * apZ - abZ * apX) / denominator;
        double weightA = 1.0 - weightB - weightC;
        if (weightA < -tolerance || weightB < -tolerance || weightC < -tolerance
                || weightA > 1 + tolerance || weightB > 1 + tolerance || weightC > 1 + tolerance) {
            return Double.NaN;
        }
        weightA = Math.max(0, weightA);
        weightB = Math.max(0, weightB);
        weightC = Math.max(0, weightC);
        double sum = weightA + weightB + weightC;
        if (!(sum > 0) || !Double.isFinite(sum)) return Double.NaN;
        return (weightA * a.y + weightB * b.y + weightC * c.y) / sum;
    }

    private static boolean exactGridProducts(float spacing) {
        int bits = Float.floatToRawIntBits(spacing);
        int exponent = (bits >>> 23) & 0xff;
        int fraction = bits & 0x7fffff;
        if (exponent == 0) {
            return fraction != 0 && (fraction & (fraction - 1)) == 0;
        }
        return exponent != 0xff && fraction == 0;
    }

    private static boolean unit(float value) {
        return Float.isFinite(value) && value >= 0f && value <= 1f;
    }
}
