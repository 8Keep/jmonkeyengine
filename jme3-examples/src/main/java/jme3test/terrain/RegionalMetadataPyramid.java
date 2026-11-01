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
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors may
 *   be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDER AND CONTRIBUTORS "AS IS"
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

import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Conservative, bounded metadata construction for the connected far-landscape
 * source. Pure Gaussian nodes use the source's analytic rectangle/error bound;
 * nodes intersecting the legacy/blend footprint use nested P1 comparisons.
 */
final class RegionalMetadataPyramid {
    private static final int MAX_PATCH_CELLS = 256;
    private static final long MAX_METADATA_NODES = 1_000_000L;

    private RegionalMetadataPyramid() {
    }

    static HeightfieldHierarchy build(RegionalHeightfieldSource source, int patchCells) {
        if (source == null || source.getCells() != RegionalHeightfieldSource.FAR_CELLS
                || patchCells < 2 || patchCells > MAX_PATCH_CELLS
                || (patchCells & (patchCells - 1)) != 0
                || source.getCells() % patchCells != 0) {
            throw new IllegalArgumentException("far source and a power-of-two patch size in [2,256] are required");
        }
        long leavesPerAxis = source.getCells() / (long) patchCells;
        long leafCount = leavesPerAxis * leavesPerAxis;
        long nodeCountBound = (4 * leafCount - 1) / 3;
        if (nodeCountBound > MAX_METADATA_NODES) {
            throw new IllegalArgumentException("regional metadata hierarchy exceeds 1,000,000 nodes");
        }
        Builder builder = new Builder(source, patchCells);
        Draft root = builder.buildNode(0, 0, source.getCells());
        if (root.parentError != 0) {
            throw new IllegalStateException("root parent error is unused and must be zero");
        }
        Map<NodeKey, HeightfieldHierarchy.NodeMetadata> metadata = new HashMap<>(builder.drafts.size() * 2);
        int visited = 0;
        for (Draft draft : builder.drafts) {
            if ((visited++ & 255) == 0) Builder.checkCancelled();
            NodeKey key = new NodeKey(draft.x, draft.z, draft.span);
            HeightfieldHierarchy.NodeMetadata prior = metadata.put(key,
                    new HeightfieldHierarchy.NodeMetadata(draft.minimum, draft.maximum,
                            draft.stableError, draft.parentError));
            if (prior != null) throw new IllegalStateException("duplicate metadata node " + key);
        }
        int nodeCount = builder.drafts.size();
        HeightfieldHierarchy result = HeightfieldHierarchy.fromMetadata(
                source.getCells(), patchCells, source.getSampleSpacing(),
                source.getRevision(), 0.0, (x, z, span, stride) -> {
                    Builder.checkCancelled();
                    HeightfieldHierarchy.NodeMetadata value = metadata.get(new NodeKey(x, z, span));
                    if (value == null) throw new IllegalStateException("missing postorder metadata at "
                            + x + "," + z + "," + span);
                    return value;
                });
        if (result.getNodes().size() != nodeCount || metadata.size() != nodeCount) {
            throw new IllegalStateException("metadata pyramid node count changed during hierarchy construction");
        }
        System.out.printf(java.util.Locale.ROOT,
                "REGIONAL_METADATA_PYRAMID revision=%d patchCells=%d nodes=%d analyticNodes=%d measuredLeaves=%d measuredLeafSamples=%d parentGridSamples=%d childComparisonSamples=%d fixedScratchBytes=%d%n",
                source.getRevision(), patchCells, nodeCount, builder.analyticNodes,
                builder.measuredLeaves, builder.measuredLeafSamples, builder.parentGridSamples,
                builder.childComparisonSamples, (long) (patchCells + 1) * (patchCells + 1) * Float.BYTES);
        return result;
    }

    private static final class Builder {
        final RegionalHeightfieldSource source;
        final RegionalFarTerrain far;
        final int patchCells;
        final float globalMaximum;
        final double floatAllowance;
        final float[] parentGrid;
        final HeightfieldSource directSamples;
        final List<Draft> drafts = new ArrayList<>();
        long analyticNodes;
        long measuredLeaves;
        long measuredLeafSamples;
        long parentGridSamples;
        long childComparisonSamples;

        Builder(RegionalHeightfieldSource source, int patchCells) {
            this.source = source;
            far = source.getFarTerrain();
            if (far == null) throw new IllegalArgumentException("far source metadata helper is required");
            this.patchCells = patchCells;
            globalMaximum = far.getMaximumHeight();
            if (!Float.isFinite(globalMaximum) || globalMaximum < 0) {
                throw new IllegalArgumentException("far-source global height ceiling must be finite and nonnegative");
            }
            floatAllowance = 2.0 * Math.ulp(globalMaximum);
            parentGrid = new float[(patchCells + 1) * (patchCells + 1)];
            // pageSamples(2) is a direct point view: sparse metadata probes do
            // not fault a full 64x64 source tile into the bounded fine cache.
            directSamples = source.pageSamples(2);
        }

        Draft buildNode(int x, int z, int span) {
            checkCancelled();
            int stride = span / patchCells;
            RegionalFarTerrain.Bounds analytic = far.bounds(x, z, span, stride);
            if (span == patchCells) {
                Draft leaf;
                if (analytic != null) {
                    double range = Math.max(0, (double) analytic.maximumHeight - analytic.minimumHeight);
                    double error = Math.min(analytic.surfaceError, range);
                    leaf = new Draft(x, z, span, analytic.minimumHeight, analytic.maximumHeight,
                            error, 0);
                    analyticNodes++;
                } else {
                    float minimum = Float.POSITIVE_INFINITY;
                    float maximum = Float.NEGATIVE_INFINITY;
                    for (int j = z; j <= z + span; j++) {
                        checkCancelled();
                        for (int i = x; i <= x + span; i++) {
                            float height = source.getHeight(i, j);
                            minimum = Math.min(minimum, height);
                            maximum = Math.max(maximum, height);
                            measuredLeafSamples++;
                        }
                    }
                    leaf = new Draft(x, z, span, minimum, maximum, 0, 0);
                    measuredLeaves++;
                }
                drafts.add(leaf);
                return leaf;
            }

            int half = span / 2;
            Draft[] children = {
                buildNode(x, z, half),
                buildNode(x + half, z, half),
                buildNode(x, z + half, half),
                buildNode(x + half, z + half, half)
            };
            float minimum = Float.POSITIVE_INFINITY;
            float maximum = Float.NEGATIVE_INFINITY;
            for (Draft child : children) {
                minimum = Math.min(minimum, child.minimum);
                maximum = Math.max(maximum, child.maximum);
            }
            double nodeRange = Math.max(0, (double) maximum - minimum);
            double stableError;
            if (analytic != null) {
                stableError = Math.min(analytic.surfaceError, nodeRange);
                analyticNodes++;
                for (Draft child : children) {
                    // This parent P1 surface is bounded against the canonical
                    // fine surface over the entire parent, hence also over each
                    // child. Engine morph logic applies its own factor of two.
                    child.parentError = stableError;
                }
            } else {
                fillParentGrid(x, z, span, stride);
                stableError = 0;
                for (Draft child : children) {
                    double maximumDifference = compareChildToParent(child, x, z, span, stride);
                    double parentError = child.stableError + maximumDifference + floatAllowance;
                    child.parentError = Math.min(parentError, nodeRange);
                    stableError = Math.max(stableError, child.parentError);
                }
                stableError = Math.min(stableError, nodeRange);
            }
            Draft node = new Draft(x, z, span, minimum, maximum, stableError, 0);
            drafts.add(node);
            return node;
        }

        private void fillParentGrid(int x, int z, int span, int stride) {
            HeightfieldSource samples = nearbySamples(stride);
            for (int j = 0; j <= patchCells; j++) {
                checkCancelled();
                int sourceZ = z + j * stride;
                int row = j * (patchCells + 1);
                for (int i = 0; i <= patchCells; i++) {
                    parentGrid[row + i] = samples.getHeight(x + i * stride, sourceZ);
                    parentGridSamples++;
                }
            }
        }

        private double compareChildToParent(Draft child, int parentX, int parentZ,
                int parentSpan, int parentStride) {
            int childStride = child.span / patchCells;
            HeightfieldSource samples = nearbySamples(childStride);
            double maximum = 0;
            for (int j = 0; j <= patchCells; j++) {
                checkCancelled();
                int sourceZ = child.z + j * childStride;
                for (int i = 0; i <= patchCells; i++) {
                    int sourceX = child.x + i * childStride;
                    float sourceHeight = samples.getHeight(sourceX, sourceZ);
                    double reconstructed = interpolateParent(sourceX - parentX,
                            sourceZ - parentZ, parentX, parentZ, parentSpan, parentStride);
                    maximum = Math.max(maximum, Math.abs((double) sourceHeight - reconstructed));
                    childComparisonSamples++;
                }
            }
            return maximum;
        }

        private HeightfieldSource nearbySamples(int stride) {
            // The preceding child walk leaves nearby fine tiles in the bounded
            // cache. A <=4-stride child grid and its siblings fit that working set;
            // wider sparse probes should not fault entire fine tiles.
            return stride <= 4 ? source : directSamples;
        }

        /** P1 reconstruction on the engine's fixed lower-left-to-upper-right diagonal. */
        private double interpolateParent(int relativeX, int relativeZ, int parentX,
                int parentZ, int parentSpan, int stride) {
            double gridX = (double) relativeX / stride;
            double gridZ = (double) relativeZ / stride;
            int cellX = Math.min(patchCells - 1, (int) Math.floor(gridX));
            int cellZ = Math.min(patchCells - 1, (int) Math.floor(gridZ));
            double fx = gridX - cellX;
            double fz = gridZ - cellZ;
            int side = patchCells + 1;

            // At an internal right/top boundary, the global reconstruction
            // chooses the next cell (u/v=0). Its edge value is the last grid
            // column/row. Keep that arithmetic instead of evaluating the
            // neighboring triangle at u/v=1, which can round differently.
            if (relativeX == parentSpan && parentX + parentSpan < source.getCells()) {
                int column = patchCells;
                if (relativeZ == parentSpan && parentZ + parentSpan < source.getCells()) {
                    return parentGrid[patchCells * side + column];
                }
                int baseZ = Math.min(patchCells - 1, cellZ);
                double edgeV = gridZ - baseZ;
                double a = parentGrid[baseZ * side + column];
                double c = parentGrid[(baseZ + 1) * side + column];
                return a + edgeV * (c - a);
            }
            if (relativeZ == parentSpan && parentZ + parentSpan < source.getCells()) {
                int row = patchCells;
                int baseX = Math.min(patchCells - 1, cellX);
                double edgeU = gridX - baseX;
                double a = parentGrid[row * side + baseX];
                double b = parentGrid[row * side + baseX + 1];
                return a + edgeU * (b - a);
            }

            // At the actual world edge the reference keeps the preceding cell.
            if (relativeX == parentSpan) {
                cellX = patchCells - 1;
                fx = 1;
            }
            if (relativeZ == parentSpan) {
                cellZ = patchCells - 1;
                fz = 1;
            }
            double v00 = parentGrid[cellZ * side + cellX];
            double v10 = parentGrid[cellZ * side + cellX + 1];
            double v01 = parentGrid[(cellZ + 1) * side + cellX];
            double v11 = parentGrid[(cellZ + 1) * side + cellX + 1];
            if (fx >= fz) {
                // The engine's diagonal is x == z: the first triangle is
                // lower-left, lower-right, upper-right (u >= v).
                return v00 + fx * (v10 - v00) + fz * (v11 - v10);
            }
            return v00 + fx * (v11 - v01) + fz * (v01 - v00);
        }

        private static void checkCancelled() {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("regional metadata pyramid interrupted");
            }
        }
    }

    private static final class Draft {
        final int x, z, span;
        final float minimum, maximum;
        final double stableError;
        double parentError;

        Draft(int x, int z, int span, float minimum, float maximum,
                double stableError, double parentError) {
            this.x = x;
            this.z = z;
            this.span = span;
            this.minimum = minimum;
            this.maximum = maximum;
            this.stableError = stableError;
            this.parentError = parentError;
        }
    }

    private static final class NodeKey {
        final int x, z, span;

        NodeKey(int x, int z, int span) {
            this.x = x;
            this.z = z;
            this.span = span;
        }

        @Override public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof NodeKey)) return false;
            NodeKey other = (NodeKey) object;
            return x == other.x && z == other.z && span == other.span;
        }

        @Override public int hashCode() {
            int result = 31 * x + z;
            return 31 * result + span;
        }

        @Override public String toString() { return "(" + x + "," + z + "," + span + ")"; }
    }
}
