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
package jme3test.terrain;

import com.jme3.terrain.heightfield.HeightfieldSource;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldMetadataBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Bounded, deterministic scenic source shared by regional terrain examples.
 * Preparation is thread-confined; transfer ownership to the render thread
 * only after preparation completes.
 */
final class RegionalHeightfieldSource implements HeightfieldSource {
    static final int CELLS = 1024;
    static final int FAR_CELLS = 8192;
    static final float SPACING = .5f;

    private static final int TILE_CELLS = 64;
    private static final int DEFAULT_TILE_LIMIT = 64;
    private final int tileLimit;
    private static final long TILE_FLOAT_BYTES = (long) TILE_CELLS * TILE_CELLS * Float.BYTES;
    private static final double SCENIC_OFFSET_X = 100;
    private static final double SCENIC_OFFSET_Z = 200;
    private static final double EDIT_HILL_X = 280;
    private static final double EDIT_HILL_Z = 220;
    private static final double EDIT_HILL_RADIUS = 35;

    private final long revision;
    private final int cells;
    private final RegionalFarTerrain farTerrain;
    private final HeightfieldSource pointSamples = new PointSampleView();
    private final LinkedHashMap<Long, float[]> tiles = new LinkedHashMap<Long, float[]>(80, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, float[]> eldest) {
            return size() > tileLimit;
        }
    };
    private int tileLoads;
    private long peakTransientFloatBytes;
    private long directSampleCount;
    private long heightRequests;

    RegionalHeightfieldSource(long revision) {
        this(CELLS, revision);
    }

    RegionalHeightfieldSource(int cells, long revision) {
        if (cells != 1024 && cells != 2048 && cells != FAR_CELLS) {
            throw new IllegalArgumentException("regional source cells must be 1024, 2048, or 8192");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("synthetic source revision must be nonnegative");
        }
        this.cells = cells;
        // Keep the fine scan and its coarse reconstruction rows resident,
        // including edge tiles. A kilometre-wide scan outgrows 64 tiles.
        tileLimit = cells >= 2048 ? 128 : DEFAULT_TILE_LIMIT;
        this.revision = revision;
        farTerrain = cells == FAR_CELLS ? new RegionalFarTerrain(revision) : null;
    }

    @Override
    public int getCells() {
        return cells;
    }

    @Override
    public float getSampleSpacing() {
        return SPACING;
    }

    @Override
    public float getHeight(int x, int z) {
        if (((++heightRequests) & 4095L) == 0) {
            checkInterrupted();
        }
        if (x < -1 || x > cells + 1 || z < -1 || z > cells + 1) {
            throw new IndexOutOfBoundsException("sample is outside the source and its one-fine-sample halo");
        }
        int tileX = Math.floorDiv(x, TILE_CELLS);
        int tileZ = Math.floorDiv(z, TILE_CELLS);
        long key = ((long) tileZ << 32) ^ (tileX & 0xffffffffL);
        float[] tile = tiles.get(key);
        if (tile == null) {
            tile = loadTile(tileX, tileZ);
            peakTransientFloatBytes = Math.max(peakTransientFloatBytes,
                    (tiles.size() + 1L) * TILE_FLOAT_BYTES);
            tiles.put(key, tile);
            tileLoads++;
        }
        int localX = Math.floorMod(x, TILE_CELLS);
        int localZ = Math.floorMod(z, TILE_CELLS);
        return tile[localZ * TILE_CELLS + localX];
    }

    long getRevision() {
        return revision;
    }

    float getWorldOrigin() {
        return farTerrain == null ? 0f : -2048f;
    }

    RegionalFarTerrain getFarTerrain() {
        return farTerrain;
    }

    HeightfieldHierarchy measuredHierarchy(int patchCells) {
        return farTerrain == null
                ? HeightfieldMetadataBuilder.build(this, pageSamples(2), patchCells, revision, 0)
                : RegionalMetadataPyramid.build(this, patchCells);
    }

    /** Build conservative metadata without sampling or retaining a dense page. */
    HeightfieldHierarchy bootstrapHierarchy(int patchCells) {
        if (farTerrain != null) {
            float maximum = farTerrain.getMaximumHeight();
            return HeightfieldHierarchy.fromMetadata(
                    cells, patchCells, SPACING, revision, 0,
                    (x, z, span, stride) -> new HeightfieldHierarchy.NodeMetadata(
                            0, maximum, stride == 1 ? 0 : maximum,
                            stride == cells / patchCells ? 0 : maximum));
        }
        // Global source envelope: 25.3 base + 266.7 first hills + 196.88
        // second hills + 122.96 far hills + 97 summits + 5.42 detail + 20
        // edit maximum = 734.26. The source is nonnegative; [0,736] pads it.
        // Every coarse triangle and parent morph is a convex interpolation of
        // fine samples in this envelope, so 736 bounds their vertical error.
        return HeightfieldHierarchy.fromMetadata(
                cells, patchCells, SPACING, revision, 0,
                (x, z, span, stride) -> new HeightfieldHierarchy.NodeMetadata(
                        0, 736, stride == 1 ? 0 : 736, stride == cells / patchCells ? 0 : 736));
    }

    static long maximumCacheBytes(int cells) {
        return (cells >= 2048 ? 128L : DEFAULT_TILE_LIMIT) * TILE_FLOAT_BYTES;
    }

    long getCachedFloatBytes() {
        return (long) tiles.size() * TILE_FLOAT_BYTES;
    }

    long getPeakTransientFloatBytes() {
        return peakTransientFloatBytes;
    }

    int getTileLoads() {
        return tileLoads;
    }

    long getDirectSampleCount() {
        return directSampleCount;
    }

    long getHeightRequestCount() {
        return heightRequests;
    }

    /**
     * Return the source view used by a page transaction at {@code stride}.
     * Fine samples use the bounded tile cache; coarser page samples bypass
     * whole-tile loading and evaluate the exact same immutable function at
     * each requested fine-lattice point. The view remains on this revision.
     */
    HeightfieldSource pageSamples(int stride) {
        if (stride <= 0 || (stride & (stride - 1)) != 0 || stride > cells) {
            throw new IllegalArgumentException("stride must be a power of two no greater than source cells");
        }
        return stride == 1 ? this : pointSamples;
    }

    private float[] loadTile(int tileX, int tileZ) {
        checkInterrupted();
        float[] values = new float[TILE_CELLS * TILE_CELLS];
        for (int z = 0; z < TILE_CELLS; z++) {
            if ((z & 7) == 0) {
                checkInterrupted();
            }
            for (int x = 0; x < TILE_CELLS; x++) {
                int localX = tileX * TILE_CELLS + x;
                int localZ = tileZ * TILE_CELLS + z;
                values[z * TILE_CELLS + x] = sampleHeight(localX, localZ);
            }
        }
        return values;
    }

    private float sampleHeight(int x, int z) {
        return farTerrain == null ? legacyHeight(x, z, revision) : farTerrain.height(x, z);
    }

    static float legacyHeight(int globalFineX, int globalFineZ, long revision) {
        double worldX = globalFineX * (double) SPACING + SCENIC_OFFSET_X;
        double worldZ = globalFineZ * (double) SPACING + SCENIC_OFFSET_Z;
        double height = ScenicHeightField.height(worldX, worldZ);
        if ((revision & 1L) != 0) {
            double dx = globalFineX * (double) SPACING - EDIT_HILL_X;
            double dz = globalFineZ * (double) SPACING - EDIT_HILL_Z;
            double radiusSquared = EDIT_HILL_RADIUS * EDIT_HILL_RADIUS;
            height += 20.0 * Math.exp(-0.5 * (dx * dx + dz * dz) / radiusSquared);
        }
        return (float) height;
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("regional heightfield preparation interrupted");
        }
    }

    private final class PointSampleView implements HeightfieldSource {
        @Override
        public int getCells() {
            return cells;
        }

        @Override
        public float getSampleSpacing() {
            return SPACING;
        }

        @Override
        public float getHeight(int x, int z) {
            if (((++directSampleCount) & 4095L) == 0) {
                checkInterrupted();
            }
            if (x < -1 || x > cells + 1 || z < -1 || z > cells + 1) {
                throw new IndexOutOfBoundsException("sample is outside the source and its one-fine-sample halo");
            }
            return sampleHeight(x, z);
        }


    }
}
