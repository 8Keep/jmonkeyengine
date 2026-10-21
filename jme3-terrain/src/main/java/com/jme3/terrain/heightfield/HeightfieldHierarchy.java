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
 * Immutable hierarchy of nested triangle surfaces over a canonical heightfield.
 * Error metadata compares ideal triangle surfaces against the canonical fine
 * triangle surface. The hierarchy adds a local float-reconstruction allowance;
 * additional source, filter, quantization, edit, or backend uncertainty belongs
 * in {@code additionalWorldError}. Independently filtered payloads are not
 * supported by this nested reconstruction contract.
 */
public final class HeightfieldHierarchy {
    private static final int MAX_REGION_INDEX = 1 << 20;
    private static final long MAX_NODES = 1_000_000L;

    private final HeightfieldPage page;
    private final int cells, patchCells;
    private final float spacing;
    private final long sourceRevision;
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
        if (page == null) throw new IllegalArgumentException("page is required");
        validateDimensions(page.getCells(), patchCells, page.getSampleSpacing(), additionalWorldError);
        this.page = page;
        cells = page.getCells();
        spacing = page.getSampleSpacing();
        this.patchCells = patchCells;
        sourceRevision = 0;
        additionalError = additionalWorldError;
        root = new Node(null, 0, 0, cells, 0,
                (x, z, span, stride) -> measure(page, x, z, span, stride), null);
        List<Node> ordered = new ArrayList<>();
        collect(root, ordered);
        nodes = Collections.unmodifiableList(ordered);
    }

    private HeightfieldHierarchy(int cells, int patchCells, float spacing, long sourceRevision,
            double additionalWorldError, MetadataProvider provider) {
        validateDimensions(cells, patchCells, spacing, additionalWorldError);
        if (provider == null) throw new IllegalArgumentException("metadata provider is required");
        if (sourceRevision < 0) throw new IllegalArgumentException("source revision must be nonnegative");
        this.page = null;
        this.cells = cells;
        this.patchCells = patchCells;
        this.spacing = spacing;
        this.sourceRevision = sourceRevision;
        this.additionalError = additionalWorldError;
        root = new Node(null, 0, 0, cells, 0, provider, null);
        List<Node> ordered = new ArrayList<>();
        collect(root, ordered);
        nodes = Collections.unmodifiableList(ordered);
    }

    /** Build from exact or conservative canonical-surface metadata, retaining no provider. */
    public static HeightfieldHierarchy fromMetadata(int cells, int patchCells, float spacing,
            long sourceRevision, double additionalWorldError, MetadataProvider provider) {
        return new HeightfieldHierarchy(cells, patchCells, spacing, sourceRevision,
                additionalWorldError, provider);
    }

    /**
     * Measure metadata from one immutable fine source without retaining it.
     * This exhaustive CPU reference scans fine samples at each level; production
     * sources can prepare/cache metadata offline and use {@link #fromMetadata}.
     * The caller keeps source revision and extracted residency payloads coherent.
     */
    public static HeightfieldHierarchy fromSource(HeightfieldSource source, int patchCells,
            long sourceRevision, double additionalWorldError) {
        if (source == null) throw new IllegalArgumentException("source is required");
        return fromMetadata(source.getCells(), patchCells, source.getSampleSpacing(),
                sourceRevision, additionalWorldError,
                (x, z, span, stride) -> measure(source, x, z, span, stride));
    }

    private static NodeMetadata measure(HeightfieldSource source, int x, int z, int span, int stride) {
        float minimum = Float.POSITIVE_INFINITY, maximum = Float.NEGATIVE_INFINITY;
        for (int j = z; j <= z + span; j++) for (int i = x; i <= x + span; i++) {
            float height = source.getHeight(i, j);
            minimum = Math.min(minimum, height);
            maximum = Math.max(maximum, height);
        }
        double stable = HeightfieldReconstruction.maximumError(source, x, z, span, stride);
        double parent = span == source.getCells() ? 0
                : HeightfieldReconstruction.maximumError(source, x, z, span, 2 * stride);
        return new NodeMetadata(minimum, maximum, stable, parent);
    }

    private static void validateDimensions(int cells, int patchCells, float spacing, double extra) {
        if (!powerOfTwo(cells) || cells > MAX_REGION_INDEX || patchCells < 2
                || !powerOfTwo(patchCells) || patchCells > cells
                || !Float.isFinite(spacing) || spacing <= 0
                || !Float.isFinite(cells * spacing)
                || Double.isNaN(extra) || extra < 0) {
            throw new IllegalArgumentException("power-of-two dimensions, finite positive extent, and nonnegative uncertainty required");
        }
        long count = 0, level = 1;
        int span = cells;
        while (true) {
            count += level;
            if (count > MAX_NODES) throw new IllegalArgumentException("hierarchy exceeds 1,000,000 nodes");
            if (span == patchCells) break;
            span /= 2;
            level *= 4;
        }
    }

    public boolean hasCanonicalPage() { return page != null; }
    public HeightfieldPage getPage() {
        if (page == null) throw new IllegalStateException("metadata-only hierarchy has no canonical page");
        return page;
    }
    public int getCells() { return cells; }
    public float getSampleSpacing() { return spacing; }
    public long getSourceRevision() { return sourceRevision; }
    public int getPatchCells() { return patchCells; }
    public Node getRoot() { return root; }
    public List<Node> getNodes() { return nodes; }

    private static boolean powerOfTwo(int n) { return n > 0 && (n & (n - 1)) == 0; }
    private static void collect(Node node, List<Node> result) {
        result.add(node);
        for (Node child : node.children) collect(child, result);
    }

    /**
     * Immutable node summary. Errors are exact or conservative bounds in world
     * height units against the canonical fine triangle surface: stable compares
     * this node's ideal triangle surface, while parent compares the surface at
     * twice this node's stride. The root has no parent, so its parent error is
     * unused and may be zero. Positive infinity means the error is unbounded.
     */
    public static final class NodeMetadata {
        private final float minimumHeight, maximumHeight;
        private final double stableSurfaceError, parentSurfaceError;
        public NodeMetadata(float minimumHeight, float maximumHeight,
                double stableSurfaceError, double parentSurfaceError) {
            if (!Float.isFinite(minimumHeight) || !Float.isFinite(maximumHeight)
                    || minimumHeight > maximumHeight
                    || Double.isNaN(stableSurfaceError) || stableSurfaceError < 0
                    || Double.isNaN(parentSurfaceError) || parentSurfaceError < 0) {
                throw new IllegalArgumentException("finite ordered heights and nonnegative error bounds required");
            }
            this.minimumHeight = minimumHeight;
            this.maximumHeight = maximumHeight;
            this.stableSurfaceError = stableSurfaceError;
            this.parentSurfaceError = parentSurfaceError;
        }
        public float getMinimumHeight() { return minimumHeight; }
        public float getMaximumHeight() { return maximumHeight; }
        public double getStableSurfaceError() { return stableSurfaceError; }
        public double getParentSurfaceError() { return parentSurfaceError; }
    }

    /** Supplies metadata in canonical integer lattice coordinates. */
    @FunctionalInterface
    public interface MetadataProvider {
        NodeMetadata metadata(int x, int z, int span, int stride);
    }

    /** A fixed-size grid displaced at a power-of-two sample stride. */
    public final class Node {
        private final Node parent;
        private final int x, z, span, level, stride;
        private final List<Node> children;
        private final BoundingBox bound;
        private final NodeMetadata metadata;
        private final double stableError, morphError;

        private Node(Node parent, int x, int z, int span, int level,
                MetadataProvider provider, NodeMetadata parentData) {
            this.parent = parent; this.x = x; this.z = z; this.span = span; this.level = level;
            stride = span / patchCells;
            NodeMetadata data = provider.metadata(x, z, span, stride);
            validateMetadata(data, parentData);
            metadata = data;
            float min = data.minimumHeight, max = data.maximumHeight;
            double floatError = localFloatReconstructionError(Math.max(Math.abs(min), Math.abs(max)),
                    Math.max(x + span, z + span) * spacing);
            stableError = data.stableSurfaceError + floatError + additionalError;
            // Both morphed vertices and the fine reference deviate from the
            // parent plane by at most its error E, so their comparison is 2E.
            // The float comparison allowance is charged only once.
            morphError = parent == null ? stableError
                    : 2 * data.parentSurfaceError + floatError + additionalError;
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
            if (stride == 1) children = Collections.emptyList();
            else {
                int half = span / 2;
                List<Node> four = new ArrayList<>(4);
                four.add(new Node(this, x, z, half, level + 1, provider, data));
                four.add(new Node(this, x + half, z, half, level + 1, provider, data));
                four.add(new Node(this, x, z + half, half, level + 1, provider, data));
                four.add(new Node(this, x + half, z + half, half, level + 1, provider, data));
                children = Collections.unmodifiableList(four);
            }
        }

        private void validateMetadata(NodeMetadata data, NodeMetadata parentData) {
            if (data == null || !Float.isFinite(data.minimumHeight) || !Float.isFinite(data.maximumHeight)
                    || data.minimumHeight > data.maximumHeight
                    || !Float.isFinite((float) (((double) data.maximumHeight - data.minimumHeight) * .5))
                    || Double.isNaN(data.stableSurfaceError) || data.stableSurfaceError < 0
                    || Double.isNaN(data.parentSurfaceError) || data.parentSurfaceError < 0) {
                throw new IllegalArgumentException("invalid hierarchy metadata at " + x + "," + z + "," + span);
            }
            if (parentData != null && (data.minimumHeight < parentData.minimumHeight
                    || data.maximumHeight > parentData.maximumHeight)) {
                throw new IllegalArgumentException("child height range escapes parent at " + x + "," + z + "," + span);
            }
        }

        /** Uninflated source metadata suitable for a later metadata-only rebuild. */
        public NodeMetadata getMetadata() { return metadata; }
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
     * an exactly representable lattice index (at most 2^20) by float spacing;
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
