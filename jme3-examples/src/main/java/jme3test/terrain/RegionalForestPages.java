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
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.terrain.heightfield.HeightfieldSource;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * Deterministic, bounded cache of source-local conifer placement pages. The
 * source revision and suitability rules are fixed for this cache's lifetime.
 * Calls are confined to the thread that constructs the instance.
 */
public final class RegionalForestPages {
    public static final float PAGE_SIZE_METRES = 128f;
    public static final int RECORD_COMPONENTS = 6; // fineX, fineZ, scale, cosYaw, sinYaw, tint

    private static final float[] GROVES = {
        .17f, .22f, .34f, .55f, .64f, .26f, .80f, .72f,
        .42f, .84f, .72f, .48f, .20f, .78f, .53f, .42f
    };
    private static final float[] GROVE_COS = new float[GROVES.length / 2];
    private static final float[] GROVE_SIN = new float[GROVES.length / 2];
    private static final float[] NO_RECORDS = new float[0];
    private static final float DENSE_MAJOR_RADIUS = 1.12f;
    private static final float DENSE_MINOR_RADIUS = .72f;
    private static final float DENSE_WARP_AXIS_BOUND = 32f;

    private final RegionalHeightfieldSource source;
    private final HeightfieldSource pointSamples;
    private final int sourceCells;
    private final float spacing;
    private final float siteStep;
    private final float worldSize;
    private final int gridCount;
    private final int pagesPerAxis;
    private final int maxResidentPages;
    private final int maxResidentTrees;
    private final Page[] pages;
    private final byte[] support;
    private final Page[] emptyPages;
    private final boolean[] demanded;
    private final long[] lastUse;
    private final boolean[] impossible;
    private final int[] knownTreeCounts;
    private final float[] candidateScratch;
    private final float[] denseValues = new float[4];

    private int residentPageCount;
    private int residentTreeCount;
    private int demandedPageCount;
    private long primitivePayloadBytes;
    private long clock;
    private long loads;
    private long evictions;
    private long budgetLimited;

    static {
        for (int grove = 0; grove < GROVE_COS.length; grove++) {
            double angle = unit(mix(0x6a09e667f3bcc909L + grove * 0x9e3779b97f4a7c15L))
                    * Math.PI * 2;
            GROVE_COS[grove] = (float) Math.cos(angle);
            GROVE_SIN[grove] = (float) Math.sin(angle);
        }
    }

    /**
     * @param source immutable regional source for one revision
     * @param siteStep spacing of the deterministic candidate grid, at least 6m
     * @param maxResidentPages maximum nonempty resident pages
     * @param maxResidentTrees maximum resident trees across all pages
     */
    public RegionalForestPages(RegionalHeightfieldSource source, float siteStep,
            int maxResidentPages, int maxResidentTrees) {
        if (source == null || !Float.isFinite(siteStep) || siteStep < 6f
                || maxResidentPages < 1 || maxResidentTrees < 1) {
            throw new IllegalArgumentException("source, siteStep >= 6, and positive cache limits are required");
        }
        this.source = source;
        this.sourceCells = source.getCells();
        this.spacing = source.getSampleSpacing();
        if (sourceCells < 1 || sourceCells > RegionalHeightfieldSource.FAR_CELLS
                || !Float.isFinite(spacing) || spacing <= 0f) {
            throw new IllegalArgumentException("unsupported regional source dimensions");
        }
        worldSize = sourceCells * spacing;
        double pageCount = worldSize / PAGE_SIZE_METRES;
        if (Math.abs(pageCount - Math.rint(pageCount)) > 1e-6 || pageCount < 1
                || pageCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("source extent must be an integer number of 128m pages");
        }
        this.siteStep = siteStep;
        this.gridCount = (int) Math.ceil(worldSize / siteStep);
        this.pagesPerAxis = (int) Math.rint(pageCount);
        long pageSlots = (long) pagesPerAxis * pagesPerAxis;
        if (pageSlots > Integer.MAX_VALUE) throw new IllegalArgumentException("source page grid is too large");
        this.maxResidentPages = maxResidentPages;
        this.maxResidentTrees = maxResidentTrees;
        this.pages = new Page[(int) pageSlots];
        this.support = new byte[(int) pageSlots];
        this.emptyPages = new Page[(int) pageSlots];
        this.demanded = new boolean[(int) pageSlots];
        this.lastUse = new long[(int) pageSlots];
        this.impossible = new boolean[(int) pageSlots];
        this.knownTreeCounts = new int[(int) pageSlots];
        Arrays.fill(knownTreeCounts, -1);
        int candidatesPerAxis = (int) Math.ceil(PAGE_SIZE_METRES / siteStep)
                + 12 + (int) Math.ceil(2 * spacing / siteStep);
        long scratchComponents = (long) candidatesPerAxis * candidatesPerAxis * RECORD_COMPONENTS;
        if (scratchComponents > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("siteStep makes per-page candidate scratch too large");
        }
        candidateScratch = new float[(int) scratchComponents];
        pointSamples = source.pageSamples(2);
    }

    /** Start a demand epoch. Existing pages become evictable until requested. */
    public void beginDemand() {
        Arrays.fill(demanded, false);
        demandedPageCount = 0;
    }

    /**
     * Request one source-local 128m page. A returned empty page is a cached
     * result too. Returns {@code null} when limits are occupied by pages already
     * requested during this demand epoch.
     */
    public Page request(int cellX, int cellZ) {
        int index = pageIndex(cellX, cellZ);
        if (impossible[index]) {
            budgetLimited++;
            return null;
        }
        Page cached = pages[index];
        if (cached != null) {
            protect(index);
            return cached;
        }
        if (emptyPages[index] != null) {
            protect(index);
            return emptyPages[index];
        }
        if (!mayContainTrees(cellX, cellZ)) {
            Page empty = new Page(cellX, cellZ, NO_RECORDS);
            emptyPages[index] = empty;
            knownTreeCounts[index] = 0;
            protect(index);
            return empty;
        }
        if (knownTreeCounts[index] > 0 && protectedBudgetCannotFit(knownTreeCounts[index])) {
            budgetLimited++;
            return null;
        }
        if (protectedCapacityBlocksNonemptyPage()) {
            budgetLimited++;
            return null;
        }

        Page prepared = buildPage(cellX, cellZ);
        loads++;
        int treeCount = prepared.getTreeCount();
        knownTreeCounts[index] = treeCount;
        if (treeCount == 0) {
            emptyPages[index] = prepared;
            protect(index);
            return prepared;
        }
        if (treeCount > maxResidentTrees) {
            impossible[index] = true;
            budgetLimited++;
            return null;
        }
        if (!makeRoom(treeCount)) {
            budgetLimited++;
            return null;
        }
        pages[index] = prepared;
        residentPageCount++;
        residentTreeCount += treeCount;
        primitivePayloadBytes += (long) prepared.records.length * Float.BYTES;
        protect(index);
        return prepared;
    }

    /**
     * Cheap conservative test against the eight deterministic grove support
     * circles. It includes the half-sample displacement between the unrounded
     * jittered point and its rounded page-ownership sample.
     */
    public boolean mayContainTrees(int cellX, int cellZ) {
        int index = pageIndex(cellX, cellZ);
        if (support[index] != 0) return support[index] == 2;
        double padding = spacing * .5;
        double minX = cellX * (double) PAGE_SIZE_METRES - padding;
        double minZ = cellZ * (double) PAGE_SIZE_METRES - padding;
        double maxX = Math.min(worldSize, (cellX + 1.0) * PAGE_SIZE_METRES) + padding;
        double maxZ = Math.min(worldSize, (cellZ + 1.0) * PAGE_SIZE_METRES) + padding;
        float legacyRadius = Math.max(28f, worldSize * .035f);
        double supportRadius = legacyRadius;
        if (siteStep < 14f) {
            // The dense field is an oriented ellipse plus a two-axis domain
            // warp, whose each component is bounded by 32 metres.
            supportRadius = legacyRadius * DENSE_MAJOR_RADIUS
                    + Math.sqrt(2.0) * DENSE_WARP_AXIS_BOUND;
        }
        // Pad the legacy float center/subtraction and squared-distance
        // roundoff so this prefilter cannot exclude an accepted candidate.
        double radius = Math.nextUp(supportRadius
                + 4.0 * Math.ulp(worldSize) + 4.0 * Math.ulp(legacyRadius));
        double radiusSquared = radius * radius;
        for (int grove = 0; grove < GROVES.length; grove += 2) {
            double centerX = (float) (GROVES[grove] * worldSize);
            double centerZ = (float) (GROVES[grove + 1] * worldSize);
            double dx = centerX < minX ? minX - centerX : centerX > maxX ? centerX - maxX : 0;
            double dz = centerZ < minZ ? minZ - centerZ : centerZ > maxZ ? centerZ - maxZ : 0;
            if (dx * dx + dz * dz <= radiusSquared) {
                support[index] = 2;
                return true;
            }
        }
        support[index] = 1;
        return false;
    }

    /** Return a cached page without generating, protecting, or changing its LRU age. */
    public Page peek(int cellX, int cellZ) {
        int index = pageIndex(cellX, cellZ);
        Page page = pages[index];
        return page != null ? page : emptyPages[index];
    }

    private boolean protectedBudgetCannotFit(int newTreeCount) {
        int protectedPages = 0;
        int protectedTrees = 0;
        for (int i = 0; i < pages.length; i++) {
            Page page = pages[i];
            if (demanded[i] && page != null && page.getTreeCount() > 0) {
                protectedPages++;
                protectedTrees += page.getTreeCount();
            }
        }
        return protectedPages + 1 > maxResidentPages
                || protectedTrees + newTreeCount > maxResidentTrees;
    }

    private boolean protectedCapacityBlocksNonemptyPage() {
        int protectedPages = 0;
        int protectedTrees = 0;
        for (int i = 0; i < pages.length; i++) {
            Page page = pages[i];
            if (demanded[i] && page != null && page.getTreeCount() > 0) {
                protectedPages++;
                protectedTrees += page.getTreeCount();
            }
        }
        return protectedPages >= maxResidentPages || protectedTrees >= maxResidentTrees;
    }

    private void protect(int index) {
        if (!demanded[index]) {
            demanded[index] = true;
            demandedPageCount++;
        }
        lastUse[index] = ++clock;
    }

    private boolean makeRoom(int newTreeCount) {
        int protectedPages = 0;
        int protectedTrees = 0;
        for (int i = 0; i < pages.length; i++) {
            if (demanded[i] && pages[i] != null && pages[i].getTreeCount() > 0) {
                protectedPages++;
                protectedTrees += pages[i].getTreeCount();
            }
        }
        if (protectedPages + 1 > maxResidentPages || protectedTrees + newTreeCount > maxResidentTrees) {
            return false;
        }
        while (residentPageCount + 1 > maxResidentPages
                || residentTreeCount + newTreeCount > maxResidentTrees) {
            int victim = -1;
            long oldest = Long.MAX_VALUE;
            for (int i = 0; i < pages.length; i++) {
                Page candidate = pages[i];
                if (!demanded[i] && candidate != null && candidate.getTreeCount() > 0
                        && lastUse[i] < oldest) {
                    victim = i;
                    oldest = lastUse[i];
                }
            }
            if (victim < 0) return false;
            Page removed = pages[victim];
            residentPageCount--;
            residentTreeCount -= removed.getTreeCount();
            primitivePayloadBytes -= (long) removed.records.length * Float.BYTES;
            pages[victim] = null;
            evictions++;
        }
        return true;
    }

    private Page buildPage(int cellX, int cellZ) {
        checkInterrupted();
        float minX = cellX * PAGE_SIZE_METRES;
        float minZ = cellZ * PAGE_SIZE_METRES;
        float maxX = Math.min(worldSize, minX + PAGE_SIZE_METRES);
        float maxZ = Math.min(worldSize, minZ + PAGE_SIZE_METRES);
        // Candidate jitter is in [.2,.8) grid cells and rounding moves at most
        // half a sample. The generous one-step padding only broadens the scan;
        // final ownership is decided from the rounded physical sample.
        int fromX = Math.max(0, (int) Math.floor((minX - siteStep - spacing) / siteStep) - 2);
        int fromZ = Math.max(0, (int) Math.floor((minZ - siteStep - spacing) / siteStep) - 2);
        int toX = Math.min(gridCount, (int) Math.ceil((maxX + siteStep + spacing) / siteStep) + 2);
        int toZ = Math.min(gridCount, (int) Math.ceil((maxZ + siteStep + spacing) / siteStep) + 2);
        long candidateSlots = (long) (toX - fromX) * (toZ - fromZ);
        if (candidateSlots * RECORD_COMPONENTS > candidateScratch.length) {
            throw new IllegalStateException("candidate scratch bound was underestimated");
        }
        float[] records = candidateScratch;
        int count = 0;
        float radius = Math.max(28f, worldSize * .035f);
        float radiusSquared = radius * radius;
        for (int z = fromZ; z < toZ; z++) {
            checkInterrupted();
            for (int x = fromX; x < toX; x++) {
                long seed = mix(((long) x << 32) ^ (z & 0xffffffffL));
                if (siteStep >= 14f && unit(seed) > .60f) continue;
                float px = (x + .2f + .6f * unit(mix(seed + 1))) * siteStep;
                float pz = (z + .2f + .6f * unit(mix(seed + 2))) * siteStep;
                if (px < 4 || pz < 4 || px > worldSize - 4 || pz > worldSize - 4) continue;
                int fineX = Math.round(px / spacing);
                int fineZ = Math.round(pz / spacing);
                float sampleX = fineX * spacing;
                float sampleZ = fineZ * spacing;
                if ((int) Math.floor(sampleX / PAGE_SIZE_METRES) != cellX
                        || (int) Math.floor(sampleZ / PAGE_SIZE_METRES) != cellZ) continue;

                float denseScaleFactor = 1f;
                float denseTintFactor = 1f;
                if (siteStep >= 14f) {
                    boolean inGrove = false;
                    for (int grove = 0; grove < GROVES.length; grove += 2) {
                        float dx = px - GROVES[grove] * worldSize;
                        float dz = pz - GROVES[grove + 1] * worldSize;
                        if (dx * dx + dz * dz < radiusSquared) {
                            inGrove = true;
                            break;
                        }
                    }
                    if (!inGrove) continue;
                } else {
                    float globalX = px + source.getWorldOrigin();
                    float globalZ = pz + source.getWorldOrigin();
                    denseField(globalX, globalZ);
                    float density = denseValues[0];
                    denseScaleFactor = denseValues[2];
                    denseTintFactor = denseValues[3];
                    if (!(unit(seed) < density)) continue;
                }
                float height = pointSamples.getHeight(fineX, fineZ);
                float slopeX = (pointSamples.getHeight(fineX + 2, fineZ)
                        - pointSamples.getHeight(fineX - 2, fineZ)) / (4 * spacing);
                float slopeZ = (pointSamples.getHeight(fineX, fineZ + 2)
                        - pointSamples.getHeight(fineX, fineZ - 2)) / (4 * spacing);
                if (height < 12 || height > 185
                        || slopeX * slopeX + slopeZ * slopeZ > .65f * .65f) continue;

                int offset = count++ * RECORD_COMPONENTS;
                records[offset] = fineX;
                records[offset + 1] = fineZ;
                float baseScale = .8f + .35f * unit(mix(seed + 3));
                float baseTint = .85f + .25f * unit(mix(seed + 5));
                if (siteStep >= 14f) {
                    records[offset + 2] = baseScale;
                    records[offset + 5] = baseTint;
                } else {
                    records[offset + 2] = baseScale * denseScaleFactor;
                    records[offset + 5] = baseTint * denseTintFactor;
                }
                double yaw = unit(mix(seed + 4)) * Math.PI * 2;
                records[offset + 3] = (float) Math.cos(yaw);
                records[offset + 4] = (float) Math.sin(yaw);
            }
        }
        return new Page(cellX, cellZ, count == 0 ? NO_RECORDS
                : Arrays.copyOf(records, count * RECORD_COMPONENTS));
    }

    /** Write density, age, scale multiplier, and tint multiplier to denseValues. */
    private void denseField(float worldX, float worldZ) {
        float warpX = 20f * valueNoise(worldX, worldZ, 96f, 0)
                + 9f * valueNoise(worldX, worldZ, 40f, 1)
                + 3f * valueNoise(worldX, worldZ, 16f, 2);
        float warpZ = 20f * valueNoise(worldX, worldZ, 96f, 3)
                + 9f * valueNoise(worldX, worldZ, 40f, 4)
                + 3f * valueNoise(worldX, worldZ, 16f, 5);
        float warpedX = worldX + warpX;
        float warpedZ = worldZ + warpZ;
        float activity = .50f + .26f * valueNoise(worldX, worldZ, 96f, 6)
                + .17f * valueNoise(worldX, worldZ, 40f, 7)
                + .07f * valueNoise(worldX, worldZ, 16f, 8);
        float boundary = .86f + .14f * (.5f + .5f * valueNoise(worldX, worldZ, 48f, 9));
        float ageNoise = .5f + .5f * valueNoise(worldX, worldZ, 96f, 10);
        float bestDensity = 0f;
        float bestAge = 0f;
        float radius = Math.max(28f, worldSize * .035f);
        float major = radius * DENSE_MAJOR_RADIUS;
        float minor = radius * DENSE_MINOR_RADIUS;
        for (int grove = 0; grove < GROVE_COS.length; grove++) {
            float centerX = GROVES[grove * 2] * worldSize + source.getWorldOrigin();
            float centerZ = GROVES[grove * 2 + 1] * worldSize + source.getWorldOrigin();
            float dx = warpedX - centerX;
            float dz = warpedZ - centerZ;
            float along = GROVE_COS[grove] * dx + GROVE_SIN[grove] * dz;
            float across = -GROVE_SIN[grove] * dx + GROVE_COS[grove] * dz;
            float normalized = (float) Math.sqrt((along * along) / (major * major)
                    + (across * across) / (minor * minor)) / boundary;
            if (normalized >= 1f) continue;
            float edgeDensity = 1f - smoothstep(.72f, 1f, normalized);
            float density = .90f * edgeDensity * smoothstep(.18f, .60f, activity);
            if (density > bestDensity) {
                bestDensity = density;
                float coreAge = 1f - smoothstep(.55f, 1f, normalized);
                bestAge = coreAge * (.78f + .22f * ageNoise);
            }
        }
        denseValues[0] = bestDensity;
        denseValues[1] = bestAge;
        denseValues[2] = .78f + .22f * bestAge;
        denseValues[3] = .90f + .10f * bestAge;
    }

    private static float valueNoise(float x, float z, float scale, int channel) {
        double latticeX = x / (double) scale;
        double latticeZ = z / (double) scale;
        int x0 = (int) Math.floor(latticeX);
        int z0 = (int) Math.floor(latticeZ);
        float fx = smoothFraction((float) (latticeX - x0));
        float fz = smoothFraction((float) (latticeZ - z0));
        float a = latticeValue(x0, z0, channel);
        float b = latticeValue(x0 + 1, z0, channel);
        float c = latticeValue(x0, z0 + 1, channel);
        float d = latticeValue(x0 + 1, z0 + 1, channel);
        float north = a + (b - a) * fx;
        float south = c + (d - c) * fx;
        return north + (south - north) * fz;
    }

    private static float smoothFraction(float value) {
        return value * value * (3f - 2f * value);
    }

    private static float latticeValue(int x, int z, int channel) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL)
                ^ (long) (channel + 1) * 0x9e3779b97f4a7c15L;
        return 2f * unit(mix(key)) - 1f;
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0f, Math.min(1f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    private int pageIndex(int cellX, int cellZ) {
        if (cellX < 0 || cellX >= pagesPerAxis || cellZ < 0 || cellZ >= pagesPerAxis) {
            throw new IndexOutOfBoundsException("forest page is outside the source extent");
        }
        return cellZ * pagesPerAxis + cellX;
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("forest placement page generation interrupted");
        }
    }

    private static float unit(long value) { return (float) ((value >>> 40) * 0x1.0p-24); }

    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    public int getPageCountPerAxis() { return pagesPerAxis; }
    public float getPageSizeMetres() { return PAGE_SIZE_METRES; }
    public float getSiteStep() { return siteStep; }
    public long getRevision() { return source.getRevision(); }
    public int getResidentPages() { return residentPageCount; }
    public int getResidentTrees() { return residentTreeCount; }
    public int getDemandedPageCount() { return demandedPageCount; }
    public long getPrimitivePayloadBytes() { return primitivePayloadBytes; }
    public long getLoads() { return loads; }
    public long getEvictions() { return evictions; }
    public long getBudgetLimitedCount() { return budgetLimited; }

    /** Visit admitted pages for this demand epoch in stable row-major order. */
    public void forEachDemandedPage(Consumer<? super Page> visitor) {
        if (visitor == null) throw new IllegalArgumentException("visitor is required");
        for (int index = 0; index < pages.length; index++) {
            if (demanded[index] && pages[index] != null) visitor.accept(pages[index]);
        }
    }

    /** Visit every currently resident nonempty page in stable row-major order. */
    public void forEachResidentPage(Consumer<? super Page> visitor) {
        if (visitor == null) throw new IllegalArgumentException("visitor is required");
        for (Page page : pages) {
            if (page != null && page.getTreeCount() > 0) visitor.accept(page);
        }
    }

    /** Immutable record payload for one deterministic placement cell. */
    public static final class Page {
        private final int cellX;
        private final int cellZ;
        private final float[] records;

        private Page(int cellX, int cellZ, float[] records) {
            this.cellX = cellX;
            this.cellZ = cellZ;
            this.records = records;
        }

        public int getCellX() { return cellX; }
        public int getCellZ() { return cellZ; }
        public int getTreeCount() { return records.length / RECORD_COMPONENTS; }

        public float getRecord(int treeIndex, int component) {
            if (treeIndex < 0 || treeIndex >= getTreeCount()
                    || component < 0 || component >= RECORD_COMPONENTS) {
                throw new IndexOutOfBoundsException("forest page record index out of range");
            }
            return records[treeIndex * RECORD_COMPONENTS + component];
        }

        public void copyRecords(float[] destination, int offset) {
            if (destination == null || offset < 0
                    || (long) offset + records.length > destination.length) {
                throw new IllegalArgumentException("destination does not cover the page records");
            }
            System.arraycopy(records, 0, destination, offset, records.length);
        }

        public float[] copyRecords() { return records.clone(); }
    }
}
