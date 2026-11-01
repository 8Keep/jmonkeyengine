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
package jme3test.terrain;

import com.jme3.terrain.heightfield.HeightfieldSurfacePage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Deterministic material source for the regional alpine example. One instance
 * owns its heightfield source and is intended for one preparation thread; it
 * must not be shared with rendering or metadata workers.
 */
final class RegionalSurfaceSource implements HeightfieldSurfacePage.Source {
    private static final int TILE_SIDE = 64;
    private static final int HEIGHT_WINDOW_SIDE = TILE_SIDE + 3;
    private static final long HEIGHT_WINDOW_BYTES = (long) HEIGHT_WINDOW_SIDE * HEIGHT_WINDOW_SIDE * Float.BYTES;
    private final int tileLimit;
    private static final int CHANNELS = 9;
    private static final long TILE_BYTES = (long) TILE_SIDE * TILE_SIDE * CHANNELS * Float.BYTES;
    private static final double SPACING = RegionalHeightfieldSource.SPACING;
    private static final double OFFSET_X = 100;
    private static final double OFFSET_Z = 200;

    // Channels: linear albedo RGB, tangent normal XYZ, roughness, semantic ID, detail weight.
    private final int regionCells;
    private final float[] heightWindow = new float[HEIGHT_WINDOW_SIDE * HEIGHT_WINDOW_SIDE];
    private long heightWindowX, heightWindowZ;
    private final RegionalHeightfieldSource heights;
    private final LinkedHashMap<Long, float[]> tiles = new LinkedHashMap<Long, float[]>(80, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, float[]> eldest) {
            return size() > tileLimit;
        }
    };
    private long composedSamples;
    private long peakMaterialTransientBytes;

    static long maximumCacheBytes(int regionCells) {
        return tileLimit(regionCells) * TILE_BYTES + RegionalHeightfieldSource.maximumCacheBytes(regionCells)
                + HEIGHT_WINDOW_BYTES;
    }

    private static int tileLimit(int regionCells) {
        // A stride-8 page with its largest mip gutter touches 10x10 source tiles.
        return regionCells >= 2048 ? 128 : 64;
    }

    RegionalSurfaceSource(long revision) {
        this(RegionalSurfaceCache.REGION_CELLS, revision);
    }

    RegionalSurfaceSource(int regionCells, long revision) {
        if (regionCells != 1024 && regionCells != 2048) {
            throw new IllegalArgumentException("regional surface source supports 1024 or 2048 canonical cells");
        }
        if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
        this.regionCells = regionCells;
        this.tileLimit = tileLimit(regionCells);
        this.heights = new RegionalHeightfieldSource(regionCells, revision);
    }

    @Override
    public void sample(long fineX, long fineZ, HeightfieldSurfacePage.Sample store) {
        if (store == null) throw new IllegalArgumentException("sample output is required");
        checkInterrupted();
        int tx = Math.floorDiv(Math.toIntExact(fineX), TILE_SIDE);
        int tz = Math.floorDiv(Math.toIntExact(fineZ), TILE_SIDE);
        long key = ((long) tz << 32) ^ (tx & 0xffffffffL);
        float[] tile = tiles.get(key);
        if (tile == null) {
            tile = loadTile(tx, tz);
            tiles.put(key, tile);
        }
        int lx = Math.floorMod(Math.toIntExact(fineX), TILE_SIDE);
        int lz = Math.floorMod(Math.toIntExact(fineZ), TILE_SIDE);
        int p = (lz * TILE_SIDE + lx) * CHANNELS;
        store.albedoR = tile[p];
        store.albedoG = tile[p + 1];
        store.albedoB = tile[p + 2];
        store.normalX = tile[p + 3];
        store.normalY = tile[p + 4];
        store.normalZ = tile[p + 5];
        store.roughness = tile[p + 6];
        store.semanticId = Math.round(tile[p + 7]);
        store.detailWeight = tile[p + 8];
    }

    long getCachedBytes() {
        return (long) tiles.size() * TILE_BYTES + heights.getCachedFloatBytes() + HEIGHT_WINDOW_BYTES;
    }

    /**
     * Conservative high-water bound for both caches together. It adds the
     * largest retained-plus-loading material tile set to the height source's
     * largest retained-plus-loading tile set; the component peaks may have
     * happened at different times, so this can exceed a measured instant.
     */
    long getPeakCachedBytes() {
        return peakMaterialTransientBytes + heights.getPeakTransientFloatBytes() + HEIGHT_WINDOW_BYTES;
    }

    long getComposedSamples() {
        return composedSamples;
    }

    private float[] loadTile(int tileX, int tileZ) {
        checkInterrupted();
        float[] data = new float[TILE_SIDE * TILE_SIDE * CHANNELS];
        peakMaterialTransientBytes = Math.max(peakMaterialTransientBytes,
                (long) (tiles.size() + 1) * TILE_BYTES);
        // Share the height stencil across all cell-center samples in this tile,
        // rather than performing twenty height-cache lookups for every material.
        heightWindowX = (long) tileX * TILE_SIDE - 1;
        heightWindowZ = (long) tileZ * TILE_SIDE - 1;
        for (int z = 0; z < HEIGHT_WINDOW_SIDE; z++) {
            checkInterrupted();
            int gz = (int) Math.max(0, Math.min(regionCells, heightWindowZ + z));
            for (int x = 0; x < HEIGHT_WINDOW_SIDE; x++) {
                int gx = (int) Math.max(0, Math.min(regionCells, heightWindowX + x));
                heightWindow[z * HEIGHT_WINDOW_SIDE + x] = heights.getHeight(gx, gz);
            }
        }
        for (int z = 0; z < TILE_SIDE; z++) {
            if ((z & 3) == 0) checkInterrupted();
            for (int x = 0; x < TILE_SIDE; x++) {
                int gx = tileX * TILE_SIDE + x;
                int gz = tileZ * TILE_SIDE + z;
                compose(gx, gz, data, (z * TILE_SIDE + x) * CHANNELS);
            }
        }
        return data;
    }

    private void compose(int x, int z, float[] out, int p) {
        double worldX = x * SPACING + OFFSET_X;
        double worldZ = z * SPACING + OFFSET_Z;
        double centerX = x + .5;
        double centerZ = z + .5;
        double h = heightAt(centerX, centerZ);
        double dx = (heightAt(centerX + 1, centerZ) - heightAt(centerX - 1, centerZ)) / (2 * SPACING);
        double dz = (heightAt(centerX, centerZ + 1) - heightAt(centerX, centerZ - 1)) / (2 * SPACING);
        double slope = Math.hypot(dx, dz);

        double broad = noise(worldX * .014 + 31, worldZ * .014 - 17);
        double patch = noise(worldX * .061 - 9, worldZ * .061 + 23);
        double grain = noise(worldX * .31 + 5, worldZ * .31 - 41);
        double snowLine = 190 + 55 * broad + 8 * patch;
        double snow = smoothstep(snowLine - 12, snowLine + 11, h)
                * (1 - smoothstep(.65, 1.4, slope));
        double rock = smoothstep(.48, 1.30, slope) * (1 - snow);
        double soilBand = (1 - smoothstep(48, 94, h)) * (1 - rock) * (1 - snow);
        double meadow = Math.max(0, 1 - Math.max(rock, Math.max(snow, soilBand * .88)));
        double total = meadow + rock + soilBand + snow;
        meadow /= total; rock /= total; soilBand /= total; snow /= total;

        double variation = .88 + .19 * patch + .07 * grain;
        out[p] = (float) clamp01((meadow * .20 + soilBand * .30 + rock * .34 + snow * .76) * variation);
        out[p + 1] = (float) clamp01((meadow * .35 + soilBand * .22 + rock * .33 + snow * .79) * variation);
        out[p + 2] = (float) clamp01((meadow * .12 + soilBand * .11 + rock * .28 + snow * .84) * variation);
        int semantic = snow > .45 ? 3 : rock > .45 ? 1 : soilBand > .45 ? 2 : 0;
        out[p + 7] = semantic;

        double microX = (noise(worldX * .55 + .13, worldZ * .55)
                - noise(worldX * .55 - .13, worldZ * .55)) * .24;
        double microZ = (noise(worldX * .55, worldZ * .55 + .13)
                - noise(worldX * .55, worldZ * .55 - .13)) * .24;
        double inverse = 1 / Math.sqrt(microX * microX + 1 + microZ * microZ);
        out[p + 3] = (float) (microX * inverse);
        out[p + 4] = (float) inverse;
        out[p + 5] = (float) (microZ * inverse);
        out[p + 6] = (float) clamp01(.86 - rock * .12 - snow * .19 + (grain - .5) * .05);
        out[p + 8] = (float) rock;
        composedSamples++;
    }

    /** Bilinear height lookup at fine-lattice cell centers, clamped to terrain. */
    private double heightAt(double cellX, double cellZ) {
        double x = Math.max(0, Math.min(regionCells, cellX));
        double z = Math.max(0, Math.min(regionCells, cellZ));
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        int x1 = Math.min(x0 + 1, regionCells);
        int z1 = Math.min(z0 + 1, regionCells);
        double fx = x - x0, fz = z - z0;
        int wx0 = windowIndex(x0, heightWindowX), wx1 = windowIndex(x1, heightWindowX);
        int wz0 = windowIndex(z0, heightWindowZ), wz1 = windowIndex(z1, heightWindowZ);
        double a = heightWindow[wz0 * HEIGHT_WINDOW_SIDE + wx0] * (1 - fx)
                + heightWindow[wz0 * HEIGHT_WINDOW_SIDE + wx1] * fx;
        double b = heightWindow[wz1 * HEIGHT_WINDOW_SIDE + wx0] * (1 - fx)
                + heightWindow[wz1 * HEIGHT_WINDOW_SIDE + wx1] * fx;
        return a * (1 - fz) + b * fz;
    }

    private static int windowIndex(int coordinate, long origin) {
        // Entirely out-of-region tiles have the same clamped edge height.
        return (int) Math.max(0, Math.min(HEIGHT_WINDOW_SIDE - 1, coordinate - origin));
    }

    private static double smoothstep(double low, double high, double value) {
        double t = clamp01((value - low) / (high - low));
        return t * t * (3 - 2 * t);
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double noise(double x, double z) {
        long ix = (long) Math.floor(x), iz = (long) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
        double a = hash(ix, iz) * (1 - fx) + hash(ix + 1, iz) * fx;
        double b = hash(ix, iz + 1) * (1 - fx) + hash(ix + 1, iz + 1) * fx;
        return a * (1 - fz) + b * fz;
    }

    private static double hash(long x, long z) {
        long h = x * 0x9e3779b97f4a7c15L ^ z * 0xc2b2ae3d27d4eb4fL;
        h ^= h >>> 30; h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 27; h *= 0x94d049bb133111ebL; h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("regional surface preparation interrupted");
        }
    }
}
