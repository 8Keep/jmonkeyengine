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
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded CPU preparation of immutable regional node pages. The single worker
 * owns its revision source cache; only completed CPU pages cross to the caller.
 */
final class RegionalPagePreparation implements AutoCloseable {
    private static final int SOURCE_CACHE_LIMIT = 2;
    private final long tileCacheLimitBytes;
    private static final long CLOSE_WAIT_SECONDS = 5;

    private final Thread ownerThread = Thread.currentThread();
    private final int patchCells;
    private final int maxJobs;
    private final int regionCells;
    private final ThreadPoolExecutor executor;
    private final LinkedHashMap<Long, RegionalHeightfieldSource> sources =
            new LinkedHashMap<Long, RegionalHeightfieldSource>(4, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, RegionalHeightfieldSource> eldest) {
                    return size() > SOURCE_CACHE_LIMIT;
                }
            };
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();
    private volatile int cachedSourceCount;
    private volatile long cachedFloatBytes;
    private volatile long peakCachedFloatBytes;
    private volatile long tileLoads;
    private volatile long directSamples;
    private boolean closed;

    RegionalPagePreparation(int patchCells, int maxJobs) {
        this(patchCells, maxJobs, RegionalHeightfieldSource.CELLS);
    }

    RegionalPagePreparation(int patchCells, int maxJobs, int regionCells) {
        if (patchCells < 2 || patchCells > 256 || (patchCells & (patchCells - 1)) != 0) {
            throw new IllegalArgumentException("patchCells must be a power of two from 2 to 256");
        }
        if ((regionCells != 1024 && regionCells != 2048) || patchCells > regionCells) {
            throw new IllegalArgumentException("regionCells must be 1024 or 2048 and at least patchCells");
        }
        if (maxJobs < 1) {
            throw new IllegalArgumentException("maxJobs must be positive");
        }
        this.patchCells = patchCells;
        this.maxJobs = maxJobs;
        this.regionCells = regionCells;
        tileCacheLimitBytes = SOURCE_CACHE_LIMIT * RegionalHeightfieldSource.maximumCacheBytes(regionCells);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "regional-heightfield-pages");
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxJobs), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Submit a node page for one immutable source revision. */
    Future<HeightfieldPage> submit(HeightfieldHierarchy.Node node, long revision) {
        checkOwner();
        ensureOpen();
        if (node == null) {
            throw new IllegalArgumentException("node is required");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be nonnegative");
        }
        final int originX = node.getOriginX();
        final int originZ = node.getOriginZ();
        final int stride = node.getStride();
        final int span = node.getSpan();
        if ((long) span != (long) patchCells * stride || stride < 1 || (stride & (stride - 1)) != 0
                || originX < 0 || originZ < 0
                || (long) originX + span > regionCells
                || (long) originZ + span > regionCells
                || originX % stride != 0 || originZ % stride != 0) {
            throw new IllegalArgumentException("node must be a stride-aligned patch within the regional source");
        }
        Future<HeightfieldPage> future = executor.submit(() -> {
            try {
                RegionalHeightfieldSource source = sourceFor(revision);
                HeightfieldSource samples = patchCells >= 32 && stride <= 2
                        ? source : source.pageSamples(stride);
                HeightfieldPage page = HeightfieldPage.fromSource(samples, originX, originZ,
                        patchCells, stride, true);
                completed.incrementAndGet();
                publishSourceStats();
                return page;
            } catch (CancellationException cancelledWork) {
                publishSourceStats();
                throw cancelledWork;
            } catch (Throwable failureCause) {
                failed.incrementAndGet();
                publishSourceStats();
                throw failureCause;
            }
        });
        submitted.incrementAndGet();
        return future;
    }

    /** Interrupt a running extraction or remove it promptly from the bounded queue. */
    void cancel(Future<?> future) {
        checkOwner();
        ensureOpen();
        if (future == null) {
            throw new IllegalArgumentException("future is required");
        }
        if (future.cancel(true)) {
            cancelled.incrementAndGet();
            executor.purge();
        }
    }

    private RegionalHeightfieldSource sourceFor(long revision) {
        RegionalHeightfieldSource source = sources.get(revision);
        if (source == null) {
            source = new RegionalHeightfieldSource(regionCells, revision);
            sources.put(revision, source);
        }
        return source;
    }

    private void publishSourceStats() {
        long bytes = 0;
        long loads = 0;
        long direct = 0;
        for (RegionalHeightfieldSource source : sources.values()) {
            bytes += source.getCachedFloatBytes();
            loads += source.getTileLoads();
            direct += source.getDirectSampleCount();
        }
        cachedSourceCount = sources.size();
        cachedFloatBytes = bytes;
        peakCachedFloatBytes = Math.max(peakCachedFloatBytes, bytes);
        tileLoads = loads;
        directSamples = direct;
    }

    /** Print worker-published resource and throughput counters. */
    void printStats() {
        checkOwner();
        System.out.println("REGIONAL_PAGE_PREPARATION submitted=" + submitted.get()
                + " completed=" + completed.get() + " failed=" + failed.get()
                + " cancelled=" + cancelled.get() + " queue=" + executor.getQueue().size()
                + " active=" + executor.getActiveCount() + " cachedSources=" + cachedSourceCount
                + " cachedFloatBytes=" + cachedFloatBytes + " peakCachedFloatBytes=" + peakCachedFloatBytes
                + " tileLoads=" + tileLoads + " directSamples=" + directSamples);
    }

    /** Assert the queue, worker, source and tile caches remain within their configured bounds. */
    void assertBounds() {
        checkOwner();
        if (executor.getMaximumPoolSize() != 1 || executor.getQueue().size() > maxJobs
                || cachedSourceCount > SOURCE_CACHE_LIMIT || cachedFloatBytes > tileCacheLimitBytes
                || peakCachedFloatBytes > tileCacheLimitBytes) {
            throw new IllegalStateException("regional page preparation exceeded a worker or source-cache bound");
        }
    }

    @Override
    public void close() {
        checkOwner();
        if (closed) return;
        closed = true;
        for (Runnable queued : executor.shutdownNow()) {
            if (queued instanceof Future<?>) ((Future<?>) queued).cancel(false);
        }
        try {
            if (!executor.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("regional page preparation worker did not stop after interruption");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while stopping regional page preparation", interrupted);
        }
        sources.clear();
        cachedSourceCount = 0;
        cachedFloatBytes = 0;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("regional page preparation is closed");
    }

    private void checkOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("regional page preparation must be controlled by its owner thread");
        }
    }
}
