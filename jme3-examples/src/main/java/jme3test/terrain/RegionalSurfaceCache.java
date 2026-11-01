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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDER AND CONTRIBUTORS "AS IS"
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

import com.jme3.material.Material;
import com.jme3.math.FastMath;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.opengl.GLFence;
import com.jme3.renderer.TextureUnitException;
import com.jme3.renderer.Camera;
import com.jme3.terrain.heightfield.HeightfieldSurfacePage;
import com.jme3.terrain.heightfield.HeightfieldSurfaceResidency;
import com.jme3.texture.Image;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.TextureArray;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

/**
 * Bounded material residency for one finite synthetic region.
 * Surface pages use canonical 0.5 metre cells and remain independent
 * of the terrain geometry page cache. All renderer and residency operations
 * belong to the thread that constructs this object.
 */
final class RegionalSurfaceCache implements AutoCloseable {
    static final float CANONICAL_SPACING = 0.5f;
    static final int REGION_CELLS = 1024;
    static final float REGION_METRES = REGION_CELLS * CANONICAL_SPACING;
    private static final int TABLE_CELL_CELLS = 64;
    private static final int ROOTS_PER_AXIS = 4;
    static final int PAGE_RESOLUTION = 64;
    static final int MIP_COUNT = 3;
    static final int PHYSICAL_SIDE = 68;
    static final int SLOT_COUNT = 64;
    static final int LAYER_COUNT = SLOT_COUNT * MIP_COUNT;
    private static final int FINE_REQUEST_LIMIT = 16;
    private static final long PADDED_PAGE_BYTES = (long) PHYSICAL_SIDE * PHYSICAL_SIDE * 25 * MIP_COUNT;
    private static final long MAX_UPLOAD_PAYLOAD_BYTES = PADDED_PAGE_BYTES * 2;
    private final long maxSourceCacheBytes;

    private final Renderer renderer;
    private final int regionCells;
    private final int tableSide;
    private final int rootBaseStride;
    private final int rootSpanCells;
    private final long fixedAtlasBytes;
    private final HeightfieldSurfaceResidency residency;
    private final ThreadPoolExecutor worker;
    private final LinkedHashMap<Long, RegionalSurfaceSource> sources = new LinkedHashMap<Long, RegionalSurfaceSource>(2, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, RegionalSurfaceSource> eldest) {
            return size() > 2;
        }
    };
    private final Map<HeightfieldSurfaceResidency.PageKey, Work> jobs = new LinkedHashMap<>();
    private final java.util.Set<HeightfieldSurfaceResidency.PageKey> readyPages = new java.util.LinkedHashSet<>();
    private final java.util.Set<HeightfieldSurfaceResidency.PageKey> desiredPages = new java.util.LinkedHashSet<>();
    private final List<ByteBuffer> albedoLayers = new ArrayList<>(LAYER_COUNT);
    private final List<ByteBuffer> normalLayers = new ArrayList<>(LAYER_COUNT);
    private final List<ByteBuffer> semanticLayers = new ArrayList<>(LAYER_COUNT);
    private final TextureArray albedo;
    private final TextureArray normal;
    private final TextureArray semantic;
    private final Texture2D pageTable;
    private final List<ByteBuffer> ownedBuffers = new ArrayList<>();
    private final ByteBuffer tableBuffer;
    private final Thread owner = Thread.currentThread();
    private long visibleRevision = 0;
    private long targetRevision = -1;
    private long pinnedRevision = -1;
    private final long desiredRegionX = 0;
    private final long desiredRegionZ = 0;
    private boolean frameOpen;
    private boolean closed;
    private boolean failed;
    private boolean atlasInitialized;
    private final float[] lastPageTable;
    private KeyBank newestKeyBank;
    private KeyBank previousKeyBank;
    private HeightfieldSurfaceResidency.Frame frame;
    private volatile long reportedCachedBytes;
    private volatile long reportedPeakCachedBytes;
    private volatile long reportedSourceSamples;
    private volatile int reportedSourceCount;
    private long paddedUploadBytes;
    private long uploadedFinePages;
    private long frameUploadBytes;
    private long maxFrameUploadBytes;

    RegionalSurfaceCache(Renderer renderer) {
        this(renderer, REGION_CELLS);
    }

    RegionalSurfaceCache(Renderer renderer, int regionCells) {
        if (regionCells != 1024 && regionCells != 2048) {
            throw new IllegalArgumentException("regional surface cache supports 1024 or 2048 canonical cells");
        }
        this.regionCells = regionCells;
        maxSourceCacheBytes = 2 * RegionalSurfaceSource.maximumCacheBytes(regionCells);
        tableSide = regionCells / TABLE_CELL_CELLS;
        rootBaseStride = regionCells / (ROOTS_PER_AXIS * PAGE_RESOLUTION);
        rootSpanCells = PAGE_RESOLUTION * rootBaseStride;
        lastPageTable = new float[tableSide * tableSide * 4];
        fixedAtlasBytes = (long) LAYER_COUNT * PHYSICAL_SIDE * PHYSICAL_SIDE * 25
                + (long) tableSide * tableSide * 4 * Float.BYTES;
        if (renderer == null) throw new IllegalArgumentException("renderer is required");
        if (!renderer.getCaps().contains(Caps.TextureArray)
                || !renderer.getCaps().contains(Caps.FloatTexture)
                || !renderer.getCaps().contains(Caps.FloatTextureFilter)
                || !renderer.getCaps().contains(Caps.HalfFloatTexture)
                || !renderer.getCaps().contains(Caps.HalfFloatTextureFilter)) {
            throw new UnsupportedOperationException("regional surface pages require filtered float and half-float texture arrays");
        }
        this.renderer = renderer;
        long pageCpuBytes = pageCpuArrayBytes();
        long pageEncodedBytes = pageEncodedBytes();
        residency = new HeightfieldSurfaceResidency(PAGE_RESOLUTION, MIP_COUNT,
                new HeightfieldSurfaceResidency.Limits(SLOT_COUNT, 4, pageCpuBytes * 4,
                        pageCpuBytes * 2, 2, pageEncodedBytes * 2, 16, 12));
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4), runnable -> {
                    Thread thread = new Thread(runnable, "regional-surface-compose");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        List<Image> a = new ArrayList<>(LAYER_COUNT);
        List<Image> n = new ArrayList<>(LAYER_COUNT);
        List<Image> s = new ArrayList<>(LAYER_COUNT);
        for (int i = 0; i < LAYER_COUNT; i++) {
            ByteBuffer ab = blankBuffer(8), nb = blankBuffer(16), sb = blankBuffer(1);
            albedoLayers.add(ab); normalLayers.add(nb); semanticLayers.add(sb);
            a.add(new Image(Image.Format.RGBA16F, PHYSICAL_SIDE, PHYSICAL_SIDE, ab, ColorSpace.Linear));
            n.add(new Image(Image.Format.RGBA32F, PHYSICAL_SIDE, PHYSICAL_SIDE, nb, ColorSpace.Linear));
            s.add(new Image(Image.Format.Luminance8, PHYSICAL_SIDE, PHYSICAL_SIDE, sb, ColorSpace.Linear));
        }
        albedo = new TextureArray(a);
        normal = new TextureArray(n);
        semantic = new TextureArray(s);
        configureArray(albedo, false);
        configureArray(normal, false);
        configureArray(semantic, true);
        tableBuffer = BufferUtils.createByteBuffer(tableSide * tableSide * 4 * Float.BYTES).order(ByteOrder.nativeOrder());
        ownedBuffers.add(tableBuffer);
        tableBuffer.limit(tableBuffer.capacity());
        for (int i = 0; i < tableSide * tableSide; i++) tableBuffer.putFloat(0).putFloat(0).putFloat(rootBaseStride).putFloat(0);
        tableBuffer.flip();
        Image table = new Image(Image.Format.RGBA32F, tableSide, tableSide, tableBuffer, ColorSpace.Linear);
        pageTable = new Texture2D(table);
        configureTexture(pageTable, true);
    }

    void requestRevision(long revision) {
        checkOwner();
        if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
        if (targetRevision == revision) return;
        targetRevision = revision;
        cancelObsoleteJobs();
    }

    /** Poll worker results, upload bounded payloads, and maintain bounded demand. */
    void pump(Camera camera) {
        checkOwner();
        ensureOpen();
        if (camera == null) throw new IllegalArgumentException("camera is required");
        pollFences();
        pollJobs();
        frameUploadBytes = 0;
        residency.uploadPrepared(this::uploadPage);
        maxFrameUploadBytes = Math.max(maxFrameUploadBytes, frameUploadBytes);
        if (targetRevision < 0) requestRevision(0);
        desiredPages.clear();
        addRoots(desiredPages, visibleRevision);
        addRoots(desiredPages, targetRevision);
        // Finish the drawable revision's complete fallback before scheduling a pending edit.
        ensureRoots(visibleRevision);
        if (targetRevision != visibleRevision) ensureRoots(targetRevision);
        if (rootsReady(visibleRevision, desiredRegionX, desiredRegionZ)) {
            chooseDetailDemand(visibleRevision, camera);
        }
        evictUnwanted();
        requestDesiredPages();
    }

    boolean isRevisionReady(long revision) {
        checkOwner();
        return rootsReady(revision, 0, 0);
    }

    void beginFrame(long revision, Camera camera) {
        checkOwner();
        ensureOpen();
        if (frameOpen) throw new IllegalStateException("surface frame is already open");
        if (camera == null) throw new IllegalArgumentException("camera is required");
        pollFences();
        if (!rootsReady(revision, desiredRegionX, desiredRegionZ)) {
            frame = null;
            frameOpen = true;
            return;
        }
        if (pinnedRevision != revision) {
            unpinRoots(pinnedRevision);
            pinRoots(revision);
            pinnedRevision = revision;
        }
        visibleRevision = revision;
        updatePageTable(revision);
        List<HeightfieldSurfaceResidency.PageKey> leaseKeys = visiblePageKeys(revision);
        frame = residency.acquire(leaseKeys);
        if (frame == null) throw new IllegalStateException("regional surface frame lease capacity exhausted");
        frameOpen = true;
    }

    void bind(Material material) {
        checkOwner();
        if (material == null) throw new IllegalArgumentException("material is required");
        if (!frameOpen || frame == null) {
            material.setBoolean("UseHeightfieldSurface", false);
            return;
        }
        HeightfieldSurfaceBindings.bind(material, albedo, normal, semantic, pageTable,
                PHYSICAL_SIDE, HeightfieldSurfacePage.GUTTER, MIP_COUNT,
                new Vector2f(0, 0), regionCells * CANONICAL_SPACING, CANONICAL_SPACING);
    }

    void endFrame() {
        checkOwner();
        if (!frameOpen) return;
        if (frame != null) {
            GLFence fence = renderer.createFence();
            if (!residency.submit(frame, new HeightfieldSurfaceResidency.Completion() {
                @Override public boolean isComplete() { return renderer.isFenceSignaled(fence); }
                @Override public void close() { renderer.deleteFence(fence); }
            })) {
                renderer.deleteFence(fence);
                throw new IllegalStateException("could not submit regional surface frame lease");
            }
        }
        frame = null;
        frameOpen = false;
    }

    boolean canRecreate() {
        checkOwner();
        pollFences();
        return residency.stats().frames == 0 && !frameOpen;
    }

    void recreate(long revision) {
        checkOwner();
        ensureOpen();
        if (!canRecreate()) throw new IllegalStateException("regional surface frames are still in flight");
        cancelAllJobs();
        disposeNativeStorage();
        residency.reset();
        readyPages.clear();
        desiredPages.clear();
        newestKeyBank = null;
        previousKeyBank = null;
        visibleRevision = revision;
        pinnedRevision = -1;
        targetRevision = revision;
        markImagesForReplay();
    }

    long getCachedBytes() { checkOwner(); return reportedCachedBytes; }
    long getPeakCachedBytes() { checkOwner(); return reportedPeakCachedBytes; }
    long getComposedSamples() { checkOwner(); return reportedSourceSamples; }
    long getPaddedUploadBytes() { checkOwner(); return paddedUploadBytes; }
    HeightfieldSurfaceResidency.Stats stats() { checkOwner(); return residency.stats(); }

    void printStats() {
        checkOwner();
        HeightfieldSurfaceResidency.Stats s = residency.stats();
        System.out.println("Regional surface: revision=" + visibleRevision + ", ready=" + s.readySlots
                + "/" + s.slotCapacity + ", pending=" + s.requests + ", prepared=" + s.preparedPages
                + ", sourceBytes=" + getCachedBytes() + ", peakSourceBytes=" + getPeakCachedBytes()
                + ", composedSamples=" + getComposedSamples() + ", uploadedBytes=" + paddedUploadBytes
                + ", finePages=" + uploadedFinePages + ", maxUploadFrameBytes=" + maxFrameUploadBytes);
        System.out.println("HEIGHTFIELD_SURFACE_CACHE_PASS ready=" + s.readySlots + " retired=" + s.retiredSlots
                + " jobs=" + jobs.size() + " prepared=" + s.preparedPages + " fixedAtlasBytes=" + fixedAtlasBytes
                + " sourceBytes=" + getCachedBytes() + " reservedBytes=" + (s.queuedBytes + s.preparedBytes)
                + " maxUploadFrameBytes=" + maxFrameUploadBytes + " finePages=" + uploadedFinePages);
    }

    /** Finite capture diagnostic: atlas layers and page table must match retained bytes. */
    void verifyNativeAlbedo(FrameBuffer restoreTarget) {
        checkOwner();
        if (!rootsReady(visibleRevision, 0, 0)) return;
        ByteBuffer pixels = BufferUtils.createByteBuffer(PHYSICAL_SIDE * PHYSICAL_SIDE * 16)
                .order(ByteOrder.nativeOrder());
        long channels = 0;
        try {
            for (int z = 0; z < ROOTS_PER_AXIS; z++) for (int x = 0; x < ROOTS_PER_AXIS; x++) {
                HeightfieldSurfaceResidency.PageKey key = key(visibleRevision, x * (long) rootSpanCells, z * (long) rootSpanCells, rootBaseStride);
                int slot = residency.ready(key).getSlot();
                for (int mip = 0; mip < MIP_COUNT; mip++) {
                    int layer = slot * MIP_COUNT + mip;
                    FrameBuffer target = new FrameBuffer(PHYSICAL_SIDE, PHYSICAL_SIDE, 1);
                    target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(albedo).layer(layer));
                    try {
                        pixels.clear();
                        renderer.readFrameBufferWithFormat(target, pixels, Image.Format.RGBA32F);
                        pixels.rewind();
                        FloatBuffer actual = pixels.asFloatBuffer();
                        ByteBuffer expected = albedoLayers.get(layer).duplicate().order(ByteOrder.nativeOrder());
                        for (int i = 0; i < PHYSICAL_SIDE * PHYSICAL_SIDE * 4; i++) {
                            float want = FastMath.convertHalfToFloat(expected.getShort(i * 2));
                            float got = actual.get(i);
                            if (!Float.isFinite(got) || Math.abs(got - want) > 1e-6f
                                    || (i % 4 == 3 && (want < 0 || want > 1))) {
                                throw new IllegalStateException("surface GPU storage mismatch revision=" + visibleRevision
                                        + " origin=" + key.originX + "," + key.originZ + " slot=" + slot
                                        + " mip=" + mip + " texel=" + i / 4 + " channel=" + i % 4
                                        + " expected=" + want + " actual=" + got);
                            }
                            channels++;
                        }
                    } finally {
                        renderer.setFrameBuffer(restoreTarget);
                        renderer.deleteFrameBuffer(target);
                    }
                }
            }
            FrameBuffer tableTarget = new FrameBuffer(tableSide, tableSide, 1);
            tableTarget.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(pageTable));
            try {
                pixels.clear();
                renderer.readFrameBufferWithFormat(tableTarget, pixels, Image.Format.RGBA32F);
                pixels.rewind();
                FloatBuffer actual = pixels.asFloatBuffer();
                for (int i = 0; i < lastPageTable.length; i++) {
                    float got = actual.get(i);
                    if (got != lastPageTable[i]) throw new IllegalStateException("surface GPU page table mismatch index="
                            + i + " expected=" + lastPageTable[i] + " actual=" + got);
                    channels++;
                }
            } finally {
                renderer.setFrameBuffer(restoreTarget);
                renderer.deleteFrameBuffer(tableTarget);
            }
            System.out.println("HEIGHTFIELD_SURFACE_GPU_ALBEDO_PASS revision=" + visibleRevision + " channels=" + channels);
        } finally {
            BufferUtils.destroyDirectBuffer(pixels);
        }
    }

    void assertBounds() {
        checkOwner();
        HeightfieldSurfaceResidency.Stats s = residency.stats();
        long reserved = s.queuedBytes + s.preparedBytes;
        long desiredExtras = desiredPages.stream().filter(page -> page.baseStride != rootBaseStride).count();
        if (s.requests > 4 || jobs.size() > 4 || s.preparedPages > 2 || s.readySlots + s.retiredSlots > SLOT_COUNT
                || s.slotCapacity != SLOT_COUNT || s.preparedBytes > pageCpuArrayBytes() * 2
                || s.queuedBytes > pageCpuArrayBytes() * 4 || desiredExtras > 32
                || reportedSourceCount > 2 || getCachedBytes() > maxSourceCacheBytes
                || fixedAtlasBytes + getCachedBytes() + reserved + MAX_UPLOAD_PAYLOAD_BYTES
                        > fixedAtlasBytes + 40L * 1024 * 1024) {
            throw new IllegalStateException("regional surface cache exceeded a configured bound");
        }
    }

    @Override public void close() {
        checkOwner();
        if (closed) return;
        if (frameOpen) endFrame();
        cancelAllJobs();
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                failed = true;
                throw new IllegalStateException("regional surface worker did not stop within five seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed = true;
            throw new IllegalStateException("interrupted while stopping regional surface worker", e);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            pollFences();
            if (residency.stats().frames == 0 && !frameOpen) break;
            try { Thread.sleep(1); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed = true;
                throw new IllegalStateException("interrupted while draining regional surface frames", e);
            }
        }
        if (residency.stats().frames != 0 || frameOpen) {
            failed = true;
            throw new IllegalStateException("regional surface frames remain in flight during close");
        }
        disposeNativeStorage();
        residency.close();
        for (ByteBuffer buffer : ownedBuffers) BufferUtils.destroyDirectBuffer(buffer);
        ownedBuffers.clear();
        newestKeyBank = null;
        previousKeyBank = null;
        closed = true;
    }

    private void addRoots(java.util.Set<HeightfieldSurfaceResidency.PageKey> into, long revision) {
        if (revision < 0) return;
        for (int z = 0; z < ROOTS_PER_AXIS; z++) for (int x = 0; x < ROOTS_PER_AXIS; x++) into.add(key(revision, x * (long) rootSpanCells, z * (long) rootSpanCells, rootBaseStride));
    }

    private void ensureRoots(long revision) {
        if (revision < 0) return;
        for (int z = 0; z < ROOTS_PER_AXIS; z++) for (int x = 0; x < ROOTS_PER_AXIS; x++) requestPage(key(revision, x * (long) rootSpanCells, z * (long) rootSpanCells, rootBaseStride));
    }

    private boolean rootsReady(long revision, long rx, long rz) {
        if (revision < 0) return false;
        for (int z = 0; z < ROOTS_PER_AXIS; z++) for (int x = 0; x < ROOTS_PER_AXIS; x++) {
            if (residency.ready(key(revision, rx + (long) x * rootSpanCells, rz + (long) z * rootSpanCells, rootBaseStride)) == null) return false;
        }
        return true;
    }

    private void chooseDetailDemand(long revision, Camera camera) {
        Vector3f location = camera.getLocation(), direction = camera.getDirection();
        double dx = direction.x, dz = direction.z, length = Math.hypot(dx, dz);
        if (length < 1e-6) { dx = 0; dz = 1; length = 1; }
        dx /= length; dz /= length;
        List<DistanceKey> candidates = new ArrayList<>(tableSide * tableSide);
        for (int z = 0; z < tableSide; z++) for (int x = 0; x < tableSide; x++) {
            long px = x * (long) TABLE_CELL_CELLS, pz = z * (long) TABLE_CELL_CELLS;
            double vx = (px + TABLE_CELL_CELLS * .5) * CANONICAL_SPACING - location.x;
            double vz = (pz + TABLE_CELL_CELLS * .5) * CANONICAL_SPACING - location.z;
            double along = vx * dx + vz * dz;
            double across = Math.abs(vx * dz - vz * dx);
            double score = Math.max(0, -along) * 4 + across * 1.5 + Math.hypot(vx, vz) * .15;
            candidates.add(new DistanceKey(px, pz, score));
        }
        candidates.sort(Comparator.comparingDouble(value -> value.distance));
        int selected = 0;
        for (DistanceKey candidate : candidates) {
            if (selected >= FINE_REQUEST_LIMIT) break;
            HeightfieldSurfaceResidency.PageKey fine = key(revision, candidate.x, candidate.z, 1);
            HeightfieldSurfaceResidency.PageKey middle = key(revision, Math.floorDiv(candidate.x, 128) * 128,
                    Math.floorDiv(candidate.z, 128) * 128, 2);
            if (desiredPages.contains(fine)) continue;
            desiredPages.add(middle);
            desiredPages.add(fine);
            selected++;
        }
    }

    private void requestDesiredPages() {
        for (HeightfieldSurfaceResidency.PageKey page : desiredPages) if (page.baseStride != rootBaseStride) requestPage(page);
    }

    private void requestPage(HeightfieldSurfaceResidency.PageKey key) {
        if (residency.ready(key) != null || jobs.containsKey(key) || jobs.size() >= 4) return;
        HeightfieldSurfaceResidency.Request request = residency.request(key);
        if (request == null) return;
        Work work = new Work(request);
        jobs.put(key, work);
        try { work.future = worker.submit(() -> compose(key)); }
        catch (RuntimeException ex) { jobs.remove(key); residency.cancel(request); throw ex; }
    }

    private HeightfieldSurfacePage compose(HeightfieldSurfaceResidency.PageKey key) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        RegionalSurfaceSource source = sources.get(key.revision.getHeight());
        if (source == null) {
            source = new RegionalSurfaceSource(regionCells, key.revision.getHeight());
            sources.put(key.revision.getHeight(), source);
        }
        HeightfieldSurfacePage page = new HeightfieldSurfacePage(key.revision, key.originX, key.originZ,
                PAGE_RESOLUTION, key.baseStride, MIP_COUNT, source);
        publishSourceMetrics();
        return page;
    }

    private void pollJobs() {
        for (Map.Entry<HeightfieldSurfaceResidency.PageKey, Work> entry : jobs.entrySet()) {
            Work work = entry.getValue();
            if (work.future == null || !work.future.isDone()) continue;
            if (work.cancelled) { work.completed = true; continue; }
            if (work.page == null) {
                try { work.page = work.future.get(); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failWork(work, new IllegalStateException("interrupted while collecting regional page", e));
                } catch (ExecutionException | java.util.concurrent.CancellationException e) {
                    failWork(work, new IllegalStateException("regional surface preparation failed for " + entry.getKey(), e));
                }
            }
            if (work.page != null && residency.isPending(work.request)
                    && residency.complete(work.request, work.page)) work.completed = true;
        }
        jobs.entrySet().removeIf(entry -> entry.getValue().completed);
    }

    private void failWork(Work work, RuntimeException failure) {
        failed = true;
        residency.cancel(work.request);
        throw failure;
    }

    private boolean uploadPage(int slot, HeightfieldSurfacePage page) {
        List<ByteBuffer> payloads = new ArrayList<>(9);
        try {
            initializeAtlas();
            for (int mip = 0; mip < MIP_COUNT; mip++) {
                payloads.add(padded(page.createAlbedoImage(mip), 8));
                payloads.add(padded(page.createNormalImage(mip), 16));
                payloads.add(padded(page.createSemanticImage(mip), 1));
            }
            for (int mip = 0; mip < MIP_COUNT; mip++) {
                int layer = slot * MIP_COUNT + mip;
                ByteBuffer a = payloads.get(mip * 3), n = payloads.get(mip * 3 + 1), sem = payloads.get(mip * 3 + 2);
                copy(a, albedoLayers.get(layer)); copy(n, normalLayers.get(layer)); copy(sem, semanticLayers.get(layer));
                renderer.modifyTexture(albedo, temporary(Image.Format.RGBA16F, a), layer, 0, 0);
                renderer.modifyTexture(normal, temporary(Image.Format.RGBA32F, n), layer, 0, 0);
                renderer.modifyTexture(semantic, temporary(Image.Format.Luminance8, sem), layer, 0, 0);
            }
            HeightfieldSurfacePage.Revision pageRevision = page.getRevision();
            readyPages.add(key(pageRevision.getHeight(), page.getOriginX(), page.getOriginZ(), page.getBaseStride()));
            paddedUploadBytes += PADDED_PAGE_BYTES;
            frameUploadBytes += PADDED_PAGE_BYTES;
            if (page.getBaseStride() == 1) uploadedFinePages++;
            return true;
        } finally { for (ByteBuffer buffer : payloads) BufferUtils.destroyDirectBuffer(buffer); }
    }

    private void initializeAtlas() {
        if (atlasInitialized) return;
        try {
            renderer.setTexture(0, albedo); renderer.setTexture(1, normal);
            renderer.setTexture(2, semantic); renderer.setTexture(3, pageTable);
            atlasInitialized = true;
        } catch (TextureUnitException e) {
            throw new IllegalStateException("could not initialize regional surface texture arrays", e);
        }
    }

    private void updatePageTable(long revision) {
        float[] next = new float[lastPageTable.length];
        tableBuffer.clear();
        for (int z = 0; z < tableSide; z++) for (int x = 0; x < tableSide; x++) {
            long px = x * (long) TABLE_CELL_CELLS, pz = z * (long) TABLE_CELL_CELLS;
            HeightfieldSurfaceResidency.PageKey page = finestReady(revision, px, pz);
            if (page == null) throw new IllegalStateException("no ready regional surface fallback for table cell " + x + "," + z);
            HeightfieldSurfaceResidency.Handle handle = residency.ready(page);
            int i = (z * tableSide + x) * 4;
            next[i] = (float) page.originX; next[i + 1] = (float) page.originZ;
            next[i + 2] = page.baseStride; next[i + 3] = handle.getSlot() * MIP_COUNT;
            tableBuffer.putFloat(next[i]).putFloat(next[i + 1]).putFloat(next[i + 2]).putFloat(next[i + 3]);
        }
        tableBuffer.flip();
        if (!java.util.Arrays.equals(next, lastPageTable)) {
            System.arraycopy(next, 0, lastPageTable, 0, next.length);
            pageTable.getImage().setUpdateNeeded();
        }
    }

    private HeightfieldSurfaceResidency.PageKey finestReady(long revision, long x, long z) {
        HeightfieldSurfaceResidency.PageKey fine = key(revision, x, z, 1);
        if (desiredPages.contains(fine) && residency.ready(fine) != null) return fine;
        HeightfieldSurfaceResidency.PageKey middle = key(revision, Math.floorDiv(x, 128) * 128,
                Math.floorDiv(z, 128) * 128, 2);
        if (desiredPages.contains(middle) && residency.ready(middle) != null) return middle;
        HeightfieldSurfaceResidency.PageKey root = key(revision, Math.floorDiv(x, rootSpanCells) * (long) rootSpanCells,
                Math.floorDiv(z, rootSpanCells) * (long) rootSpanCells, rootBaseStride);
        return desiredPages.contains(root) && residency.ready(root) != null ? root : null;
    }

    private List<HeightfieldSurfaceResidency.PageKey> visiblePageKeys(long revision) {
        List<HeightfieldSurfaceResidency.PageKey> keys = new ArrayList<>(tableSide * tableSide);
        for (int z = 0; z < tableSide; z++) for (int x = 0; x < tableSide; x++) {
            HeightfieldSurfaceResidency.PageKey page = finestReady(revision, x * (long) TABLE_CELL_CELLS, z * (long) TABLE_CELL_CELLS);
            if (page == null) throw new IllegalStateException("regional page table references a missing page");
            keys.add(page);
        }
        return keys;
    }

    private void pinRoots(long revision) {
        if (revision < 0) return;
        for (int z = 0; z < ROOTS_PER_AXIS; z++) for (int x = 0; x < ROOTS_PER_AXIS; x++) residency.pin(key(revision, x * (long) rootSpanCells, z * (long) rootSpanCells, rootBaseStride));
    }

    private void unpinRoots(long revision) {
        if (revision < 0) return;
        for (int z = 0; z < ROOTS_PER_AXIS; z++) for (int x = 0; x < ROOTS_PER_AXIS; x++) residency.unpin(key(revision, x * (long) rootSpanCells, z * (long) rootSpanCells, rootBaseStride));
    }

    private void evictUnwanted() {
        for (HeightfieldSurfaceResidency.PageKey page : new ArrayList<>(readyPages)) {
            if (desiredPages.contains(page)) continue;
            if (residency.evict(page)) readyPages.remove(page);
        }
    }

    private HeightfieldSurfaceResidency.PageKey key(long revision, long x, long z, int stride) {
        return keyBank(revision).key(x, z, stride);
    }

    private KeyBank keyBank(long revision) {
        if (newestKeyBank != null && newestKeyBank.revisionNumber == revision) {
            return newestKeyBank;
        }
        if (previousKeyBank != null && previousKeyBank.revisionNumber == revision) {
            KeyBank swap = newestKeyBank;
            newestKeyBank = previousKeyBank;
            previousKeyBank = swap;
            return newestKeyBank;
        }
        previousKeyBank = newestKeyBank;
        newestKeyBank = new KeyBank(revision, regionCells, rootBaseStride);
        return newestKeyBank;
    }

    /** Lazy immutable keys for one revision; recreated keys remain value-equal. */
    static final class KeyBank {
        final long revisionNumber;
        final HeightfieldSurfacePage.Revision revision;
        private final int regionCells;
        private final int[] axes = new int[9];
        private final HeightfieldSurfaceResidency.PageKey[][] tables =
                new HeightfieldSurfaceResidency.PageKey[9][];
        private int allocatedKeys;

        KeyBank(long revisionNumber, int regionCells, int rootBaseStride) {
            if (revisionNumber < 0 || regionCells <= 0) {
                throw new IllegalArgumentException("revision and region dimensions must be nonnegative");
            }
            this.revisionNumber = revisionNumber;
            this.regionCells = regionCells;
            revision = new HeightfieldSurfacePage.Revision(revisionNumber, revisionNumber, revisionNumber);
            installStride(1);
            installStride(2);
            installStride(rootBaseStride);
        }

        private void installStride(int stride) {
            long span = (long) PAGE_RESOLUTION * stride;
            if (stride < 1 || stride > 8 || (stride & (stride - 1)) != 0
                    || regionCells % span != 0) {
                throw new IllegalArgumentException("stride does not evenly partition the surface region");
            }
            int axis = (int) (regionCells / span);
            if (tables[stride] == null) {
                axes[stride] = axis;
                tables[stride] = new HeightfieldSurfaceResidency.PageKey[axis * axis];
            }
        }

        HeightfieldSurfaceResidency.PageKey key(long x, long z, int stride) {
            if (stride < 1 || stride > 8 || tables[stride] == null) {
                throw new IllegalArgumentException("unsupported regional surface stride");
            }
            long span = (long) PAGE_RESOLUTION * stride;
            if (x < 0 || z < 0 || x >= regionCells || z >= regionCells
                    || x % span != 0 || z % span != 0) {
                throw new IndexOutOfBoundsException("surface key origin is outside or misaligned to its page grid");
            }
            int axis = axes[stride];
            int index = (int) (z / span) * axis + (int) (x / span);
            HeightfieldSurfaceResidency.PageKey result = tables[stride][index];
            if (result == null) {
                result = new HeightfieldSurfaceResidency.PageKey(revision, x, z, stride);
                tables[stride][index] = result;
                allocatedKeys++;
            }
            return result;
        }

        int getAllocatedKeyCount() {
            return allocatedKeys;
        }
    }

    private void cancelObsoleteJobs() {
        java.util.Iterator<Map.Entry<HeightfieldSurfaceResidency.PageKey, Work>> iterator = jobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<HeightfieldSurfaceResidency.PageKey, Work> entry = iterator.next();
            long rev = entry.getKey().revision.getHeight();
            if (rev == targetRevision || rev == visibleRevision) continue;
            Work work = entry.getValue();
            cancelFuture(work.future);
            residency.cancel(work.request);
            work.cancelled = true;
            if (work.future == null || work.future.isDone()) iterator.remove();
        }
    }

    private void cancelAllJobs() {
        java.util.Iterator<Work> iterator = jobs.values().iterator();
        while (iterator.hasNext()) {
            Work work = iterator.next();
            cancelFuture(work.future);
            residency.cancel(work.request);
            work.cancelled = true;
            if (work.future == null || work.future.isDone()) iterator.remove();
        }
        worker.getQueue().clear();
    }

    private void pollFences() { residency.poll(); }

    private void cancelFuture(Future<?> future) {
        // Remove and cancel queued work. A running composition is allowed to finish so
        // its future remains counted against the four-job budget until it really ends.
        if (future instanceof Runnable && worker.remove((Runnable) future)) future.cancel(false);
    }

    private void markImagesForReplay() {
        albedo.getImage().setUpdateNeeded();
        normal.getImage().setUpdateNeeded();
        semantic.getImage().setUpdateNeeded();
        pageTable.getImage().setUpdateNeeded();
        java.util.Arrays.fill(lastPageTable, Float.NaN);
        atlasInitialized = false;
        tableBuffer.clear();
        for (int i = 0; i < tableSide * tableSide; i++) tableBuffer.putFloat(0).putFloat(0).putFloat(rootBaseStride).putFloat(0);
        tableBuffer.flip();
    }

    private void disposeNativeStorage() {
        renderer.deleteImage(albedo.getImage()); renderer.deleteImage(normal.getImage());
        renderer.deleteImage(semantic.getImage()); renderer.deleteImage(pageTable.getImage());
    }

    private void publishSourceMetrics() {
        long bytes = 0, peak = 0, samples = 0;
        for (RegionalSurfaceSource source : sources.values()) {
            bytes += source.getCachedBytes();
            peak += source.getPeakCachedBytes();
            samples += source.getComposedSamples();
        }
        reportedCachedBytes = bytes;
        reportedPeakCachedBytes = Math.max(reportedPeakCachedBytes, peak);
        reportedSourceSamples = samples;
        reportedSourceCount = sources.size();
    }

    private static long pageCpuArrayBytes() {
        long bytes = 0;
        for (int mip = 0; mip < MIP_COUNT; mip++) {
            long side = (PAGE_RESOLUTION >> mip) + 2L * HeightfieldSurfacePage.GUTTER;
            bytes += side * side * HeightfieldSurfacePage.CPU_BYTES_PER_TEXEL;
        }
        return bytes;
    }

    private static long pageEncodedBytes() {
        long bytes = 0;
        for (int mip = 0; mip < MIP_COUNT; mip++) {
            long side = (PAGE_RESOLUTION >> mip) + 2L * HeightfieldSurfacePage.GUTTER;
            bytes += side * side * 25;
        }
        return bytes;
    }

    private ByteBuffer blankBuffer(int bytesPerTexel) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(PHYSICAL_SIDE * PHYSICAL_SIDE * bytesPerTexel);
        ownedBuffers.add(buffer);
        return buffer;
    }

    private static ByteBuffer padded(Image source, int bytesPerTexel) {
        int side = source.getWidth();
        ByteBuffer input = source.getData(0).duplicate();
        ByteBuffer output = BufferUtils.createByteBuffer(PHYSICAL_SIDE * PHYSICAL_SIDE * bytesPerTexel)
                .order(ByteOrder.nativeOrder());
        try {
            for (int z = 0; z < PHYSICAL_SIDE; z++) for (int x = 0; x < PHYSICAL_SIDE; x++) {
                int sx = Math.max(0, Math.min(side - 1, x));
                int sz = Math.max(0, Math.min(side - 1, z));
                int index = (sz * side + sx) * bytesPerTexel;
                for (int b = 0; b < bytesPerTexel; b++) output.put(input.get(index + b));
            }
            output.flip();
            return output;
        } finally { BufferUtils.destroyDirectBuffer(source.getData(0)); }
    }

    private static void copy(ByteBuffer source, ByteBuffer destination) {
        ByteBuffer src = source.duplicate();
        destination.clear();
        destination.put(src);
        destination.flip();
    }

    private Image temporary(Image.Format format, ByteBuffer data) {
        return new Image(format, PHYSICAL_SIDE, PHYSICAL_SIDE, data, ColorSpace.Linear);
    }

    private static void configureArray(TextureArray texture, boolean categorical) {
        texture.setMinFilter(categorical ? Texture.MinFilter.NearestNoMipMaps : Texture.MinFilter.BilinearNoMipMaps);
        texture.setMagFilter(categorical ? Texture.MagFilter.Nearest : Texture.MagFilter.Bilinear);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
        texture.setAnisotropicFilter(1);
    }

    private static void configureTexture(Texture texture, boolean nearest) {
        texture.setMinFilter(nearest ? Texture.MinFilter.NearestNoMipMaps : Texture.MinFilter.BilinearNoMipMaps);
        texture.setMagFilter(nearest ? Texture.MagFilter.Nearest : Texture.MagFilter.Bilinear);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
    }

    private void checkOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("regional surface cache must be used by its owner thread"); }
    private void ensureOpen() { if (closed || failed) throw new IllegalStateException("regional surface cache is closed or failed"); }

    private static final class Work {
        final HeightfieldSurfaceResidency.Request request;
        Future<HeightfieldSurfacePage> future;
        HeightfieldSurfacePage page;
        boolean completed;
        boolean cancelled;
        Work(HeightfieldSurfaceResidency.Request request) { this.request = request; }
    }
    private static final class DistanceKey {
        final long x, z;
        final double distance;
        DistanceKey(long x, long z, double distance) { this.x = x; this.z = z; this.distance = distance; }
    }
}
