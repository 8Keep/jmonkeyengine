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
package com.jme3.terrain.heightfield;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Builds exact hierarchy metadata while scanning each leaf's fine vertices once.
 * Unlike {@link HeightfieldHierarchy#fromSource}, this builder retains only a
 * bounded stack of ancestor grids and the compact metadata arrays required by
 * the result hierarchy. Both sources must represent the same immutable sample
 * revision; {@code pointSource} may evaluate sparse coarse-grid points without
 * loading full tiles into {@code fineSource}.
 */
public final class HeightfieldMetadataBuilder {
    private static final int MAX_REGION_INDEX = 1 << 20;
    private static final int MAX_PATCH_CELLS = 256;
    private static final long MAX_NODES = 1_000_000L;
    private static final long MAX_GRID_SCRATCH_BYTES = 4L * 1024 * 1024;

    private HeightfieldMetadataBuilder() {
    }

    /** Build using one source for fine and sparse point samples. */
    public static HeightfieldHierarchy build(HeightfieldSource source, int patchCells,
            long sourceRevision, double additionalWorldError) {
        return build(source, source, patchCells, sourceRevision, additionalWorldError);
    }

    /** Build using tile-friendly fine reads and an equivalent sparse point source. */
    public static HeightfieldHierarchy build(HeightfieldSource fineSource,
            HeightfieldSource pointSource, int patchCells, long sourceRevision,
            double additionalWorldError) {
        Dimensions dimensions = validate(fineSource, pointSource, patchCells,
                sourceRevision, additionalWorldError);
        Builder.checkCancelled();
        Builder builder = new Builder(fineSource, pointSource, dimensions, patchCells);
        builder.buildNode(0, 0, dimensions.cells, -1);

        // HeightfieldHierarchy asks its provider for nodes in pre-order. The
        // construction walk above uses the same order, so no coordinate map or
        // retained source callback is needed.
        final int[] next = {0};
        return HeightfieldHierarchy.fromMetadata(dimensions.cells, patchCells,
                dimensions.spacing, sourceRevision, additionalWorldError,
                (x, z, span, stride) -> {
                    Builder.checkCancelled();
                    int index = next[0]++;
                    if (index >= builder.nodeCount
                            || builder.x[index] != x || builder.z[index] != z
                            || builder.span[index] != span || builder.stride[index] != stride) {
                        throw new IllegalStateException("metadata traversal differs at "
                                + x + "," + z + "," + span);
                    }
                    return new HeightfieldHierarchy.NodeMetadata(builder.minimum[index],
                            builder.maximum[index], builder.stableError[index],
                            builder.parentError[index]);
                });
    }

    private static Dimensions validate(HeightfieldSource fine, HeightfieldSource points,
            int patchCells, long revision, double additionalError) {
        if (fine == null || points == null) throw new IllegalArgumentException("both sources are required");
        int cells = fine.getCells();
        float spacing = fine.getSampleSpacing();
        if (cells < 1 || cells > MAX_REGION_INDEX || (cells & (cells - 1)) != 0
                || points.getCells() != cells
                || Float.floatToIntBits(points.getSampleSpacing()) != Float.floatToIntBits(spacing)
                || !Float.isFinite(spacing) || spacing <= 0 || !Float.isFinite(cells * spacing)
                || patchCells < 2 || patchCells > MAX_PATCH_CELLS
                || (patchCells & (patchCells - 1)) != 0 || patchCells > cells
                || cells % patchCells != 0 || revision < 0
                || Double.isNaN(additionalError) || additionalError < 0
                || maximumGridScratchBytes(cells, patchCells) > MAX_GRID_SCRATCH_BYTES) {
            throw new IllegalArgumentException("compatible power-of-two sources, dimensions, and errors are required");
        }
        long nodeCount = 0, levelCount = 1;
        int span = cells;
        while (true) {
            nodeCount += levelCount;
            if (nodeCount > MAX_NODES) throw new IllegalArgumentException("hierarchy exceeds 1,000,000 nodes");
            if (span == patchCells) break;
            span /= 2;
            levelCount *= 4;
        }
        return new Dimensions(cells, spacing, (int) nodeCount);
    }

    /** Maximum retained ancestor-grid storage, excluding O(node-count) metadata. */
    static long maximumGridScratchBytes(int cells, int patchCells) {
        if (cells < 1 || patchCells < 1 || patchCells > cells) return Long.MAX_VALUE;
        int depth = 0;
        for (int span = cells; span > patchCells; span /= 2) depth++;
        return (long) depth * (patchCells + 1) * (patchCells + 1) * Float.BYTES;
    }

    private static final class Dimensions {
        final int cells, nodeCount;
        final float spacing;

        Dimensions(int cells, float spacing, int nodeCount) {
            this.cells = cells;
            this.spacing = spacing;
            this.nodeCount = nodeCount;
        }
    }

    private static final class Builder {
        final HeightfieldSource fine, points;
        final int cells, patchCells, gridSide;
        final int[] x, z, span, stride;
        final float[] minimum, maximum;
        final double[] stableError, parentError;
        final List<Frame> frames = new ArrayList<>();
        int nodeCount;

        Builder(HeightfieldSource fine, HeightfieldSource points, Dimensions dimensions,
                int patchCells) {
            this.fine = fine;
            this.points = points;
            cells = dimensions.cells;
            this.patchCells = patchCells;
            gridSide = patchCells + 1;
            x = new int[dimensions.nodeCount];
            z = new int[dimensions.nodeCount];
            span = new int[dimensions.nodeCount];
            stride = new int[dimensions.nodeCount];
            minimum = new float[dimensions.nodeCount];
            maximum = new float[dimensions.nodeCount];
            stableError = new double[dimensions.nodeCount];
            parentError = new double[dimensions.nodeCount];
        }

        int buildNode(int originX, int originZ, int nodeSpan, int parentIndex) {
            checkCancelled();
            int index = nodeCount++;
            int nodeStride = nodeSpan / patchCells;
            x[index] = originX;
            z[index] = originZ;
            span[index] = nodeSpan;
            stride[index] = nodeStride;
            if (parentIndex >= 0) frames.get(frames.size() - 1).activeChild = index;

            if (nodeSpan == patchCells) {
                measureLeaf(index);
                return index;
            }

            Frame frame = new Frame(index, originX, originZ, nodeSpan, nodeStride,
                    new float[gridSide * gridSide]);
            fillGrid(frame);
            frames.add(frame);
            int half = nodeSpan / 2;
            int child0 = buildNode(originX, originZ, half, index);
            int child1 = buildNode(originX + half, originZ, half, index);
            int child2 = buildNode(originX, originZ + half, half, index);
            int child3 = buildNode(originX + half, originZ + half, half, index);
            frames.remove(frames.size() - 1);
            minimum[index] = Math.min(Math.min(minimum[child0], minimum[child1]),
                    Math.min(minimum[child2], minimum[child3]));
            maximum[index] = Math.max(Math.max(maximum[child0], maximum[child1]),
                    Math.max(maximum[child2], maximum[child3]));
            return index;
        }

        private void measureLeaf(int leafIndex) {
            int originX = x[leafIndex], originZ = z[leafIndex];
            float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
            for (int j = 0; j <= patchCells; j++) {
                checkCancelled();
                int sampleZ = originZ + j;
                for (int i = 0; i <= patchCells; i++) {
                    int sampleX = originX + i;
                    float height = sample(fine, sampleX, sampleZ);
                    low = Math.min(low, height);
                    high = Math.max(high, height);
                    double reference = referenceHeight(sampleX, sampleZ, height);
                    for (Frame frame : frames) {
                        double reconstructed = interpolate(frame, sampleX, sampleZ);
                        double error = Math.abs(reference - reconstructed);
                        frameStable(frame, error);
                        parentError[frame.activeChild] = Math.max(parentError[frame.activeChild], error);
                    }
                }
            }
            minimum[leafIndex] = low;
            maximum[leafIndex] = high;
            // Fine leaves are the canonical stride-one surface.
            stableError[leafIndex] = 0;
        }

        /**
         * At ordinary fine-grid vertices stride-one reconstruction returns the
         * sampled value. At the right/top world edges the reference uses the
         * preceding cell and evaluates with u or v equal to one; reproduce that
         * arithmetic explicitly, including cancellation for widely separated
         * finite float magnitudes.
         */
        private double referenceHeight(int sx, int sz, float sampledHeight) {
            if (sx < cells && sz < cells) return sampledHeight;
            checkCancelled();
            if (sx == cells && sz == cells) {
                double a = sample(points, cells - 1, cells - 1);
                double b = sample(points, cells, cells - 1);
                double d = sample(points, cells, cells);
                return a + 1 * (b - a) + 1 * (d - b);
            }
            if (sx == cells) {
                double a = sample(points, cells - 1, sz);
                double b = sample(points, cells, sz);
                double d = sample(points, cells, sz + 1);
                return a + 1 * (b - a) + 0 * (d - b);
            }
            double a = sample(points, sx, cells - 1);
            double c = sample(points, sx, cells);
            double d = sample(points, sx + 1, cells);
            return a + 0 * (d - c) + 1 * (c - a);
        }

        private void frameStable(Frame frame, double error) {
            stableError[frame.nodeIndex] = Math.max(stableError[frame.nodeIndex], error);
        }

        private void fillGrid(Frame frame) {
            for (int j = 0; j <= patchCells; j++) {
                checkCancelled();
                for (int i = 0; i <= patchCells; i++) {
                    frame.grid[j * gridSide + i] = sample(points,
                            frame.originX + i * frame.stride,
                            frame.originZ + j * frame.stride);
                }
            }
        }

        /** Match HeightfieldReconstruction's baseline and triangle arithmetic. */
        private double interpolate(Frame frame, int sampleX, int sampleZ) {
            int relativeX = sampleX - frame.originX;
            int relativeZ = sampleZ - frame.originZ;
            int cellX = relativeX / frame.stride;
            int cellZ = relativeZ / frame.stride;
            double u = (double) (relativeX - cellX * frame.stride) / frame.stride;
            double v = (double) (relativeZ - cellZ * frame.stride) / frame.stride;

            // At an internal right/top node edge, the global reconstruction
            // chooses the cell to the right/above (u/v=0). Its boundary value
            // is exactly the current grid's last column/row. Evaluate that
            // edge directly to preserve the oracle's arithmetic and avoid
            // requiring samples outside this node grid.
            if (relativeX == frame.nodeSpan && sampleX < cells) {
                int column = patchCells;
                if (relativeZ == frame.nodeSpan && sampleZ < cells) {
                    return frame.grid[patchCells * gridSide + column];
                }
                int baseZ = Math.min(patchCells - 1, cellZ);
                double edgeV = (double) (relativeZ - baseZ * frame.stride) / frame.stride;
                double a = frame.grid[baseZ * gridSide + column];
                double c = frame.grid[(baseZ + 1) * gridSide + column];
                return a + edgeV * (c - a);
            }
            if (relativeZ == frame.nodeSpan && sampleZ < cells) {
                int row = patchCells;
                int baseX = Math.min(patchCells - 1, cellX);
                double edgeU = (double) (relativeX - baseX * frame.stride) / frame.stride;
                double a = frame.grid[row * gridSide + baseX];
                double b = frame.grid[row * gridSide + baseX + 1];
                return a + edgeU * (b - a);
            }

            // World-edge coordinates are the one case where the reference
            // keeps the preceding cell and evaluates with u/v=1.
            if (relativeX == frame.nodeSpan) {
                cellX = patchCells - 1;
                u = 1;
            }
            if (relativeZ == frame.nodeSpan) {
                cellZ = patchCells - 1;
                v = 1;
            }
            int side = gridSide;
            double a = frame.grid[cellZ * side + cellX];
            double b = frame.grid[cellZ * side + cellX + 1];
            double c = frame.grid[(cellZ + 1) * side + cellX];
            double d = frame.grid[(cellZ + 1) * side + cellX + 1];
            if (u >= v) return a + u * (b - a) + v * (d - b);
            return a + u * (d - c) + v * (c - a);
        }

        private float sample(HeightfieldSource source, int sx, int sz) {
            float result = source.getHeight(sx, sz);
            if (!Float.isFinite(result)) {
                throw new IllegalArgumentException("height source returned a non-finite sample at " + sx + "," + sz);
            }
            return result;
        }

        private static void checkCancelled() {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("heightfield metadata construction interrupted");
            }
        }
    }

    private static final class Frame {
        final int nodeIndex, originX, originZ, nodeSpan, stride;
        final float[] grid;
        int activeChild;

        Frame(int nodeIndex, int originX, int originZ, int nodeSpan, int stride, float[] grid) {
            this.nodeIndex = nodeIndex;
            this.originX = originX;
            this.originZ = originZ;
            this.nodeSpan = nodeSpan;
            this.stride = stride;
            this.grid = grid;
        }
    }
}
