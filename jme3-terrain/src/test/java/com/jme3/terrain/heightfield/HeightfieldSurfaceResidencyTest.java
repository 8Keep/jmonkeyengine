/*
 * Copyright (c) 2009-2024 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * Neither the name of jMonkeyEngine nor the names of its contributors may be
 * used to endorse or promote products derived from this software without
 * specific prior written permission.
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
package com.jme3.terrain.heightfield;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeightfieldSurfaceResidencyTest {
    private static final HeightfieldSurfacePage.Revision REVISION =
            new HeightfieldSurfacePage.Revision(1, 2, 3);
    private static final long CPU_BYTES = 12L * 12 * HeightfieldSurfacePage.CPU_BYTES_PER_TEXEL;
    private static final long ENCODED_BYTES = 12L * 12 * 25;

    private static HeightfieldSurfaceResidency.Limits limits(int slots, int requests,
            long queued, long prepared, int uploads, long uploadBytes, int retired, int frames) {
        return new HeightfieldSurfaceResidency.Limits(slots, requests, queued, prepared,
                uploads, uploadBytes, retired, frames);
    }

    private static HeightfieldSurfaceResidency pool(int slots, int requests, int retired, int frames) {
        return new HeightfieldSurfaceResidency(8, 1,
                limits(slots, requests, CPU_BYTES * requests, CPU_BYTES * requests,
                        8, ENCODED_BYTES * 8, retired, frames));
    }

    private static HeightfieldSurfaceResidency.PageKey key(long revision, long x) {
        return new HeightfieldSurfaceResidency.PageKey(
                new HeightfieldSurfacePage.Revision(revision, revision + 1, revision + 2), x, 0, 1);
    }

    private static HeightfieldSurfacePage page(HeightfieldSurfaceResidency.PageKey key) {
        return new HeightfieldSurfacePage(key.revision, key.originX, key.originZ, 8,
                key.baseStride, 1, (x, z, sample) -> {
                    sample.albedoR = .2f;
                    sample.albedoG = .4f;
                    sample.albedoB = .6f;
                    sample.normalY = 1;
                    sample.roughness = .5f;
                    sample.semanticId = 7;
                });
    }

    private static void prepare(HeightfieldSurfaceResidency pool,
            HeightfieldSurfaceResidency.PageKey key) {
        HeightfieldSurfaceResidency.Request request = pool.request(key);
        assertNotNull(request);
        assertTrue(pool.complete(request, page(key)));
    }

    @Test
    void partialThrowNeverPublishesAndRetryUploadsWholePage() {
        HeightfieldSurfaceResidency pool = pool(1, 1, 0, 1);
        HeightfieldSurfaceResidency.PageKey key = key(1, 0);
        prepare(pool, key);
        AtomicInteger calls = new AtomicInteger();
        assertEquals(1, pool.uploadPrepared((slot, page) -> {
            calls.incrementAndGet();
            return false;
        }));
        assertNull(pool.ready(key), "an incomplete set of channel or mip uploads is not ready");
        assertThrows(IllegalStateException.class, () -> pool.uploadPrepared((slot, page) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("failure after partial channel writes");
        }));
        assertNull(pool.ready(key));
        assertEquals(1, pool.stats().preparedPages);
        assertEquals(1, pool.stats().residentPayloadSlots, "failed partial upload reserves its slot");
        assertEquals(1, pool.uploadPrepared((slot, page) -> {
            calls.incrementAndGet();
            return true;
        }));
        assertEquals(3, calls.get());
        assertNotNull(pool.ready(key));
        assertEquals(0, pool.stats().preparedPages);
        pool.close();
    }

    @Test
    void fullRevisionAndSpatialMetadataMustMatchAndCacheKeysAreIndependent() {
        HeightfieldSurfaceResidency pool = pool(2, 2, 0, 1);
        HeightfieldSurfaceResidency.PageKey a = key(1, 0);
        HeightfieldSurfaceResidency.PageKey b = key(2, 0);
        HeightfieldSurfaceResidency.Request requestA = pool.request(a);
        HeightfieldSurfaceResidency.Request requestB = pool.request(b);
        assertNotNull(requestA);
        assertNotNull(requestB);
        assertNotEquals(requestA.getKey(), requestB.getKey());
        HeightfieldSurfacePage mismatched = new HeightfieldSurfacePage(
                new HeightfieldSurfacePage.Revision(1, 99, 3), 0, 0, 8, 1, 1,
                (x, z, s) -> {
                    s.normalY = 1;
                    s.roughness = .5f;
                });
        assertThrows(IllegalArgumentException.class, () -> pool.complete(requestA, mismatched));
        assertTrue(pool.isPending(requestA));
        assertTrue(pool.complete(requestA, page(a)));
        assertTrue(pool.complete(requestB, page(b)));
        assertEquals(2, pool.uploadPrepared((slot, value) -> true));
        assertNotNull(pool.ready(a));
        assertNotNull(pool.ready(b));
        pool.close();
    }

    @Test
    void coverAcquisitionIsAtomicAndPinnedRootSurvivesSlotPressure() {
        HeightfieldSurfaceResidency pool = pool(2, 3, 1, 2);
        HeightfieldSurfaceResidency.PageKey root = key(1, 0);
        HeightfieldSurfaceResidency.PageKey child = key(1, 8);
        HeightfieldSurfaceResidency.PageKey missing = key(1, 16);
        prepare(pool, root);
        prepare(pool, child);
        pool.uploadPrepared((slot, page) -> true);
        assertTrue(pool.pin(root));
        assertNull(pool.acquire(Arrays.asList(root, missing)), "failed cover acquires no partial leases");
        HeightfieldSurfaceResidency.Frame frame = pool.acquire(Arrays.asList(root, child, root));
        assertNotNull(frame);
        assertEquals(2, frame.getHandles().size());
        assertFalse(pool.evict(root));
        assertTrue(pool.evict(child));
        assertTrue(pool.abort(frame));
        assertTrue(pool.unpin(root));
        assertTrue(pool.evict(root));
        pool.close();
    }

    @Test
    void retiredPayloadWaitsForFenceBeforeSlotReuse() {
        HeightfieldSurfaceResidency pool = pool(1, 2, 1, 1);
        HeightfieldSurfaceResidency.PageKey first = key(1, 0);
        HeightfieldSurfaceResidency.PageKey second = key(1, 8);
        prepare(pool, first);
        pool.uploadPrepared((slot, page) -> true);
        HeightfieldSurfaceResidency.Handle old = pool.ready(first);
        HeightfieldSurfaceResidency.Frame frame = pool.acquire(Collections.singleton(first));
        AtomicInteger fenceClose = new AtomicInteger();
        AtomicInteger complete = new AtomicInteger();
        assertTrue(pool.submit(frame, new HeightfieldSurfaceResidency.Completion() {
            @Override public boolean isComplete() { return complete.get() != 0; }
            @Override public void close() { fenceClose.incrementAndGet(); }
        }));
        assertTrue(pool.evict(first));
        prepare(pool, second);
        assertEquals(0, pool.uploadPrepared((slot, page) -> {
            fail("retired slot cannot be reused");
            return true;
        }));
        assertTrue(pool.isRetained(old));
        assertFalse(pool.isCurrent(old));
        complete.set(1);
        assertEquals(1, pool.poll());
        assertEquals(1, fenceClose.get());
        assertFalse(pool.isRetained(old));
        assertEquals(1, pool.uploadPrepared((slot, page) -> true));
        assertNotNull(pool.ready(second));
        pool.close();
    }

    @Test
    void foreignAndResetHandlesAreRejectedAndCapsAreExact() {
        HeightfieldSurfaceResidency first = pool(1, 1, 0, 1);
        HeightfieldSurfaceResidency second = pool(1, 1, 0, 1);
        HeightfieldSurfaceResidency.PageKey key = key(1, 0);
        prepare(first, key);
        first.uploadPrepared((slot, page) -> true);
        HeightfieldSurfaceResidency.Handle handle = first.ready(key);
        assertNull(second.get(handle));
        assertFalse(second.evict(key));
        HeightfieldSurfaceResidency.Request foreignRequest = second.request(key);
        assertNotNull(foreignRequest);
        assertFalse(first.cancel(foreignRequest));
        first.reset();
        assertNull(first.get(handle));
        assertFalse(first.isCurrent(handle));
        first.close();
        second.close();

        HeightfieldSurfaceResidency capped = new HeightfieldSurfaceResidency(8, 1,
                limits(1, 1, CPU_BYTES, CPU_BYTES, 0, ENCODED_BYTES, 0, 0));
        HeightfieldSurfaceResidency.Request admitted = capped.request(key);
        assertNotNull(admitted);
        assertNull(capped.request(key(2, 0)));
        assertTrue(capped.complete(admitted, page(key)));
        assertEquals(0, capped.uploadPrepared((slot, page) -> {
            fail("zero upload cap");
            return true;
        }));
        assertEquals(CPU_BYTES, capped.stats().preparedBytes);
        assertThrows(IllegalArgumentException.class, () -> new HeightfieldSurfaceResidency(8, 1,
                limits(1, 1, CPU_BYTES, CPU_BYTES, 1, ENCODED_BYTES - 1, 0, 1)));
        capped.close();
    }

    @Test
    void failedFencePollFailsClosedAndRetainsUncertainLease() {
        HeightfieldSurfaceResidency pool = pool(1, 1, 1, 1);
        HeightfieldSurfaceResidency.PageKey key = key(1, 0);
        prepare(pool, key);
        pool.uploadPrepared((slot, page) -> true);
        HeightfieldSurfaceResidency.Handle handle = pool.ready(key);
        HeightfieldSurfaceResidency.Frame frame = pool.acquire(Collections.singleton(key));
        pool.submit(frame, new HeightfieldSurfaceResidency.Completion() {
            @Override public boolean isComplete() { throw new IllegalStateException("fence poll failure"); }
            @Override public void close() { }
        });
        assertThrows(IllegalStateException.class, pool::poll);
        assertTrue(pool.isRetained(handle));
        assertTrue(pool.evict(key));
        assertTrue(pool.isRetained(handle));
        prepare(pool, key(2, 0));
        assertEquals(0, pool.uploadPrepared((slot, page) -> {
            fail("poisoned lease keeps slot retired");
            return true;
        }));
        pool.close(); // physical storage is discarded before close by this test owner
        assertThrows(IllegalStateException.class, () -> pool.isRetained(handle));
    }

    @Test
    void failedFenceClosePoisonsOwnerAfterStorageReset() {
        HeightfieldSurfaceResidency pool = pool(1, 1, 0, 1);
        HeightfieldSurfaceResidency.PageKey key = key(1, 0);
        prepare(pool, key);
        pool.uploadPrepared((slot, page) -> true);
        HeightfieldSurfaceResidency.Frame frame = pool.acquire(Collections.singleton(key));
        pool.submit(frame, new HeightfieldSurfaceResidency.Completion() {
            @Override public boolean isComplete() { return false; }
            @Override public void close() { throw new IllegalStateException("native fence close failure"); }
        });
        assertThrows(IllegalStateException.class, pool::reset);
        assertThrows(IllegalStateException.class, () -> pool.request(key(2, 0)));
        pool.close();
    }
}
