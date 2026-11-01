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

import com.jme3.terrain.heightfield.HeightfieldSurfaceResidency;
import java.util.HashMap;
import java.util.Map;

/** CPU-only checks for bounded regional surface key reuse and value identity. */
public final class TestRegionalSurfaceCacheKeys {
    private TestRegionalSurfaceCacheKeys() { }

    public static void main(String[] args) {
        verifyRegion(1024, 4);
        verifyRegion(2048, 8);
        System.out.println("REGIONAL_SURFACE_CACHE_KEYS_CPU_PASS");
    }

    private static void verifyRegion(int cells, int rootStride) {
        RegionalSurfaceCache.KeyBank first = new RegionalSurfaceCache.KeyBank(7, cells, rootStride);
        long rootSpan = (long) RegionalSurfaceCache.PAGE_RESOLUTION * rootStride;
        HeightfieldSurfaceResidency.PageKey root = first.key(0, 0, rootStride);
        HeightfieldSurfaceResidency.PageKey middle = first.key(128, 128, 2);
        HeightfieldSurfaceResidency.PageKey fine = first.key(64, 64, 1);
        require(first.key(0, 0, rootStride) == root, "same revision/origin/stride reuses the immutable key");
        require(root.revision == first.revision && middle.revision == first.revision
                && fine.revision == first.revision, "one revision object is shared by the key bank");
        require(!root.equals(middle) && !middle.equals(fine), "origin and stride remain part of identity");

        Map<HeightfieldSurfaceResidency.PageKey, String> retained = new HashMap<>();
        retained.put(root, "root");
        retained.put(middle, "middle");
        retained.put(fine, "fine");
        RegionalSurfaceCache.KeyBank recreated = new RegionalSurfaceCache.KeyBank(7, cells, rootStride);
        require(root != recreated.key(0, 0, rootStride)
                && "root".equals(retained.get(recreated.key(0, 0, rootStride))),
                "recreated revision bank keys remain value-equal for retained residency maps");
        RegionalSurfaceCache.KeyBank otherRevision = new RegionalSurfaceCache.KeyBank(8, cells, rootStride);
        require(!root.equals(otherRevision.key(0, 0, rootStride)), "revision remains part of page identity");

        int fineAxis = cells / RegionalSurfaceCache.PAGE_RESOLUTION;
        int middleAxis = cells / (2 * RegionalSurfaceCache.PAGE_RESOLUTION);
        int rootAxis = cells / (rootStride * RegionalSurfaceCache.PAGE_RESOLUTION);
        for (int z = 0; z < fineAxis; z++) {
            for (int x = 0; x < fineAxis; x++) first.key(x * 64L, z * 64L, 1);
        }
        for (int z = 0; z < middleAxis; z++) {
            for (int x = 0; x < middleAxis; x++) first.key(x * 128L, z * 128L, 2);
        }
        for (int z = 0; z < rootAxis; z++) {
            for (int x = 0; x < rootAxis; x++) first.key(x * rootSpan, z * rootSpan, rootStride);
        }
        require(first.getAllocatedKeyCount() == fineAxis * fineAxis
                + middleAxis * middleAxis + rootAxis * rootAxis,
                "key table allocates at most one object per supported regional page");

        require(rejected(() -> first.key(1, 0, 1)), "misaligned page origins are rejected");
        require(rejected(() -> first.key(cells, 0, 1)), "out-of-region page origins are rejected");
    }

    private static boolean rejected(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException | IndexOutOfBoundsException expected) {
            return true;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
