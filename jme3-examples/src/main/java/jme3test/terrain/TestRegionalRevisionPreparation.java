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
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Finite headless checks for regional revision worker ownership and coalescing. */
public final class TestRegionalRevisionPreparation {
    private static final long WAIT_SECONDS = 3;

    private TestRegionalRevisionPreparation() {
    }

    public static void main(String[] args) throws Exception {
        verifyBootstrapHierarchy();
        verifyCoalescingFailureAndOwnerThread();
        verifyCloseInterruptsWorker();
        System.out.println("PASS regional revision preparation concurrency contract");
    }

    private static void verifyBootstrapHierarchy() {
        RegionalHeightfieldSource source = new RegionalHeightfieldSource(2048, 12);
        HeightfieldHierarchy hierarchy = source.bootstrapHierarchy(256);
        require(source.getCells() == 2048 && hierarchy.getCells() == 2048,
                "configured regional dimensions were not preserved");
        require(hierarchy.getNodes().size() == 85 && !hierarchy.hasCanonicalPage(),
                "2048 bootstrap hierarchy is metadata-only and bounded");
        require(source.getHeightRequestCount() == 0
                && source.getCachedFloatBytes() == 0,
                "bootstrap metadata does not sample or retain source pages");
        for (HeightfieldHierarchy.Node node : hierarchy.getNodes()) {
            HeightfieldHierarchy.NodeMetadata metadata = node.getMetadata();
            require(metadata.getMinimumHeight() == 0 && metadata.getMaximumHeight() == 736,
                    "bootstrap node uses the documented conservative source envelope");
            require(node.getStableError() <= 736.01 && node.getMorphError() <= 1472.01,
                    "bootstrap errors are conservatively bounded");
        }
    }

    private static void verifyCoalescingFailureAndOwnerThread() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstInterrupted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch firstExited = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peakActive = new AtomicInteger();
        List<Long> calls = new CopyOnWriteArrayList<>();
        IllegalArgumentException injectedFailure = new IllegalArgumentException("injected build failure");

        RegionalRevisionPreparation.Builder builder = (revision, cancelled) -> {
            int nowActive = active.incrementAndGet();
            peakActive.accumulateAndGet(nowActive, Math::max);
            calls.add(revision);
            try {
                if (revision == 1) {
                    firstStarted.countDown();
                    try {
                        releaseFirst.await();
                    } catch (InterruptedException expected) {
                        firstInterrupted.countDown();
                        awaitUninterruptibly(releaseFirst);
                    }
                }
                if (revision == 4) throw injectedFailure;
                return prepared(revision);
            } finally {
                active.decrementAndGet();
                if (revision == 1) firstExited.countDown();
            }
        };

        RegionalRevisionPreparation preparation = new RegionalRevisionPreparation(builder);
        try {
            preparation.request(1);
            preparation.poll();
            await(firstStarted, "first build did not start");

            preparation.request(2);
            preparation.request(3);
            await(firstInterrupted, "superseded worker was not interrupted");
            require(preparation.poll() == null, "poll published a superseded outcome");
            require(calls.size() == 1 && calls.get(0) == 1,
                    "latest build started before the interrupted worker exited");
            require(active.get() == 1 && peakActive.get() == 1,
                    "more than one actual builder body was active");

            releaseFirst.countDown();
            await(firstExited, "interrupted worker did not exit");
            RegionalRevisionPreparation.Outcome newest = awaitOutcome(preparation);
            require(newest.isSuccess() && newest.revision == 3 && newest.prepared.revision == 3,
                    "coalesced request did not publish only revision 3");
            require(calls.size() == 2 && calls.get(0) == 1L && calls.get(1) == 3L,
                    "superseded revision 2 was built");
            require(peakActive.get() == 1, "builder bodies overlapped across revisions");

            preparation.request(4);
            RegionalRevisionPreparation.Outcome failed = awaitOutcome(preparation);
            require(!failed.isSuccess() && failed.revision == 4 && failed.failure == injectedFailure,
                    "injected failure was not delivered intact");

            preparation.request(5);
            RegionalRevisionPreparation.Outcome recovered = awaitOutcome(preparation);
            require(recovered.isSuccess() && recovered.revision == 5,
                    "a successful build after failure was not delivered");

            AtomicReference<Throwable> wrongThreadResult = new AtomicReference<>();
            Thread wrongThread = new Thread(() -> {
                try {
                    preparation.poll();
                } catch (Throwable failure) {
                    wrongThreadResult.set(failure);
                }
            }, "regional-preparation-owner-check");
            wrongThread.start();
            wrongThread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            require(!wrongThread.isAlive(), "owner-thread check did not return");
            require(wrongThreadResult.get() instanceof IllegalStateException,
                    "poll from a non-owner thread was not rejected");
        } finally {
            releaseFirst.countDown();
            preparation.close();
        }
    }

    private static void verifyCloseInterruptsWorker() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        RegionalRevisionPreparation preparation = new RegionalRevisionPreparation((revision, cancelled) -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
            } finally {
                exited.countDown();
            }
            return prepared(revision);
        });
        preparation.request(8);
        preparation.poll();
        await(started, "close-test build did not start");
        preparation.close();
        await(interrupted, "close did not interrupt the active worker");
        await(exited, "active worker did not exit during close");
    }

    private static RegionalRevisionPreparation.Outcome awaitOutcome(
            RegionalRevisionPreparation preparation) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            RegionalRevisionPreparation.Outcome result = preparation.poll();
            if (result != null) return result;
            Thread.sleep(1);
        }
        throw new AssertionError("timed out waiting for preparation outcome");
    }

    private static RegionalRevisionPreparation.Prepared prepared(long revision) {
        RegionalHeightfieldSource source = new RegionalHeightfieldSource(revision);
        HeightfieldHierarchy hierarchy = HeightfieldHierarchy.fromMetadata(
                RegionalHeightfieldSource.CELLS, RegionalHeightfieldSource.CELLS,
                RegionalHeightfieldSource.SPACING, revision, 0,
                (x, z, span, stride) -> new HeightfieldHierarchy.NodeMetadata(0, 0, 0, 0));
        return new RegionalRevisionPreparation.Prepared(source, hierarchy, revision, 1);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException expected) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        require(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
