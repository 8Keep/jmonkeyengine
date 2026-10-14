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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, single-owner residency state for immutable heightfield pages.
 * CPU workers may hold {@link Request}s and call {@link #complete} only after
 * returning to the owner thread. This class does not create threads or own GPU
 * resources. Slot indices are stable physical storage locations supplied to
 * the upload callback.
 *
 * <p>Call {@link #reset()} only after the owner has discarded the physical GPU
 * storage represented by every slot. Call {@link #close()} under the same
 * precondition. Both invalidate all handles and jobs before closing registered
 * completion fences. A failed fence poll retains its frame references until a
 * later reset/close, so uncertain GPU use can never cause premature slot reuse.
 */
public final class HeightfieldResidency implements AutoCloseable {
    private final int pageCells;
    private final long pageBytes;
    private final Limits limits;
    private final Thread ownerThread;
    private final Slot[] slots;
    private final LinkedHashMap<PageKey, Request> requests = new LinkedHashMap<>();
    private final LinkedHashMap<PageKey, Prepared> prepared = new LinkedHashMap<>();
    private final Map<PageKey, Slot> ready = new HashMap<>();
    private final Map<PageKey, Integer> pins = new HashMap<>();
    private final List<Frame> frames = new ArrayList<>();
    private long epoch = 1;
    private long nextToken = 1;
    private long queuedBytes;
    private long preparedBytes;
    private int retiredCount;
    private boolean closed;
    private boolean externalCallback;

    public HeightfieldResidency(int pageCells, Limits limits) {
        if (pageCells < 2 || pageCells > 256) {
            throw new IllegalArgumentException("pageCells must be between 2 and 256");
        }
        this.limits = Objects.requireNonNull(limits, "limits");
        this.pageCells = pageCells;
        long side = (long) pageCells + 3;
        this.pageBytes = side * side * Float.BYTES;
        if (limits.slots == 0) {
            throw new IllegalArgumentException("at least one physical slot is required");
        }
        if (limits.queuedReservedBytes < pageBytes || limits.preparedBytes < pageBytes
                || limits.uploadBytesPerCall < pageBytes) {
            throw new IllegalArgumentException("byte limits must fit at least one page (" + pageBytes + ")");
        }
        this.ownerThread = Thread.currentThread();
        this.slots = new Slot[limits.slots];
        for (int i = 0; i < slots.length; i++) slots[i] = new Slot(i);
    }

    /** Start bounded CPU preparation. Returns null when pressure or duplication prevents admission. */
    public Request request(PageKey key) {
        checkUsable();
        Objects.requireNonNull(key, "key");
        if (ready.containsKey(key) || requests.containsKey(key) || prepared.containsKey(key)
                || requests.size() >= limits.activeRequests || nextToken == 0
                || queuedBytes > limits.queuedReservedBytes - pageBytes) {
            return null;
        }
        long token = nextToken;
        nextToken = token == Long.MAX_VALUE ? 0 : token + 1;
        Request request = new Request(this, token, epoch, key);
        requests.put(key, request);
        queuedBytes += pageBytes;
        return request;
    }

    /** Complete a request on the owner thread. On prepared-byte pressure it remains retryable. */
    public boolean complete(Request request, HeightfieldPage page) {
        checkUsable();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(page, "page");
        if (request.owner != this) return false;
        Request live = requests.get(request.key);
        if (live != request || request.epoch != epoch) return false;
        if (page.getCells() != pageCells || pageBytes != expectedPageBytes(page)) {
            throw new IllegalArgumentException("page does not match the fixed residency payload size");
        }
        if (preparedBytes > limits.preparedBytes - pageBytes) return false;
        requests.remove(request.key);
        queuedBytes -= pageBytes;
        prepared.put(request.key, new Prepared(request.key, page, request.token));
        preparedBytes += pageBytes;
        return true;
    }

    /** Cancel an active request or its prepared payload. */
    public boolean cancel(Request request) {
        checkUsable();
        Objects.requireNonNull(request, "request");
        if (request.owner != this) return false;
        if (requests.get(request.key) == request && request.epoch == epoch) {
            requests.remove(request.key);
            queuedBytes -= pageBytes;
            return true;
        }
        Prepared value = prepared.get(request.key);
        if (request.epoch == epoch && value != null && value.token == request.token) {
            dropPrepared(value);
            return true;
        }
        return false;
    }

    /** True while this pool still owns the request as an active, retryable job. */
    public boolean isPending(Request request) {
        checkUsable();
        return request != null && request.owner == this && request.epoch == epoch
                && requests.get(request.key) == request;
    }

    /** Cancel prepared data by key, for owners that no longer need a request token. */
    public boolean cancelPrepared(PageKey key) {
        checkUsable();
        Prepared value = prepared.get(Objects.requireNonNull(key, "key"));
        if (value == null) return false;
        dropPrepared(value);
        return true;
    }

    /** Upload prepared pages in FIFO order, publishing a page only after its callback succeeds. */
    public int uploadPrepared(Uploader uploader) {
        checkUsable();
        Objects.requireNonNull(uploader, "uploader");
        int attempts = 0;
        long bytes = 0;
        for (Prepared value : prepared.values().toArray(new Prepared[0])) {
            if (attempts >= limits.uploadsPerCall || bytes > limits.uploadBytesPerCall - pageBytes) break;
            Slot slot = value.reservedSlot;
            if (slot == null) {
                slot = findFreeSlot();
                if (slot == null) break;
                slot.reserved = value;
                value.reservedSlot = slot;
            }
            if (slot.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("slot generation exhausted");
            }
            attempts++;
            bytes += pageBytes;
            // A throwing callback leaves both the payload and its target slot reserved.
            externalCallback = true;
            try {
                uploader.upload(slot.index, value.page);
            } finally {
                externalCallback = false;
            }
            slot.generation++;
            slot.key = value.key;
            slot.page = value.page;
            slot.retired = false;
            slot.references = 0;
            slot.handle = new Handle(this, slot.key, slot.index, slot.generation, epoch);
            slot.reserved = null;
            value.reservedSlot = null;
            ready.put(value.key, slot);
            dropPrepared(value);
        }
        return attempts;
    }

    /** Return the current ready handle, or null if the key is not resident. */
    public Handle ready(PageKey key) {
        checkUsable();
        Slot slot = ready.get(Objects.requireNonNull(key, "key"));
        return slot == null ? null : handle(slot);
    }

    /** Return payload while its exact slot generation is retained, including a retired lease. */
    public HeightfieldPage get(Handle handle) {
        checkUsable();
        Slot slot = retainedSlot(handle);
        return slot == null ? null : slot.page;
    }

    /** True only while this handle is the current mapping for its key. */
    public boolean isCurrent(Handle handle) {
        checkUsable();
        Slot slot = retainedSlot(handle);
        return slot != null && !slot.retired && ready.get(handle.key) == slot;
    }

    /** True while the exact payload and generation remain retained by a live slot. */
    public boolean isRetained(Handle handle) {
        checkUsable();
        return retainedSlot(handle) != null;
    }

    /** Pin a ready root against eviction. Pins are reference counted. */
    public boolean pin(PageKey key) {
        checkUsable();
        if (!ready.containsKey(Objects.requireNonNull(key, "key"))) return false;
        int count = pins.getOrDefault(key, 0);
        if (count == Integer.MAX_VALUE) throw new IllegalStateException("pin count exhausted");
        pins.put(key, count + 1);
        return true;
    }

    public boolean unpin(PageKey key) {
        checkUsable();
        Objects.requireNonNull(key, "key");
        Integer count = pins.get(key);
        if (count == null) return false;
        if (count == 1) pins.remove(key); else pins.put(key, count - 1);
        return true;
    }

    /** Evict a ready page. A leased page retires until all submitted frames complete. */
    public boolean evict(PageKey key) {
        checkUsable();
        Objects.requireNonNull(key, "key");
        Slot slot = ready.get(key);
        if (slot == null || pins.containsKey(key)) return false;
        if (slot.references > 0 && retiredCount >= limits.retiredSlots) return false;
        ready.remove(key);
        if (slot.references > 0) {
            slot.retired = true;
            retiredCount++;
        } else {
            clearSlot(slot);
        }
        return true;
    }

    /** Atomically lease each unique requested ready key; null means no lease was acquired. */
    public Frame acquire(Collection<PageKey> keys) {
        checkUsable();
        Objects.requireNonNull(keys, "keys");
        if (frames.size() >= limits.inFlightFrames) return null;
        LinkedHashSet<PageKey> unique = new LinkedHashSet<>();
        for (PageKey key : keys) unique.add(Objects.requireNonNull(key, "key"));
        LinkedHashMap<PageKey, Handle> captured = new LinkedHashMap<>();
        for (PageKey key : unique) {
            Slot slot = ready.get(key);
            if (slot == null || slot.references == Integer.MAX_VALUE) return null;
            captured.put(key, handle(slot));
        }
        for (Handle handle : captured.values()) retainedSlot(handle).references++;
        Frame frame = new Frame(this, epoch, captured);
        frames.add(frame);
        return frame;
    }

    /** Register a completion fence once all draws using the frame have been submitted. */
    public boolean submit(Frame frame, Completion completion) {
        checkUsable();
        Objects.requireNonNull(completion, "completion");
        if (!ownsLiveFrame(frame) || frame.state != Frame.ACQUIRED) return false;
        frame.completion = completion;
        frame.state = Frame.SUBMITTED;
        return true;
    }

    /** Release only an acquired frame that the owner knows was never drawn. */
    public boolean abort(Frame frame) {
        checkUsable();
        if (!ownsLiveFrame(frame) || frame.state != Frame.ACQUIRED) return false;
        releaseFrame(frame);
        return true;
    }

    /** Poll submitted fences. Poll or close failures poison the frame and keep every lease. */
    public int poll() {
        checkUsable();
        int released = 0;
        for (Frame frame : new ArrayList<>(frames)) {
            if (frame.state != Frame.SUBMITTED) continue;
            final boolean complete;
            try {
                externalCallback = true;
                complete = frame.completion.isComplete();
            } catch (RuntimeException ex) {
                frame.state = Frame.POISONED;
                throw new IllegalStateException("completion poll failed; frame leases retained", ex);
            } finally {
                externalCallback = false;
            }
            if (!complete) continue;
            try {
                externalCallback = true;
                frame.completion.close();
            } catch (RuntimeException ex) {
                frame.state = Frame.POISONED;
                throw new IllegalStateException("completion close failed; frame leases retained", ex);
            } finally {
                externalCallback = false;
            }
            releaseFrame(frame);
            released++;
        }
        return released;
    }

    /** Invalidate every request, mapping and handle after physical GPU storage is discarded. */
    public void reset() {
        checkOwner();
        ensureEpochAvailable();
        List<Completion> completions = completionsToClose();
        epoch++;
        clearState();
        closeCompletions(completions);
    }

    /** Clear this owner after physical GPU storage is discarded; this object cannot be reused. */
    @Override
    public void close() {
        checkOwner();
        if (closed) return;
        ensureEpochAvailable();
        List<Completion> completions = completionsToClose();
        epoch++;
        clearState();
        closed = true;
        closeCompletions(completions);
    }

    public Stats stats() {
        checkUsable();
        int resident = 0;
        for (Slot slot : slots) if (slot.page != null || slot.reserved != null) resident++;
        return new Stats(requests.size(), queuedBytes, prepared.size(), preparedBytes,
                ready.size(), retiredCount, frames.size(), slots.length, resident,
                (long) slots.length * pageBytes);
    }

    public int getPageCells() { return pageCells; }
    public long getPageBytes() { return pageBytes; }
    public Limits getLimits() { return limits; }

    private void dropPrepared(Prepared value) {
        prepared.remove(value.key);
        preparedBytes -= pageBytes;
        if (value.reservedSlot != null) {
            value.reservedSlot.reserved = null;
            value.reservedSlot = null;
        }
    }

    private Slot findFreeSlot() {
        for (Slot slot : slots) if (slot.page == null && slot.reserved == null) return slot;
        return null;
    }

    private Handle handle(Slot slot) {
        return slot.handle;
    }

    private Slot retainedSlot(Handle handle) {
        if (handle == null || handle.owner != this || handle.epoch != epoch
                || handle.slot < 0 || handle.slot >= slots.length) return null;
        Slot slot = slots[handle.slot];
        return slot.key != null && slot.generation == handle.generation && slot.key.equals(handle.key)
                && slot.page != null ? slot : null;
    }

    private boolean ownsLiveFrame(Frame frame) {
        return frame != null && frame.owner == this && frame.epoch == epoch && frames.contains(frame);
    }

    private void releaseFrame(Frame frame) {
        for (Handle handle : frame.handles.values()) {
            Slot slot = retainedSlot(handle);
            if (slot == null || slot.references <= 0) throw new IllegalStateException("frame lease invariant failed");
            slot.references--;
            if (slot.retired && slot.references == 0) clearSlot(slot);
        }
        frame.state = Frame.RELEASED;
        frames.remove(frame);
    }

    private void clearSlot(Slot slot) {
        if (slot.retired) retiredCount--;
        slot.key = null;
        slot.page = null;
        slot.handle = null;
        slot.retired = false;
        slot.references = 0;
        slot.reserved = null;
    }

    private long expectedPageBytes(HeightfieldPage page) {
        long side = (long) page.getCells() + 3;
        return side * side * Float.BYTES;
    }

    private void clearState() {
        requests.clear();
        prepared.clear();
        ready.clear();
        pins.clear();
        queuedBytes = 0;
        preparedBytes = 0;
        for (Slot slot : slots) clearSlot(slot);
        retiredCount = 0;
        for (Frame frame : frames) frame.state = Frame.RELEASED;
        frames.clear();
    }

    private List<Completion> completionsToClose() {
        List<Completion> result = new ArrayList<>();
        for (Frame frame : frames) if (frame.completion != null) result.add(frame.completion);
        return result;
    }

    private void closeCompletions(List<Completion> completions) {
        RuntimeException failure = null;
        for (Completion completion : completions) {
            try {
                externalCallback = true;
                completion.close();
            } catch (RuntimeException ex) {
                if (failure == null) failure = new IllegalStateException("one or more completion fences failed to close");
                failure.addSuppressed(ex);
            } finally {
                externalCallback = false;
            }
        }
        if (failure != null) throw failure;
    }

    private void ensureEpochAvailable() {
        if (epoch == Long.MAX_VALUE) throw new IllegalStateException("residency epoch exhausted");
    }

    private void checkUsable() {
        checkOwner();
        if (closed) throw new IllegalStateException("residency is closed");
    }

    private void checkOwner() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("residency must be accessed by its owner thread");
        if (externalCallback) throw new IllegalStateException("residency cannot be re-entered from an external callback");
    }

    /** Independent hard bounds. Byte limits include complete float payloads. */
    public static final class Limits {
        public final int slots;
        public final int activeRequests;
        public final long queuedReservedBytes;
        public final long preparedBytes;
        public final int uploadsPerCall;
        public final long uploadBytesPerCall;
        public final int retiredSlots;
        public final int inFlightFrames;

        /** Order: slots, activeRequests, queuedReservedBytes, preparedBytes, uploadsPerCall, uploadBytesPerCall, retiredSlots, inFlightFrames. */
        public Limits(int slots, int activeRequests, long queuedReservedBytes, long preparedBytes,
                int uploadsPerCall, long uploadBytesPerCall, int retiredSlots, int inFlightFrames) {
            if (slots < 0 || activeRequests < 0 || queuedReservedBytes < 0 || preparedBytes < 0
                    || uploadsPerCall < 0 || uploadBytesPerCall < 0 || retiredSlots < 0 || inFlightFrames < 0) {
                throw new IllegalArgumentException("all residency limits must be nonnegative");
            }
            this.slots = slots;
            this.activeRequests = activeRequests;
            this.queuedReservedBytes = queuedReservedBytes;
            this.preparedBytes = preparedBytes;
            this.uploadsPerCall = uploadsPerCall;
            this.uploadBytesPerCall = uploadBytesPerCall;
            this.retiredSlots = retiredSlots;
            this.inFlightFrames = inFlightFrames;
        }
    }

    /** Immutable page identity. Stride is a positive power of two. */
    public static final class PageKey {
        public final long revision;
        public final int x;
        public final int z;
        public final int stride;

        public PageKey(long revision, int x, int z, int stride) {
            if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
            if (stride <= 0 || (stride & (stride - 1)) != 0) throw new IllegalArgumentException("stride must be a positive power of two");
            this.revision = revision;
            this.x = x;
            this.z = z;
            this.stride = stride;
        }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof PageKey)) return false;
            PageKey other = (PageKey) obj;
            return revision == other.revision && x == other.x && z == other.z && stride == other.stride;
        }
        @Override public int hashCode() {
            int hash = Long.hashCode(revision);
            hash = 31 * hash + x;
            hash = 31 * hash + z;
            return 31 * hash + stride;
        }
        @Override public String toString() { return "PageKey{" + revision + "," + x + "," + z + "," + stride + "}"; }
    }

    /** Immutable worker ticket; identity is its token and creation epoch. */
    public static final class Request {
        private final HeightfieldResidency owner;
        public final long token;
        public final long epoch;
        public final PageKey key;
        private Request(HeightfieldResidency owner, long token, long epoch, PageKey key) {
            this.owner = owner; this.token = token; this.epoch = epoch; this.key = key;
        }
        public long getToken() { return token; }
        public long getEpoch() { return epoch; }
        public PageKey getKey() { return key; }
    }

    /** Immutable slot identity. */
    public static final class Handle {
        private final HeightfieldResidency owner;
        public final PageKey key;
        public final int slot;
        public final long generation;
        public final long epoch;
        private Handle(HeightfieldResidency owner, PageKey key, int slot, long generation, long epoch) {
            this.owner = owner; this.key = key; this.slot = slot; this.generation = generation; this.epoch = epoch;
        }
        public PageKey getKey() { return key; }
        public int getSlot() { return slot; }
        public long getGeneration() { return generation; }
        public long getEpoch() { return epoch; }
    }

    /** Immutable draw lease; each unique key has exactly one retained handle. */
    public static final class Frame {
        private static final int ACQUIRED = 0, SUBMITTED = 1, RELEASED = 2, POISONED = 3;
        private final HeightfieldResidency owner;
        private final long epoch;
        private final Map<PageKey, Handle> handles;
        private int state = ACQUIRED;
        private Completion completion;
        private Frame(HeightfieldResidency owner, long epoch, Map<PageKey, Handle> handles) {
            this.owner = owner; this.epoch = epoch;
            this.handles = Collections.unmodifiableMap(new LinkedHashMap<>(handles));
        }
        public Map<PageKey, Handle> getHandles() { return handles; }
        public Handle get(PageKey key) { return handles.get(key); }
    }

    /** Immutable observational counters. Resident payload storage is fixed by slot capacity. */
    public static final class Stats {
        public final int requests;
        public final long queuedBytes;
        public final int preparedPages;
        public final long preparedBytes;
        public final int readySlots;
        public final int retiredSlots;
        public final int frames;
        public final int slotCapacity;
        public final int residentPayloadSlots;
        public final long residentPayloadBytes;
        private Stats(int requests, long queuedBytes, int preparedPages, long preparedBytes,
                int readySlots, int retiredSlots, int frames, int slotCapacity,
                int residentPayloadSlots, long residentPayloadBytes) {
            this.requests = requests; this.queuedBytes = queuedBytes;
            this.preparedPages = preparedPages; this.preparedBytes = preparedBytes;
            this.readySlots = readySlots; this.retiredSlots = retiredSlots;
            this.frames = frames; this.slotCapacity = slotCapacity;
            this.residentPayloadSlots = residentPayloadSlots;
            this.residentPayloadBytes = residentPayloadBytes;
        }
        public int getRequests() { return requests; }
        public long getQueuedBytes() { return queuedBytes; }
        public int getPreparedPages() { return preparedPages; }
        public long getPreparedBytes() { return preparedBytes; }
        public int getReadySlots() { return readySlots; }
        public int getRetiredSlots() { return retiredSlots; }
        public int getFrames() { return frames; }
        public int getSlotCapacity() { return slotCapacity; }
        public int getResidentPayloadSlots() { return residentPayloadSlots; }
        public long getResidentPayloadBytes() { return residentPayloadBytes; }
    }

    @FunctionalInterface public interface Uploader { void upload(int slot, HeightfieldPage page); }
    public interface Completion extends AutoCloseable {
        boolean isComplete();
        @Override void close();
    }

    private static final class Prepared {
        final PageKey key;
        final HeightfieldPage page;
        final long token;
        Slot reservedSlot;
        Prepared(PageKey key, HeightfieldPage page, long token) { this.key = key; this.page = page; this.token = token; }
    }

    private static final class Slot {
        final int index;
        long generation;
        PageKey key;
        HeightfieldPage page;
        Handle handle;
        int references;
        boolean retired;
        Prepared reserved;
        Slot(int index) { this.index = index; }
    }
}
