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
package com.jme3.terrain.heightfield;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeightfieldResidencyTest {
    private static HeightfieldResidency.Limits limits(int slots, int requests, long queued,
            long prepared, int uploads, long uploadBytes, int retired, int frames) {
        return new HeightfieldResidency.Limits(slots, requests, queued, prepared,
                uploads, uploadBytes, retired, frames);
    }

    private static HeightfieldResidency manager(int slots) {
        long bytes = 25 * Float.BYTES;
        return new HeightfieldResidency(2, limits(slots, 2, bytes * 2, bytes * 2,
                2, bytes * 2, slots, 3));
    }

    private static HeightfieldPage page(float value) {
        float[] samples = new float[25];
        Arrays.fill(samples, value);
        return new HeightfieldPage(2, 1, samples);
    }

    private static HeightfieldResidency.PageKey key(int x) {
        return new HeightfieldResidency.PageKey(1, x, 0, 1);
    }

    private static HeightfieldResidency.Handle publish(HeightfieldResidency owner,
            HeightfieldResidency.PageKey key, float value) {
        HeightfieldResidency.Request request = owner.request(key);
        assertNotNull(request);
        assertTrue(owner.complete(request, page(value)));
        assertEquals(1, owner.uploadPrepared((slot, payload) -> { }));
        return owner.ready(key);
    }

    @Test void independentRequestAndPreparedByteBudgetsAndCancellation() {
        long bytes = 100;
        HeightfieldResidency owner = new HeightfieldResidency(2,
                limits(2, 2, bytes * 2, bytes, 2, bytes * 2, 1, 1));
        HeightfieldResidency.Request first = owner.request(key(0));
        assertNotNull(first);
        HeightfieldResidency.Request second = owner.request(key(1));
        assertNotNull(second);
        HeightfieldResidency.Stats stats = owner.stats();
        assertEquals(2, stats.requests);
        assertEquals(bytes * 2, stats.queuedBytes);
        assertTrue(owner.complete(first, page(0)));
        assertFalse(owner.complete(second, page(1)));
        assertTrue(owner.isPending(second));
        assertEquals(1, owner.stats().requests, "prepared pressure leaves worker completion retryable");
        assertTrue(owner.cancel(second));
        assertFalse(owner.isPending(second));
        assertEquals(1, owner.stats().preparedPages);
        assertEquals(bytes, owner.stats().preparedBytes);
        assertTrue(owner.cancelPrepared(key(0)));
        assertFalse(owner.cancelPrepared(key(1)));
        assertEquals(0, owner.stats().preparedBytes);
    }

    @Test void duplicateReadyAndOutstandingRequestsAreRejectedAndResetMakesJobsStale() {
        HeightfieldResidency owner = manager(1);
        HeightfieldResidency.Request request = owner.request(key(0));
        assertNotNull(request);
        assertNull(owner.request(key(0)));
        assertTrue(owner.complete(request, page(2)));
        assertNull(owner.request(key(0)));
        owner.reset();
        assertFalse(owner.complete(request, page(3)));
        assertEquals(0, owner.stats().requests);
        assertEquals(0, owner.stats().preparedPages);
        assertNotNull(publish(owner, key(0), 4));
    }

    @Test void failedUploadKeepsPreparedPageAndRetriesTheSameUnpublishedSlot() {
        HeightfieldResidency owner = manager(1);
        HeightfieldResidency.Request request = owner.request(key(0));
        assertTrue(owner.complete(request, page(7)));
        int[] firstSlot = {-1};
        assertThrows(IllegalStateException.class, () -> owner.uploadPrepared((slot, payload) -> {
            firstSlot[0] = slot;
            throw new IllegalStateException("partial device write");
        }));
        assertNull(owner.ready(key(0)));
        assertEquals(1, owner.stats().preparedPages);
        assertEquals(1, owner.uploadPrepared((slot, payload) -> assertEquals(firstSlot[0], slot)));
        assertTrue(owner.isCurrent(owner.ready(key(0))));
    }

    @Test void pinnedPagesAndRetiredBudgetPreventUnsafeEviction() {
        HeightfieldResidency owner = new HeightfieldResidency(2,
                limits(2, 2, 200, 200, 2, 200, 1, 3));
        HeightfieldResidency.Handle a = publish(owner, key(0), 1);
        assertTrue(owner.pin(key(0)));
        assertFalse(owner.evict(key(0)));
        assertTrue(owner.unpin(key(0)));
        HeightfieldResidency.Frame first = owner.acquire(Collections.singleton(key(0)));
        assertTrue(owner.evict(key(0)));
        assertTrue(owner.isRetained(a));
        assertFalse(owner.isCurrent(a));

        HeightfieldResidency.Handle b = publish(owner, key(1), 2);
        HeightfieldResidency.Frame second = owner.acquire(Collections.singleton(key(1)));
        assertFalse(owner.evict(key(1)), "retired slot budget is already occupied");
        assertTrue(owner.submit(first, new Fence(false)));
        assertTrue(owner.submit(second, new Fence(false)));
        assertEquals(0, owner.poll());
        assertEquals(1, owner.stats().retiredSlots);
        assertEquals(1, owner.get(a).getHeight(0, 0));
        assertEquals(2, owner.get(b).getHeight(0, 0));
    }

    @Test void acquisitionIsAtomicAndMultipleFramesRetainSlotsUntilTheirOwnFencesComplete() {
        HeightfieldResidency owner = manager(1);
        HeightfieldResidency.Handle handle = publish(owner, key(0), 9);
        assertNull(owner.acquire(Arrays.asList(key(0), key(1))));
        assertEquals(0, owner.stats().frames);
        HeightfieldResidency.Frame one = owner.acquire(Arrays.asList(key(0), key(0)));
        HeightfieldResidency.Frame two = owner.acquire(Collections.singleton(key(0)));
        assertEquals(1, one.getHandles().size());
        Map<HeightfieldResidency.PageKey, HeightfieldResidency.Handle> immutable = one.getHandles();
        assertThrows(UnsupportedOperationException.class, () -> immutable.clear());
        Fence first = new Fence(false), second = new Fence(false);
        assertTrue(owner.submit(one, first));
        assertFalse(owner.submit(one, new Fence(true)));
        assertTrue(owner.submit(two, second));
        assertTrue(owner.evict(key(0)));
        assertTrue(owner.isRetained(handle));
        first.complete = true;
        assertEquals(1, owner.poll());
        assertTrue(owner.isRetained(handle));
        assertEquals(1, owner.stats().frames);
        second.complete = true;
        assertEquals(1, owner.poll());
        assertFalse(owner.isRetained(handle));
        HeightfieldResidency.Handle replacement = publish(owner, key(1), 10);
        assertEquals(handle.slot, replacement.slot);
        assertTrue(replacement.generation > handle.generation);
        assertFalse(owner.isCurrent(handle));
        assertEquals(owner.stats().slotCapacity * owner.getPageBytes(), owner.stats().residentPayloadBytes);
    }

    @Test void abortOnlyReleasesUndrawnLeaseAndResetClosesFencesAndInvalidatesHandles() {
        HeightfieldResidency owner = manager(1);
        HeightfieldResidency.Handle handle = publish(owner, key(0), 3);
        HeightfieldResidency.Frame acquired = owner.acquire(Collections.singleton(key(0)));
        assertTrue(owner.abort(acquired));
        assertFalse(owner.abort(acquired));
        HeightfieldResidency.Frame submitted = owner.acquire(Collections.singleton(key(0)));
        Fence fence = new Fence(false);
        assertTrue(owner.submit(submitted, fence));
        owner.reset();
        assertTrue(fence.closed);
        assertFalse(owner.isRetained(handle));
        assertEquals(0, owner.stats().frames);
    }

    @Test void fencePollFailureRetainsLeaseAndPreventsSlotReuseUntilReset() {
        HeightfieldResidency owner = manager(1);
        HeightfieldResidency.Handle handle = publish(owner, key(0), 5);
        HeightfieldResidency.Frame frame = owner.acquire(Collections.singleton(key(0)));
        assertTrue(owner.submit(frame, new HeightfieldResidency.Completion() {
            @Override public boolean isComplete() { throw new IllegalStateException("device lost"); }
            @Override public void close() { }
        }));
        assertThrows(IllegalStateException.class, owner::poll);
        assertTrue(owner.isRetained(handle));
        assertEquals(1, owner.stats().frames);
        assertTrue(owner.evict(key(0)));
        HeightfieldResidency.Request next = owner.request(key(1));
        assertNotNull(next);
        assertTrue(owner.complete(next, page(6)));
        assertEquals(0, owner.uploadPrepared((slot, page) -> fail("retained slot must not be reused")));
        owner.reset();
        assertFalse(owner.isRetained(handle));
    }

    @Test void foreignPoolRequestsAndHandlesAreRejectedEvenWhenTheirValuesMatch() {
        HeightfieldResidency firstPool = manager(1), secondPool = manager(1);
        HeightfieldResidency.Request firstRequest = firstPool.request(key(0));
        HeightfieldResidency.Request secondRequest = secondPool.request(key(0));
        assertEquals(firstRequest.token, secondRequest.token);
        assertEquals(firstRequest.epoch, secondRequest.epoch);
        assertEquals(firstRequest.key, secondRequest.key);
        assertFalse(secondPool.complete(firstRequest, page(4)));
        assertFalse(secondPool.cancel(firstRequest));
        assertFalse(secondPool.isPending(firstRequest));
        assertTrue(secondPool.isPending(secondRequest));
        assertTrue(secondPool.complete(secondRequest, page(4)));
        assertFalse(secondPool.cancel(firstRequest), "foreign ticket cannot cancel matching prepared data");
        assertTrue(secondPool.cancel(secondRequest));

        HeightfieldResidency.Handle firstHandle = publish(firstPool, key(1), 8);
        HeightfieldResidency.Handle secondHandle = publish(secondPool, key(1), 8);
        assertEquals(firstHandle.slot, secondHandle.slot);
        assertEquals(firstHandle.generation, secondHandle.generation);
        assertEquals(firstHandle.epoch, secondHandle.epoch);
        assertFalse(secondPool.isCurrent(firstHandle));
        assertFalse(secondPool.isRetained(firstHandle));
        assertNull(secondPool.get(firstHandle));
    }

    @Test void callbackReentryAndWrongThreadAccessAreRejected() throws Exception {
        HeightfieldResidency owner = manager(1);
        HeightfieldResidency.Request request = owner.request(key(0));
        assertTrue(owner.complete(request, page(1)));
        assertThrows(IllegalStateException.class,
                () -> owner.uploadPrepared((slot, payload) -> owner.request(key(1))));
        assertEquals(1, owner.stats().preparedPages);
        assertFalse(owner.isPending(request));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                owner.request(key(2));
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        worker.start();
        worker.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
    }

    @Test void uploadCountAndByteBudgetsConstrainCallsIndependently() {
        long pageBytes = 100;
        HeightfieldResidency byCount = new HeightfieldResidency(2,
                limits(2, 2, 200, 200, 1, 200, 1, 1));
        HeightfieldResidency.Request a = byCount.request(key(0));
        HeightfieldResidency.Request b = byCount.request(key(1));
        assertTrue(byCount.complete(a, page(1)));
        assertTrue(byCount.complete(b, page(2)));
        assertEquals(1, byCount.uploadPrepared((slot, payload) -> { }));
        assertEquals(1, byCount.stats().preparedPages);

        HeightfieldResidency byBytes = new HeightfieldResidency(2,
                limits(2, 2, 200, 200, 2, pageBytes, 1, 1));
        a = byBytes.request(key(0));
        b = byBytes.request(key(1));
        assertTrue(byBytes.complete(a, page(1)));
        assertTrue(byBytes.complete(b, page(2)));
        assertEquals(1, byBytes.uploadPrepared((slot, payload) -> { }));
        assertEquals(1, byBytes.stats().preparedPages);
    }

    @Test void completionCloseFailureKeepsLeaseUntilReset() {
        HeightfieldResidency owner = manager(1);
        HeightfieldResidency.Handle handle = publish(owner, key(0), 5);
        HeightfieldResidency.Frame frame = owner.acquire(Collections.singleton(key(0)));
        assertTrue(owner.submit(frame, new HeightfieldResidency.Completion() {
            @Override public boolean isComplete() { return true; }
            @Override public void close() { throw new IllegalStateException("fence release failed"); }
        }));
        assertThrows(IllegalStateException.class, owner::poll);
        assertTrue(owner.isRetained(handle));
        assertEquals(1, owner.stats().frames);
        assertTrue(owner.evict(key(0)));
        HeightfieldResidency.Request next = owner.request(key(1));
        assertTrue(owner.complete(next, page(6)));
        assertEquals(0, owner.uploadPrepared((slot, payload) -> fail("lease must retain the slot")));
        assertThrows(IllegalStateException.class, owner::reset);
        assertFalse(owner.isRetained(handle));
        assertEquals(0, owner.stats().frames);
    }

    @Test void resetCleanupFailureStillInvalidatesStateAndClosesRemainingFences() {
        HeightfieldResidency owner = manager(2);
        HeightfieldResidency.Request stale = owner.request(key(9));
        HeightfieldResidency.Handle a = publish(owner, key(0), 1);
        HeightfieldResidency.Handle b = publish(owner, key(1), 2);
        HeightfieldResidency.Frame frameA = owner.acquire(Collections.singleton(key(0)));
        HeightfieldResidency.Frame frameB = owner.acquire(Collections.singleton(key(1)));
        Fence broken = new Fence(false) {
            @Override public void close() {
                closed = true;
                throw new IllegalStateException("native close failed");
            }
        };
        Fence healthy = new Fence(false);
        assertTrue(owner.submit(frameA, broken));
        assertTrue(owner.submit(frameB, healthy));
        assertThrows(IllegalStateException.class, owner::reset);
        assertTrue(broken.closed);
        assertTrue(healthy.closed, "cleanup continues after another fence close fails");
        assertFalse(owner.isRetained(a));
        assertFalse(owner.isRetained(b));
        assertFalse(owner.isPending(stale));
        assertFalse(owner.complete(stale, page(9)));
        assertEquals(0, owner.stats().frames);
        assertNotNull(owner.request(key(10)), "pool remains usable after failed old-fence cleanup");
    }

    private static class Fence implements HeightfieldResidency.Completion {
        boolean complete;
        boolean closed;
        Fence(boolean complete) { this.complete = complete; }
        @Override public boolean isComplete() { return complete; }
        @Override public void close() { closed = true; }
    }
}
