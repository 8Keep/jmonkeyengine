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

import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldLod;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldReconstruction;
import com.jme3.terrain.heightfield.HeightfieldResidency;
import com.jme3.terrain.heightfield.HeightfieldSource;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finite headless example of metadata and page extraction from a regional
 * source. The large source keeps only a bounded tile cache; it never allocates
 * a full height array. This is a CPU reference example, not renderer
 * integration or a measurement of the JVM object graph. Existing native
 * renderer integration remains bounded to 85 metadata nodes and is not
 * demonstrated by this regional 1365-node example.
 */
public final class TestHeightfieldRegionalSource {
    private static final int CELLS = 1024;
    private static final int PATCH_CELLS = 32;
    private static final int TILE_CELLS = 64;
    private static final int TILE_LIMIT = 64;
    private static final float SPACING = .5f;
    private static final long REVISION = 17;

    private TestHeightfieldRegionalSource() {
    }

    public static void main(String[] args) {
        long started = System.nanoTime();
        TiledSource source = new TiledSource();
        HeightfieldHierarchy hierarchy = HeightfieldHierarchy.fromSource(source, PATCH_CELLS, REVISION, 0);
        long constructionNanos = System.nanoTime() - started;
        require(!hierarchy.hasCanonicalPage(), "metadata hierarchy retained a dense page");
        require(hierarchy.getNodes().size() == 1365, "unexpected 1024/32 hierarchy node count");
        require(hierarchy.getSourceRevision() == REVISION, "metadata revision changed");

        verifyDenseControl(source);
        verifySelectionAndTransitions(hierarchy);
        HeightfieldPage west = HeightfieldPage.fromSource(source, 0, 0, PATCH_CELLS, 1, true);
        HeightfieldPage east = HeightfieldPage.fromSource(source, PATCH_CELLS, 0, PATCH_CELLS, 1, true);
        verifyAdjacentPages(west, east);
        HeightfieldPage coarseWest = HeightfieldPage.fromSource(source, 0, 0, PATCH_CELLS, 4, true);
        HeightfieldPage coarseEast = HeightfieldPage.fromSource(source, PATCH_CELLS * 4, 0,
                PATCH_CELLS, 4, true);
        HeightfieldPage fineWest = HeightfieldPage.fromSource(source, 0, 0, PATCH_CELLS * 4, 1, true);
        HeightfieldPage fineEast = HeightfieldPage.fromSource(source, PATCH_CELLS * 4, 0,
                PATCH_CELLS * 4, 1, true);
        verifyCoarseAdjacentPages(coarseWest, coarseEast, fineWest, fineEast);
        verifyResidency(west, east, hierarchy.getSourceRevision());

        long cacheBytes = source.cachedFloatPayloadBytes();
        require(source.peakTiles <= TILE_LIMIT, "tile cache exceeded its bound");
        require(source.peakTransientTiles == TILE_LIMIT + 1, "loader transient peak was not 65 tiles");
        System.out.println("PASS regional source: construction=" + constructionNanos + "ns"
                + ", metadataNodes=" + hierarchy.getNodes().size()
                + ", tileLoads=" + source.tileLoads + ", cachedFloatPayloadBytes=" + cacheBytes
                + ", transientLoaderPeakTiles=" + source.peakTransientTiles
                + ", retainedDenseHeightBytes=0");
    }

    private static void verifyDenseControl(HeightfieldSource source) {
        int cells = 128;
        float[] samples = new float[(cells + 3) * (cells + 3)];
        for (int z = -1; z <= cells + 1; z++) {
            for (int x = -1; x <= cells + 1; x++) {
                samples[(z + 1) * (cells + 3) + x + 1] = source.getHeight(x, z);
            }
        }
        HeightfieldPage densePage = new HeightfieldPage(cells, SPACING, samples);
        HeightfieldHierarchy dense = new HeightfieldHierarchy(densePage, 16, 0);
        HeightfieldHierarchy metadata = HeightfieldHierarchy.fromSource(densePage, 16, REVISION, 0);
        require(metadata.getNodes().size() == dense.getNodes().size(), "dense control topology mismatch");
        for (int i = 0; i < dense.getNodes().size(); i++) {
            HeightfieldHierarchy.Node a = dense.getNodes().get(i);
            HeightfieldHierarchy.Node b = metadata.getNodes().get(i);
            require(a.getOriginX() == b.getOriginX() && a.getOriginZ() == b.getOriginZ()
                    && a.getSpan() == b.getSpan() && a.getStride() == b.getStride(),
                    "dense control node mismatch at " + i);
            require(close(a.getStableError(), b.getStableError()) && close(a.getMorphError(), b.getMorphError()),
                    "dense control error mismatch at " + i);
        }
        for (int stride : new int[]{1, 2, 4, 8}) {
            double a = HeightfieldReconstruction.height(densePage, 37.25, 54.5, stride);
            double b = HeightfieldReconstruction.height(source, 37.25, 54.5, stride);
            require(Math.abs(a - b) < 1e-6, "dense control reconstruction mismatch");
        }
    }

    private static void verifySelectionAndTransitions(HeightfieldHierarchy hierarchy) {
        Camera camera = new Camera(800, 600);
        camera.setFrustumPerspective(60, 800f / 600, .5f, 1200);
        camera.setLocation(new Vector3f(256, 180, 256));
        camera.lookAt(new Vector3f(256, 0, 256), Vector3f.UNIT_Z);
        HeightfieldLod lod = new HeightfieldLod(hierarchy);
        HeightfieldLod.Selection desired = lod.select(camera, 2.0, 64, node -> true);
        require(!desired.getLeaves().isEmpty() && desired.getLeaves().size() <= 64,
                "CPU LOD selection exceeded its patch budget");
        HeightfieldLod.Transition transition = new HeightfieldLod.Transition(hierarchy);
        boolean settled = false;
        for (int step = 0; step < 1024; step++) {
            transition.advance(desired, .25f);
            verifyCover(transition.getPatches(), hierarchy.getCells());
            if (!transition.isTransitioning() && sameNodes(transition.getPatches(), desired.getLeaves())) {
                settled = true;
                break;
            }
        }
        require(settled, "CPU transitions did not settle to the selected cover within 1024 steps");
        require(!transition.getPatches().isEmpty(), "CPU transition produced no patches");
    }

    private static void verifyCover(List<HeightfieldLod.Patch> patches, int cells) {
        long area = 0;
        for (int i = 0; i < patches.size(); i++) {
            HeightfieldHierarchy.Node a = patches.get(i).getNode();
            require(a.getOriginX() >= 0 && a.getOriginZ() >= 0
                    && a.getOriginX() + a.getSpan() <= cells
                    && a.getOriginZ() + a.getSpan() <= cells, "transition patch escaped the region");
            area += (long) a.getSpan() * a.getSpan();
            for (int j = i + 1; j < patches.size(); j++) {
                HeightfieldHierarchy.Node b = patches.get(j).getNode();
                boolean overlapX = Math.max(a.getOriginX(), b.getOriginX())
                        < Math.min(a.getOriginX() + a.getSpan(), b.getOriginX() + b.getSpan());
                boolean overlapZ = Math.max(a.getOriginZ(), b.getOriginZ())
                        < Math.min(a.getOriginZ() + a.getSpan(), b.getOriginZ() + b.getSpan());
                require(!(overlapX && overlapZ), "transition patches overlap");
            }
        }
        require(area == (long) cells * cells, "transition patches do not cover the complete region");
    }

    private static boolean sameNodes(List<HeightfieldLod.Patch> patches,
            List<HeightfieldHierarchy.Node> nodes) {
        Set<HeightfieldHierarchy.Node> actual = new HashSet<>();
        Set<HeightfieldHierarchy.Node> expected = new HashSet<>(nodes);
        for (HeightfieldLod.Patch patch : patches) actual.add(patch.getNode());
        return actual.equals(expected) && actual.size() == patches.size();
    }

    private static void verifyAdjacentPages(HeightfieldPage west, HeightfieldPage east) {
        require(west.getCells() == PATCH_CELLS && east.getCells() == PATCH_CELLS,
                "regional page dimensions mismatch");
        require(west.hasSourceGradients() && east.hasSourceGradients(), "canonical gradients missing");
        for (int z = -1; z <= PATCH_CELLS + 1; z++) {
            require(west.getHeight(PATCH_CELLS, z) == east.getHeight(0, z), "neighbor edge height mismatch");
            require(west.getSourceGradientX(PATCH_CELLS, z) == east.getSourceGradientX(0, z)
                    && west.getSourceGradientZ(PATCH_CELLS, z) == east.getSourceGradientZ(0, z),
                    "neighbor edge gradient mismatch");
        }
        require(west.getHeight(PATCH_CELLS + 1, 0) == east.getHeight(1, 0), "neighbor halo mismatch");
        require(west.getSourceGradientX(PATCH_CELLS + 1, 0) == east.getSourceGradientX(1, 0)
                && west.getSourceGradientZ(PATCH_CELLS + 1, 0) == east.getSourceGradientZ(1, 0),
                "neighbor halo gradient mismatch");
    }

    private static void verifyCoarseAdjacentPages(HeightfieldPage west, HeightfieldPage east,
            HeightfieldPage fineWest, HeightfieldPage fineEast) {
        require(west.getSampleSpacing() == SPACING * 4 && east.getSampleSpacing() == SPACING * 4,
                "stride-four page spacing mismatch");
        for (int z = 0; z <= PATCH_CELLS; z++) {
            require(west.getHeight(PATCH_CELLS, z) == east.getHeight(0, z),
                    "stride-four neighbor edge height mismatch");
            require(west.getSourceGradientX(PATCH_CELLS, z) == east.getSourceGradientX(0, z)
                    && west.getSourceGradientZ(PATCH_CELLS, z) == east.getSourceGradientZ(0, z),
                    "stride-four neighbor edge gradient mismatch");
            require(west.getSourceGradientX(PATCH_CELLS, z) == fineWest.getSourceGradientX(PATCH_CELLS * 4, z * 4)
                    && west.getSourceGradientZ(PATCH_CELLS, z) == fineWest.getSourceGradientZ(PATCH_CELLS * 4, z * 4),
                    "stride-four gradients are not canonical source gradients");
            require(east.getSourceGradientX(0, z) == fineEast.getSourceGradientX(0, z * 4)
                    && east.getSourceGradientZ(0, z) == fineEast.getSourceGradientZ(0, z * 4),
                    "stride-four east gradients are not canonical source gradients");
        }
    }

    private static void verifyResidency(HeightfieldPage first, HeightfieldPage second, long revision) {
        long bytes = Math.max(first.getPayloadBytes(), second.getPayloadBytes());
        HeightfieldResidency.Limits limits = new HeightfieldResidency.Limits(2, 2, bytes * 2,
                bytes * 2, 2, bytes * 2, 0, 0);
        try (HeightfieldResidency residency = new HeightfieldResidency(PATCH_CELLS, bytes, limits)) {
            HeightfieldResidency.PageKey firstKey = new HeightfieldResidency.PageKey(revision, 0, 0, 1);
            HeightfieldResidency.PageKey secondKey = new HeightfieldResidency.PageKey(revision, PATCH_CELLS, 0, 1);
            require(firstKey.revision == secondKey.revision && revision == REVISION,
                    "metadata and page keys do not share a source revision");
            HeightfieldResidency.Request request = residency.request(firstKey);
            require(request != null && residency.complete(request, first), "page preparation failed");
            require(residency.uploadPrepared((slot, page) -> { }) == 1, "page upload did not run");
            require(residency.ready(firstKey) != null, "uploaded page is not resident");
            HeightfieldResidency.Request secondRequest = residency.request(secondKey);
            require(secondRequest != null && residency.complete(secondRequest, second), "second page preparation failed");
            require(residency.uploadPrepared((slot, page) -> { }) == 1, "second page upload did not run");
            require(residency.evict(firstKey), "page eviction failed");
            HeightfieldResidency.Request cancelled = residency.request(firstKey);
            require(cancelled != null && cancelled.getKey().revision == REVISION,
                    "revision-17 request was not admitted");
            require(residency.cancel(cancelled), "request cancellation failed");
            require(!residency.complete(cancelled, first), "late completion after cancellation was accepted");
        }
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= 1e-6 * Math.max(1, Math.max(Math.abs(a), Math.abs(b)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    /** Deterministic analytic source, stored as a bounded LRU of 64x64 tiles. */
    private static final class TiledSource implements HeightfieldSource {
        private static final long ORIGIN_X = 9_007_199_254_741_000L;
        private static final long ORIGIN_Z = -9_007_199_254_741_000L;
        private final LinkedHashMap<Long, float[]> tiles = new LinkedHashMap<Long, float[]>(80, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, float[]> eldest) {
                return size() > TILE_LIMIT;
            }
        };
        private int tileLoads;
        private int peakTiles;
        private int peakTransientTiles;

        @Override public int getCells() { return CELLS; }
        @Override public float getSampleSpacing() { return SPACING; }

        @Override public float getHeight(int x, int z) {
            if (x < -1 || x > CELLS + 1 || z < -1 || z > CELLS + 1) {
                throw new IndexOutOfBoundsException("outside regional source and its one-sample halo");
            }
            int tileX = Math.floorDiv(x, TILE_CELLS);
            int tileZ = Math.floorDiv(z, TILE_CELLS);
            long key = ((long) tileZ << 32) ^ (tileX & 0xffffffffL);
            float[] tile = tiles.get(key);
            if (tile == null) {
                tile = loadTile(tileX, tileZ);
                peakTransientTiles = Math.max(peakTransientTiles, tiles.size() + 1);
                tiles.put(key, tile);
                tileLoads++;
                peakTiles = Math.max(peakTiles, tiles.size());
            }
            int localX = Math.floorMod(x, TILE_CELLS);
            int localZ = Math.floorMod(z, TILE_CELLS);
            return tile[localZ * TILE_CELLS + localX];
        }

        private long cachedFloatPayloadBytes() {
            return (long) tiles.size() * TILE_CELLS * TILE_CELLS * Float.BYTES;
        }

        private float[] loadTile(int tileX, int tileZ) {
            float[] values = new float[TILE_CELLS * TILE_CELLS];
            for (int z = 0; z < TILE_CELLS; z++) {
                for (int x = 0; x < TILE_CELLS; x++) {
                    int localX = tileX * TILE_CELLS + x;
                    int localZ = tileZ * TILE_CELLS + z;
                    // Keep world origins as long integers. Terrain math uses the local lattice,
                    // so large translations never lose neighboring-sample precision.
                    long globalX = ORIGIN_X + localX;
                    long globalZ = ORIGIN_Z + localZ;
                    double u = (globalX - ORIGIN_X) / 37.0;
                    double v = (globalZ - ORIGIN_Z) / 43.0;
                    values[z * TILE_CELLS + x] = (float) (12 * Math.sin(u) + 7 * Math.cos(v)
                            + 3 * Math.sin((u + v) * .7));
                }
            }
            return values;
        }
    }
}
