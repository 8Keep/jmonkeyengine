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

import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * One-worker, latest-revision preparation for the regional terrain example.
 * All methods except the worker callback are confined to the creating thread.
 */
final class RegionalRevisionPreparation implements AutoCloseable {
    private static final long CLOSE_WAIT_SECONDS = 2;

    private final Thread ownerThread = Thread.currentThread();
    private final Builder builder;
    private final ThreadPoolExecutor executor;
    private final AtomicReference<Outcome> completed = new AtomicReference<>();
    private Ticket running;
    private Future<?> runningFuture;
    private Long latestRevision;
    private Outcome immediateOutcome;
    private long startedBuilds;
    private long supersededBuilds;
    private boolean closed;

    RegionalRevisionPreparation(int patchCells) {
        this(patchCells, RegionalHeightfieldSource.CELLS);
    }

    RegionalRevisionPreparation(int patchCells, int regionCells) {
        this(new DefaultBuilder(patchCells, regionCells));
    }

    /** Package hook for finite cancellation, supersession and failure checks. */
    RegionalRevisionPreparation(Builder builder) {
        this.builder = Objects.requireNonNull(builder, "builder");
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "regional-heightfield-preparation");
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Coalesce to this revision and interrupt superseded work cooperatively. */
    void request(long revision) {
        checkOwner();
        if (closed) throw new IllegalStateException("revision preparation is closed");
        if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
        if (latestRevision != null && latestRevision == revision) return;
        if (running != null && !running.cancelled && running.revision == revision) {
            // Reuse the still-valid in-flight result and discard any different
            // queued revision instead of rebuilding this revision afterward.
            if (latestRevision != null) supersededBuilds++;
            latestRevision = null;
            return;
        }

        if (latestRevision != null) supersededBuilds++;
        latestRevision = revision;
        if (running != null && !running.cancelled && running.revision != revision) {
            supersededBuilds++;
            cancelAndInterrupt(running);
        } else if (running != null && running.cancelled && running.revision == revision) {
            // Re-requesting a canceled revision queues one fresh attempt.
            latestRevision = revision;
        }
    }

    /**
     * Poll without waiting. A completion is returned only if it is still the
     * latest requested revision; otherwise the newest desired build is started.
     */
    Outcome poll() {
        checkOwner();
        if (closed) return null;
        Outcome publish = immediateOutcome;
        immediateOutcome = null;
        if (runningFuture != null && runningFuture.isDone()) {
            Ticket finished = running;
            Outcome result = completed.getAndSet(null);
            running = null;
            runningFuture = null;
            if (publish == null && result != null && !finished.cancelled && latestRevision == null) {
                publish = result;
            }
        }
        if (runningFuture == null && latestRevision != null) startLatest();
        return publish;
    }

    long getStartedBuilds() { checkOwner(); return startedBuilds; }
    long getSupersededBuilds() { checkOwner(); return supersededBuilds; }

    /** True while a worker or one coalesced revision remains outstanding. */
    boolean hasPendingWork() {
        checkOwner();
        return runningFuture != null || latestRevision != null || immediateOutcome != null;
    }

    private void startLatest() {
        long revision = latestRevision;
        latestRevision = null;
        Ticket ticket = new Ticket(revision);
        running = ticket;
        completed.set(null);
        startedBuilds++;
        try {
            runningFuture = executor.submit(() -> run(ticket));
        } catch (RuntimeException error) {
            running = null;
            runningFuture = null;
            immediateOutcome = Outcome.failure(revision, error);
        }
    }

    private Void run(Ticket ticket) {
        synchronized (ticket) {
            ticket.worker = Thread.currentThread();
            if (ticket.cancelled) ticket.worker.interrupt();
        }
        try {
            Prepared prepared = builder.build(ticket.revision, ticket::isCancelled);
            if (prepared == null || prepared.revision != ticket.revision) {
                throw new IllegalStateException("builder returned no result or the wrong source revision");
            }
            completed.set(Outcome.success(ticket.revision, prepared));
        } catch (Throwable failure) {
            completed.set(Outcome.failure(ticket.revision, failure));
        } finally {
            synchronized (ticket) {
                Thread.interrupted();
                ticket.worker = null;
            }
        }
        return null;
    }

    private void cancelAndInterrupt(Ticket ticket) {
        synchronized (ticket) {
            ticket.cancelled = true;
            if (ticket.worker != null) ticket.worker.interrupt();
        }
    }

    private void checkOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("revision preparation must be polled and controlled by its owner thread");
        }
    }

    @Override
    public void close() {
        checkOwner();
        if (closed) return;
        closed = true;
        latestRevision = null;
        if (running != null && !running.cancelled) {
            supersededBuilds++;
            cancelAndInterrupt(running);
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                if (running != null) cancelAndInterrupt(running);
                executor.shutdownNow();
                if (!executor.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("regional preparation worker did not stop after interruption");
                }
            }
        } catch (InterruptedException interrupted) {
            if (running != null) cancelAndInterrupt(running);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while stopping regional preparation", interrupted);
        } finally {
            completed.set(null);
            immediateOutcome = null;
            running = null;
            runningFuture = null;
        }
    }

    @FunctionalInterface
    interface Builder {
        Prepared build(long revision, BooleanSupplier cancelled) throws Exception;
    }

    static final class Prepared {
        final RegionalHeightfieldSource source;
        final HeightfieldHierarchy hierarchy;
        final long revision;
        final long buildNanos;

        Prepared(RegionalHeightfieldSource source, HeightfieldHierarchy hierarchy,
                long revision, long buildNanos) {
            this.source = Objects.requireNonNull(source, "source");
            this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
            if (revision < 0 || buildNanos < 0 || source.getRevision() != revision
                    || hierarchy.getSourceRevision() != revision
                    || hierarchy.getCells() != source.getCells()
                    || hierarchy.getSampleSpacing() != source.getSampleSpacing()
                    || hierarchy.hasCanonicalPage()) {
                throw new IllegalArgumentException("prepared source and metadata hierarchy must share one revision");
            }
            this.revision = revision;
            this.buildNanos = buildNanos;
        }
    }

    static final class Outcome {
        final long revision;
        final Prepared prepared;
        final Throwable failure;

        private Outcome(long revision, Prepared prepared, Throwable failure) {
            this.revision = revision;
            this.prepared = prepared;
            this.failure = failure;
        }

        private static Outcome success(long revision, Prepared prepared) {
            return new Outcome(revision, prepared, null);
        }

        private static Outcome failure(long revision, Throwable failure) {
            return new Outcome(revision, null, Objects.requireNonNull(failure, "failure"));
        }

        boolean isSuccess() { return failure == null; }
    }

    private static final class Ticket {
        final long revision;
        volatile boolean cancelled;
        Thread worker;

        Ticket(long revision) { this.revision = revision; }

        boolean isCancelled() { return cancelled || Thread.currentThread().isInterrupted(); }
    }

    private static final class DefaultBuilder implements Builder {
        private final int patchCells;
        private final int regionCells;

        DefaultBuilder(int patchCells, int regionCells) {
            if (patchCells < 2 || (patchCells & (patchCells - 1)) != 0) {
                throw new IllegalArgumentException("patchCells must be a power of two of at least 2");
            }
            if ((regionCells != 1024 && regionCells != 2048
                    && regionCells != RegionalHeightfieldSource.FAR_CELLS) || patchCells > regionCells) {
                throw new IllegalArgumentException("regionCells must be 1024, 2048, or 8192 and at least patchCells");
            }
            this.patchCells = patchCells;
            this.regionCells = regionCells;
        }

        @Override
        public Prepared build(long revision, BooleanSupplier cancelled) {
            return prepare(patchCells, regionCells, revision, cancelled);
        }
    }

    static Prepared prepare(int patchCells, long revision, BooleanSupplier cancelled) {
        return prepare(patchCells, RegionalHeightfieldSource.CELLS, revision, cancelled);
    }

    static Prepared prepare(int patchCells, int regionCells, long revision, BooleanSupplier cancelled) {
        long start = System.nanoTime();
        checkCancelled(cancelled);
        RegionalHeightfieldSource source = new RegionalHeightfieldSource(regionCells, revision);
        HeightfieldHierarchy hierarchy = source.measuredHierarchy(patchCells);
        checkCancelled(cancelled);
        return new Prepared(source, hierarchy, revision, System.nanoTime() - start);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("regional revision was superseded");
        }
    }
}
