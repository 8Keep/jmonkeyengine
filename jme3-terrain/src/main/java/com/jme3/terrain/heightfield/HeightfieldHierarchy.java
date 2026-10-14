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
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
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

import com.jme3.bounding.BoundingBox;
import com.jme3.math.Vector3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable nested triangle surfaces over one canonical height page.
 * This small CPU reference hierarchy measures error against the retained fine
 * triangle surface, not an unsampled analytic landscape. It automatically
 * includes a conservative local float-reconstruction allowance in addition
 * to the ideal integer-lattice surface error. Extra source, filter,
 * quantization, edit, or backend uncertainty must be supplied explicitly.
 * Independently filtered payloads are not supported by this reconstruction
 * contract.
 */
public final class HeightfieldHierarchy {
    private final HeightfieldPage page;
    private final int patchCells;
    private final double additionalError;
    private final Node root;
    private final List<Node> nodes;

    /**
     * Construct a hierarchy over a canonical page.
     *
     * @param page canonical samples and physical sample spacing (not null)
     * @param patchCells number of cells per patch edge (power of two, at least 2)
     * @param additionalWorldError total extra comparison uncertainty in world
     * units, beyond the automatically included local float-reconstruction
     * allowance; use positive infinity when the unsupported/backend uncertainty
     * cannot be bounded. This is an error for the comparison as a whole, not a
     * per-endpoint amount. It must cover any renderer arithmetic or transform
     * uncertainty not described by the local IEEE binary32 reconstruction
     * contract below.
     */
    public HeightfieldHierarchy(HeightfieldPage page, int patchCells, double additionalWorldError) {
        if (page == null || !powerOfTwo(page.getCells()) || patchCells < 2
                || !powerOfTwo(patchCells) || patchCells > page.getCells()
                || Double.isNaN(additionalWorldError) || additionalWorldError < 0) {
            throw new IllegalArgumentException("power-of-two page/patch dimensions and nonnegative uncertainty required");
        }
        this.page = page;
        this.patchCells = patchCells;
        additionalError = additionalWorldError;
        root = new Node(null, 0, 0, page.getCells(), 0);
        List<Node> ordered = new ArrayList<>();
        collect(root, ordered);
        nodes = Collections.unmodifiableList(ordered);
    }

    public HeightfieldPage getPage() { return page; }
    public int getPatchCells() { return patchCells; }
    public Node getRoot() { return root; }
    public List<Node> getNodes() { return nodes; }

    private static boolean powerOfTwo(int n) { return n > 0 && (n & (n - 1)) == 0; }
    private static void collect(Node node, List<Node> result) {
        result.add(node);
        for (Node child : node.children) collect(child, result);
    }

    /** A fixed-size grid displaced at a power-of-two sample stride. */
    public final class Node {
        private final Node parent;
        private final int x, z, span, level, stride;
        private final List<Node> children;
        private final BoundingBox bound;
        private final double stableError, morphError;
        private final double floatReconstructionError;

        private Node(Node parent, int x, int z, int span, int level) {
            this.parent = parent;
            this.x = x;
            this.z = z;
            this.span = span;
            this.level = level;
            stride = span / patchCells;
            float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
            for (int j = z; j <= z + span; j++) {
                for (int i = x; i <= x + span; i++) {
                    float h = page.getHeight(i, j);
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                }
            }
            float spacing = page.getSampleSpacing();
            floatReconstructionError = localFloatReconstructionError(
                    Math.max(Math.abs(min), Math.abs(max)), Math.max(x + span, z + span) * spacing);
            double idealStableError = HeightfieldReconstruction.maximumError(page, x, z, span, stride);
            stableError = idealStableError + floatReconstructionError + additionalError;
            // Every moved triangle stays in its original parent triangle in
            // x/z. Its residual from the parent plane is bounded by E; the
            // fine reference is also within E. This bounds vertical error by 2E.
            // Float reconstruction is a comparison allowance and is added once.
            morphError = parent == null ? stableError
                    : 2 * HeightfieldReconstruction.maximumError(page, x, z, span, 2 * stride)
                    + floatReconstructionError + additionalError;
            float[] lower = {x * spacing, min, z * spacing};
            float[] upper = {(x + span) * spacing, max, (z + span) * spacing};
            Vector3f center = new Vector3f();
            float[] extent = new float[3];
            for (int axis = 0; axis < 3; axis++) {
                float c = (float) (((double) lower[axis] + upper[axis]) * .5);
                center.set(axis, c);
                double radius = Math.max((double) c - lower[axis], (double) upper[axis] - c);
                float e = (float) radius;
                extent[axis] = e < radius ? Math.nextUp(e) : e;
            }
            bound = new BoundingBox(center, extent[0], extent[1], extent[2]);
            if (stride == 1) {
                children = Collections.emptyList();
            } else {
                int half = span / 2;
                List<Node> four = new ArrayList<>(4);
                four.add(new Node(this, x, z, half, level + 1));
                four.add(new Node(this, x + half, z, half, level + 1));
                four.add(new Node(this, x, z + half, half, level + 1));
                four.add(new Node(this, x + half, z + half, half, level + 1));
                children = Collections.unmodifiableList(four);
            }
        }

        public int getOriginX() { return x; }
        public int getOriginZ() { return z; }
        public int getSpan() { return span; }
        public int getStride() { return stride; }
        public int getLevel() { return level; }
        public Node getParent() { return parent; }
        public List<Node> getChildren() { return children; }
        public BoundingBox getBound() { return (BoundingBox) bound.clone(); }
        public double getStableError() { return stableError; }
        public double getMorphError() { return morphError; }
        boolean contains(Node other) {
            return other.x >= x && other.z >= z && other.x + other.span <= x + span
                    && other.z + other.span <= z + span;
        }
    }

    /**
     * A deliberately loose local allowance for conversion and convex morph
     * reconstruction under correctly rounded IEEE-754 binary32 arithmetic
     * with gradual underflow. A physical coordinate is formed by multiplying
     * an exactly representable lattice index (at most 256) by float spacing;
     * a morph then uses one subtraction, two products, and one addition. For a
     * scalar magnitude bounded by M, charge one full ULP(M) for coordinate
     * conversion and at most five for the rounded weight/products/sum. Charge
     * another ULP for the reference reconstruction. The weight error is bounded
     * by twice ULP(M), since its absolute error is at most half an ULP at 1 and
     * the endpoint difference is at most 2M. Thus seven ULPs per component
     * cover the candidate/reference pair. Combining two horizontal components
     * and one height component gives at most
     * 7*sqrt(2*extentUlp^2 + heightUlp^2), which is below 16 times their sum.
     * The extra two minimum-normal values are a conservative floor near zero.
     * This is not a bound for non-binary32 arithmetic, flush-to-zero backends,
     * GPU transforms, or other renderer operations; callers must include those
     * in their total extra comparison uncertainty (or pass positive infinity
     * if they cannot bound it). A renderer must preserve exact morph endpoints
     * and keep convex interpolation within the endpoint hull, as
     * {@link HeightfieldReconstruction#morphVertex} does.
     */
    private static double localFloatReconstructionError(float maxAbsHeight, float maxAbsExtent) {
        double heightUlp = Math.ulp(maxAbsHeight);
        double extentUlp = Math.ulp(maxAbsExtent);
        return 16.0 * (heightUlp + extentUlp) + 2.0 * Float.MIN_NORMAL;
    }
}
