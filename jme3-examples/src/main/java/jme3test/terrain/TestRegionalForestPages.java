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
import java.util.Map;
import java.util.TreeMap;

/** Finite CPU contracts for deterministic regional forest placement pages. */
public final class TestRegionalForestPages {
    private static final float SITE_STEP = 14f;
    private static final float[] GROVES = {
        .17f, .22f, .34f, .55f, .64f, .26f, .80f, .72f,
        .42f, .84f, .72f, .48f, .20f, .78f, .53f, .42f
    };

    private TestRegionalForestPages() { }

    public static void main(String[] args) {
        RegionalHeightfieldSource source = new RegionalHeightfieldSource(
                RegionalHeightfieldSource.FAR_CELLS, 0);
        Map<Long, float[]> expected = legacyScan(source);
        RegionalForestPages all = new RegionalForestPages(source, SITE_STEP, 1024, 20_000);
        all.beginDemand();
        Map<Long, float[]> actual = new TreeMap<>();
        for (int z = 0; z < all.getPageCountPerAxis(); z++) {
            for (int x = 0; x < all.getPageCountPerAxis(); x++) {
                RegionalForestPages.Page page = all.request(x, z);
                check(page != null, "full source scan fits configured cache");
                check(page.getCellX() == x && page.getCellZ() == z, "page identity");
                if (page.getTreeCount() > 0) {
                    check(all.mayContainTrees(x, z), "support test never excludes a populated page");
                }
                for (int tree = 0; tree < page.getTreeCount(); tree++) {
                    float[] record = new float[RegionalForestPages.RECORD_COMPONENTS];
                    for (int component = 0; component < record.length; component++) {
                        record[component] = page.getRecord(tree, component);
                    }
                    long key = key((int) record[0], (int) record[1]);
                    check(actual.put(key, record) == null, "rounded root belongs to exactly one page");
                }
            }
        }
        checkRecords(expected, actual, "whole 4km source equals independent legacy scan");
        check(all.getResidentPages() <= 1024 && all.getResidentTrees() <= 20_000,
                "resident cache obeys both configured limits");
        int[] residentPages = {0};
        int[] residentTrees = {0};
        all.forEachResidentPage(page -> {
            residentPages[0]++;
            residentTrees[0] += page.getTreeCount();
        });
        check(residentPages[0] == all.getResidentPages() && residentTrees[0] == all.getResidentTrees(),
                "resident visitor includes every cached nonempty page exactly once");
        check(all.getPrimitivePayloadBytes() == (long) expected.size()
                * RegionalForestPages.RECORD_COMPONENTS * Float.BYTES,
                "primitive payload byte accounting");

        long emptyKey = -1;
        for (int z = 0; z < all.getPageCountPerAxis() && emptyKey < 0; z++) {
            for (int x = 0; x < all.getPageCountPerAxis(); x++) {
                if (!containsPage(expected, x, z) && !all.mayContainTrees(x, z)) {
                    emptyKey = pageKey(x, z);
                    break;
                }
            }
        }
        check(emptyKey >= 0, "fixture has a page outside all grove supports");
        int emptyX = (int) emptyKey;
        int emptyZ = (int) (emptyKey >>> 32);
        RegionalForestPages supportCache = new RegionalForestPages(source, SITE_STEP, 1, 1);
        supportCache.beginDemand();
        check(supportCache.peek(emptyX, emptyZ) == null, "peek does not generate a page");
        RegionalForestPages.Page empty = supportCache.request(emptyX, emptyZ);
        long loadsAfterEmpty = supportCache.getLoads();
        check(empty != null && empty.getTreeCount() == 0, "empty page is an explicit cached result");
        check(supportCache.request(emptyX, emptyZ) == empty && supportCache.getLoads() == loadsAfterEmpty
                && supportCache.peek(emptyX, emptyZ) == empty,
                "cached empty page is returned without regeneration or allocation");

        int[] nonemptyPages = firstTwoPages(expected);
        int firstX = nonemptyPages[0], firstZ = nonemptyPages[1];
        int secondX = nonemptyPages[2], secondZ = nonemptyPages[3];
        int firstTrees = pageTreeCount(expected, firstX, firstZ);
        int secondTrees = pageTreeCount(expected, secondX, secondZ);
        int maxTrees = Math.max(firstTrees, secondTrees);

        RegionalForestPages pageBound = new RegionalForestPages(source, SITE_STEP, 1,
                firstTrees + secondTrees);
        pageBound.beginDemand();
        RegionalForestPages.Page first = pageBound.request(firstX, firstZ);
        float[] firstCopy = first.copyRecords();
        check(pageBound.request(secondX, secondZ) == null,
                "requested page is protected when page budget is full");
        check(pageBound.getResidentPages() == 1 && pageBound.getResidentTrees() == firstTrees,
                "protected rejection leaves resident data unchanged");
        RegionalForestPages peekCache = new RegionalForestPages(source, SITE_STEP, 1,
                firstTrees + secondTrees);
        peekCache.beginDemand();
        RegionalForestPages.Page peeked = peekCache.request(firstX, firstZ);
        peekCache.beginDemand();
        check(peekCache.peek(firstX, firstZ) == peeked && peekCache.getDemandedPageCount() == 0,
                "peek leaves resident page unprotected and does not change demand state");
        check(peekCache.request(secondX, secondZ) != null && peekCache.getEvictions() == 1,
                "unprotected peeked page remains evictable");
        pageBound.beginDemand();
        RegionalForestPages.Page second = pageBound.request(secondX, secondZ);
        check(second != null && pageBound.getEvictions() == 1,
                "new demand evicts the old unrequested page");
        pageBound.beginDemand();
        RegionalForestPages.Page replay = pageBound.request(firstX, firstZ);
        check(replay != null && Arrays.equals(firstCopy, replay.copyRecords()),
                "eviction and replay reproduce identical records");
        check(pageBound.getResidentPages() <= 1
                && pageBound.getResidentTrees() <= firstTrees + secondTrees,
                "page-bound cache remains bounded after replay");

        RegionalForestPages treeBound = new RegionalForestPages(source, SITE_STEP, 2, maxTrees);
        treeBound.beginDemand();
        check(treeBound.request(firstX, firstZ) != null, "first page fits tree budget");
        check(treeBound.request(secondX, secondZ) == null,
                "protected page prevents exceeding tree budget");
        treeBound.beginDemand();
        check(treeBound.request(secondX, secondZ) != null && treeBound.getEvictions() == 1,
                "tree budget evicts an unrequested page");
        check(treeBound.getResidentTrees() <= maxTrees, "tree-bound cache stays within limit");

        RegionalForestPages dense = new RegionalForestPages(source, 6f, 1024, 8192);
        dense.beginDemand();
        Map<Long, float[]> denseRecords = new TreeMap<>();
        for (int z = 0; z < dense.getPageCountPerAxis(); z++) {
            for (int x = 0; x < dense.getPageCountPerAxis(); x++) {
                RegionalForestPages.Page page = dense.request(x, z);
                check(page != null, "dense full-source scan fits the preset tree budget");
                if (page.getTreeCount() > 0) {
                    check(dense.mayContainTrees(x, z), "dense support retains every populated page");
                }
                for (int tree = 0; tree < page.getTreeCount(); tree++) {
                    float[] record = new float[RegionalForestPages.RECORD_COMPONENTS];
                    for (int component = 0; component < record.length; component++) {
                        record[component] = page.getRecord(tree, component);
                    }
                    check((int) Math.floor(record[0] * RegionalHeightfieldSource.SPACING
                            / RegionalForestPages.PAGE_SIZE_METRES) == x
                            && (int) Math.floor(record[1] * RegionalHeightfieldSource.SPACING
                                    / RegionalForestPages.PAGE_SIZE_METRES) == z,
                            "dense rounded root belongs to its page across seams");
                    check(record[2] <= 1.15f, "dense age scaling preserves the maximum instance scale");
                    long key = key((int) record[0], (int) record[1]);
                    check(denseRecords.put(key, record) == null, "dense roots are unique across page seams");
                }
            }
        }
        check(denseRecords.size() <= 8192, "dense placement stays within the configured tree budget");
        check(dense.getResidentPages() <= 96, "dense nonempty pages fit the preset page budget");
        int[] densePageIds = firstTwoPages(denseRecords);
        int denseFirstCount = pageTreeCount(denseRecords, densePageIds[0], densePageIds[1]);
        RegionalForestPages denseReplay = new RegionalForestPages(source, 6f, 1, 8192);
        denseReplay.beginDemand();
        RegionalForestPages.Page denseFirst = denseReplay.request(densePageIds[0], densePageIds[1]);
        float[] denseFirstRecords = denseFirst.copyRecords();
        denseReplay.beginDemand();
        check(denseReplay.request(densePageIds[2], densePageIds[3]) != null
                && denseReplay.getEvictions() == 1, "dense page budget permits deterministic eviction");
        denseReplay.beginDemand();
        RegionalForestPages.Page denseAgain = denseReplay.request(densePageIds[0], densePageIds[1]);
        check(denseAgain != null && denseAgain.getTreeCount() == denseFirstCount
                && Arrays.equals(denseFirstRecords, denseAgain.copyRecords()),
                "dense page replay is bit deterministic");

        boolean badStep = rejected(() -> new RegionalForestPages(source, 5.99f, 1, 1));
        boolean badBudget = rejected(() -> new RegionalForestPages(source, SITE_STEP, 0, 1));
        boolean badPage = rejected(() -> all.request(-1, 0));
        check(badStep && badBudget && badPage, "invalid constructor and page inputs are rejected");
        check(all.getBudgetLimitedCount() == 0, "unbounded comparison cache did not hit a budget limit");
        System.out.println("REGIONAL_FOREST_PAGES_CPU_PASS legacyTrees=" + expected.size()
                + " pages=" + all.getPageCountPerAxis() * all.getPageCountPerAxis()
                + " payloadBytes=" + all.getPrimitivePayloadBytes());
    }

    /** Independent reproduction of the prior monolithic site scan. */
    private static Map<Long, float[]> legacyScan(RegionalHeightfieldSource source) {
        int sourceCells = source.getCells();
        float spacing = source.getSampleSpacing();
        float size = sourceCells * spacing;
        float radius = Math.max(28f, size * .035f);
        float radiusSquared = radius * radius;
        HeightfieldSource samples = source.pageSamples(2);
        int cells = (int) Math.ceil(size / SITE_STEP);
        Map<Long, float[]> sites = new TreeMap<>();
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                long seed = mix(((long) x << 32) ^ (z & 0xffffffffL));
                if (unit(seed) > .60f) continue;
                float px = (x + .2f + .6f * unit(mix(seed + 1))) * SITE_STEP;
                float pz = (z + .2f + .6f * unit(mix(seed + 2))) * SITE_STEP;
                if (px < 4 || pz < 4 || px > size - 4 || pz > size - 4) continue;
                boolean inGrove = false;
                for (int g = 0; g < GROVES.length; g += 2) {
                    float dx = px - GROVES[g] * size, dz = pz - GROVES[g + 1] * size;
                    if (dx * dx + dz * dz < radiusSquared) { inGrove = true; break; }
                }
                if (!inGrove) continue;
                int fineX = Math.round(px / spacing), fineZ = Math.round(pz / spacing);
                float height = samples.getHeight(fineX, fineZ);
                float slopeX = (samples.getHeight(fineX + 2, fineZ)
                        - samples.getHeight(fineX - 2, fineZ)) / (4 * spacing);
                float slopeZ = (samples.getHeight(fineX, fineZ + 2)
                        - samples.getHeight(fineX, fineZ - 2)) / (4 * spacing);
                if (height < 12 || height > 185
                        || slopeX * slopeX + slopeZ * slopeZ > .65f * .65f) continue;
                double yaw = unit(mix(seed + 4)) * Math.PI * 2;
                float[] record = {
                    fineX, fineZ, .8f + .35f * unit(mix(seed + 3)),
                    (float) Math.cos(yaw), (float) Math.sin(yaw),
                    .85f + .25f * unit(mix(seed + 5))
                };
                check(sites.put(key(fineX, fineZ), record) == null,
                        "legacy scan itself has unique quantized roots");
            }
        }
        return sites;
    }

    private static int[] firstTwoPages(Map<Long, float[]> records) {
        int[] result = new int[4];
        int found = 0;
        int previousX = Integer.MIN_VALUE, previousZ = Integer.MIN_VALUE;
        for (float[] record : records.values()) {
            int x = (int) Math.floor((record[0] * RegionalHeightfieldSource.SPACING)
                    / RegionalForestPages.PAGE_SIZE_METRES);
            int z = (int) Math.floor((record[1] * RegionalHeightfieldSource.SPACING)
                    / RegionalForestPages.PAGE_SIZE_METRES);
            if (x == previousX && z == previousZ) continue;
            if (found == 0) {
                result[0] = x; result[1] = z;
                found++;
                previousX = x; previousZ = z;
            } else {
                result[2] = x; result[3] = z;
                return result;
            }
        }
        throw new AssertionError("expected at least two nonempty pages");
    }

    private static int pageTreeCount(Map<Long, float[]> records, int pageX, int pageZ) {
        int count = 0;
        for (float[] record : records.values()) {
            int x = (int) Math.floor(record[0] * RegionalHeightfieldSource.SPACING
                    / RegionalForestPages.PAGE_SIZE_METRES);
            int z = (int) Math.floor(record[1] * RegionalHeightfieldSource.SPACING
                    / RegionalForestPages.PAGE_SIZE_METRES);
            if (x == pageX && z == pageZ) count++;
        }
        return count;
    }

    private static boolean containsPage(Map<Long, float[]> records, int pageX, int pageZ) {
        return pageTreeCount(records, pageX, pageZ) != 0;
    }

    private static long key(int x, int z) { return ((long) z << 32) | (x & 0xffffffffL); }
    private static long pageKey(int x, int z) { return ((long) z << 32) | (x & 0xffffffffL); }

    private static void checkRecords(Map<Long, float[]> expected, Map<Long, float[]> actual, String message) {
        check(expected.size() == actual.size(), message + " count");
        for (Map.Entry<Long, float[]> entry : expected.entrySet()) {
            check(Arrays.equals(entry.getValue(), actual.get(entry.getKey())), message + " at " + entry.getKey());
        }
    }

    private static float unit(long value) { return (float) ((value >>> 40) * 0x1.0p-24); }

    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    private static boolean rejected(Runnable action) {
        try { action.run(); return false; }
        catch (IllegalArgumentException | IndexOutOfBoundsException expected) { return true; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
