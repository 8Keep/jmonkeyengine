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
 * Bounded owner-thread residency for immutable terrain surface payloads.
 * Physical atlas sizing and padded layer accounting belong to the consumer.
 * Reset and close require that consumer to have discarded all physical slot
 * storage; completion fences retain retired slots until polling confirms use
 * has ended.
 */
public final class HeightfieldSurfaceResidency implements AutoCloseable {
    private final int resolution;
    private final int mipCount;
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
    private boolean failedClosed;
    private boolean externalCallback;

    public HeightfieldSurfaceResidency(int resolution, int mipCount, Limits limits) {
        if (resolution < 8 || resolution > 64 || (resolution & (resolution - 1)) != 0) {
            throw new IllegalArgumentException("resolution must be a power of two from 8 through 64");
        }
        if (mipCount < 1 || mipCount > 4 || (resolution >> (mipCount - 1)) < 1) {
            throw new IllegalArgumentException("mip count must be between one and four and fit the resolution");
        }
        this.limits = Objects.requireNonNull(limits, "limits");
        if (limits.slots == 0) {
            throw new IllegalArgumentException("at least one physical slot is required");
        }
        this.resolution = resolution;
        this.mipCount = mipCount;
        long smallestCpuBytes = cpuArrayBytes();
        long smallestEncodedBytes = encodedBytes();
        if (limits.queuedReservedBytes < smallestCpuBytes || limits.preparedBytes < smallestCpuBytes
                || limits.uploadEncodedBytesPerCall < smallestEncodedBytes) {
            throw new IllegalArgumentException("byte limits must fit at least one stride-one page");
        }
        this.ownerThread = Thread.currentThread();
        this.slots = new Slot[limits.slots];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot(i);
        }
    }

    /** Reserve CPU preparation capacity for a key; null signals bounded pressure or duplication. */
    public Request request(PageKey key) {
        checkUsable();
        Objects.requireNonNull(key, "key");
        validateKey(key);
        long bytes = cpuArrayBytes();
        if (ready.containsKey(key) || requests.containsKey(key) || prepared.containsKey(key)
                || requests.size() >= limits.activeRequests || nextToken == 0
                || queuedBytes > limits.queuedReservedBytes - bytes) {
            return null;
        }
        long token = nextToken;
        nextToken = token == Long.MAX_VALUE ? 0 : token + 1;
        Request request = new Request(this, token, epoch, key, bytes);
        requests.put(key, request);
        queuedBytes += bytes;
        return request;
    }

    /** Complete on the owner thread. Capacity pressure leaves this request retryable. */
    public boolean complete(Request request, HeightfieldSurfacePage page) {
        checkUsable();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(page, "page");
        if (request.owner != this || request.epoch != epoch || requests.get(request.key) != request) {
            return false;
        }
        if (!matches(request.key, page)) {
            throw new IllegalArgumentException("surface page metadata does not match its requested key and pool configuration");
        }
        long bytes = page.getCpuArrayByteCount();
        if (bytes != request.cpuBytes) {
            throw new IllegalArgumentException("surface page byte count does not match its reservation");
        }
        if (preparedBytes > limits.preparedBytes - bytes) {
            return false;
        }
        requests.remove(request.key);
        queuedBytes -= request.cpuBytes;
        Prepared value = new Prepared(request.key, page, request.token, bytes, page.getEncodedByteCount());
        prepared.put(request.key, value);
        preparedBytes += bytes;
        return true;
    }

    public boolean cancel(Request request) {
        checkUsable();
        Objects.requireNonNull(request, "request");
        if (request.owner != this) {
            return false;
        }
        if (request.epoch == epoch && requests.get(request.key) == request) {
            requests.remove(request.key);
            queuedBytes -= request.cpuBytes;
            return true;
        }
        Prepared value = prepared.get(request.key);
        if (request.epoch == epoch && value != null && value.token == request.token) {
            dropPrepared(value);
            return true;
        }
        return false;
    }

    public boolean isPending(Request request) {
        checkUsable();
        return request != null && request.owner == this && request.epoch == epoch
                && requests.get(request.key) == request;
    }

    public boolean cancelPrepared(PageKey key) {
        checkUsable();
        Prepared value = prepared.get(Objects.requireNonNull(key, "key"));
        if (value == null) {
            return false;
        }
        dropPrepared(value);
        return true;
    }

    /**
     * Upload FIFO payloads. Return true only after writing albedo, normal
     * moments and semantic IDs for every logical mip. False or a thrown
     * callback keeps its page and reserved slot for a complete retry and never
     * publishes it.
     */
    public int uploadPrepared(Uploader uploader) {
        checkUsable();
        Objects.requireNonNull(uploader, "uploader");
        int attempts = 0;
        long encodedBytes = 0;
        for (Prepared value : prepared.values().toArray(new Prepared[0])) {
            if (attempts >= limits.uploadsPerCall
                    || encodedBytes > limits.uploadEncodedBytesPerCall - value.encodedBytes) {
                break;
            }
            Slot slot = value.reservedSlot;
            if (slot == null) {
                slot = findFreeSlot();
                if (slot == null) {
                    break;
                }
                slot.reserved = value;
                value.reservedSlot = slot;
            }
            if (slot.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("slot generation exhausted");
            }
            attempts++;
            encodedBytes += value.encodedBytes;
            final boolean complete;
            externalCallback = true;
            try {
                complete = uploader.upload(slot.index, value.page);
            } finally {
                externalCallback = false;
            }
            if (!complete) {
                continue;
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

    public Handle ready(PageKey key) {
        checkUsable();
        Slot slot = ready.get(Objects.requireNonNull(key, "key"));
        return slot == null ? null : slot.handle;
    }

    public HeightfieldSurfacePage get(Handle handle) {
        checkUsable();
        Slot slot = retainedSlot(handle);
        return slot == null ? null : slot.page;
    }

    public boolean isCurrent(Handle handle) {
        checkUsable();
        Slot slot = retainedSlot(handle);
        return slot != null && !slot.retired && ready.get(handle.key) == slot;
    }

    public boolean isRetained(Handle handle) {
        checkUsable();
        return retainedSlot(handle) != null;
    }

    public boolean pin(PageKey key) {
        checkUsable();
        if (!ready.containsKey(Objects.requireNonNull(key, "key"))) {
            return false;
        }
        int count = pins.getOrDefault(key, 0);
        if (count == Integer.MAX_VALUE) {
            throw new IllegalStateException("pin count exhausted");
        }
        pins.put(key, count + 1);
        return true;
    }

    public boolean unpin(PageKey key) {
        checkUsable();
        Objects.requireNonNull(key, "key");
        Integer count = pins.get(key);
        if (count == null) {
            return false;
        }
        if (count == 1) {
            pins.remove(key);
        } else {
            pins.put(key, count - 1);
        }
        return true;
    }

    public boolean evict(PageKey key) {
        checkUsable();
        Objects.requireNonNull(key, "key");
        Slot slot = ready.get(key);
        if (slot == null || pins.containsKey(key)
                || (slot.references > 0 && retiredCount >= limits.retiredSlots)) {
            return false;
        }
        ready.remove(key);
        if (slot.references > 0) {
            slot.retired = true;
            retiredCount++;
        } else {
            clearSlot(slot);
        }
        return true;
    }

    /** Atomically acquire one lease for each unique key, or acquire none. */
    public Frame acquire(Collection<PageKey> keys) {
        checkUsable();
        Objects.requireNonNull(keys, "keys");
        if (frames.size() >= limits.inFlightFrames) {
            return null;
        }
        LinkedHashSet<PageKey> unique = new LinkedHashSet<>();
        for (PageKey key : keys) {
            unique.add(Objects.requireNonNull(key, "key"));
        }
        LinkedHashMap<PageKey, Handle> captured = new LinkedHashMap<>();
        for (PageKey key : unique) {
            Slot slot = ready.get(key);
            if (slot == null || slot.references == Integer.MAX_VALUE) {
                return null;
            }
            captured.put(key, slot.handle);
        }
        for (Handle handle : captured.values()) {
            retainedSlot(handle).references++;
        }
        Frame frame = new Frame(this, epoch, captured);
        frames.add(frame);
        return frame;
    }

    public boolean submit(Frame frame, Completion completion) {
        checkUsable();
        Objects.requireNonNull(completion, "completion");
        if (!ownsLiveFrame(frame) || frame.state != Frame.ACQUIRED) {
            return false;
        }
        frame.completion = completion;
        frame.state = Frame.SUBMITTED;
        return true;
    }

    public boolean abort(Frame frame) {
        checkUsable();
        if (!ownsLiveFrame(frame) || frame.state != Frame.ACQUIRED) {
            return false;
        }
        releaseFrame(frame);
        return true;
    }

    /** Poll submitted fences. Any fence failure retains all uncertain leases. */
    public int poll() {
        checkUsable();
        int released = 0;
        for (Frame frame : new ArrayList<>(frames)) {
            if (frame.state != Frame.SUBMITTED) {
                continue;
            }
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
            if (!complete) {
                continue;
            }
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

    /** The consumer must discard physical storage before calling reset. */
    public void reset() {
        checkUsable();
        ensureEpochAvailable();
        List<Completion> completions = completionsToClose();
        epoch++;
        clearState();
        closeCompletions(completions);
    }

    /** The consumer must discard physical storage before closing this owner. */
    @Override
    public void close() {
        checkOwner();
        if (closed) {
            return;
        }
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
        for (Slot slot : slots) {
            if (slot.page != null || slot.reserved != null) {
                resident++;
            }
        }
        return new Stats(requests.size(), queuedBytes, prepared.size(), preparedBytes,
                ready.size(), retiredCount, frames.size(), slots.length, resident);
    }

    public int getResolution() {
        return resolution;
    }

    public int getMipCount() {
        return mipCount;
    }

    public Limits getLimits() {
        return limits;
    }

    private void validateKey(PageKey key) {
        if (key.baseStride < 1 || key.baseStride > 8 || (key.baseStride & (key.baseStride - 1)) != 0) {
            throw new IllegalArgumentException("base stride must be a power of two from 1 through 8");
        }
        if (((long) key.baseStride << (mipCount - 1)) > HeightfieldSurfacePage.MAX_FILTER_FOOTPRINT) {
            throw new IllegalArgumentException("configured mips exceed the canonical filter footprint limit");
        }
        long extent = Math.multiplyExact((long) resolution, key.baseStride);
        if (Math.floorMod(key.originX, extent) != 0 || Math.floorMod(key.originZ, extent) != 0) {
            throw new IllegalArgumentException("page origins must align to the canonical page extent");
        }
        long gutter = Math.multiplyExact((long) HeightfieldSurfacePage.GUTTER,
                (long) key.baseStride << (mipCount - 1));
        long positive = Math.addExact(Math.multiplyExact((long) resolution, key.baseStride), gutter) - 1;
        Math.addExact(key.originX, -gutter);
        Math.addExact(key.originZ, -gutter);
        Math.addExact(key.originX, positive);
        Math.addExact(key.originZ, positive);
    }

    private boolean matches(PageKey key, HeightfieldSurfacePage page) {
        return page.getRevision().equals(key.revision)
                && page.getOriginX() == key.originX && page.getOriginZ() == key.originZ
                && page.getBaseStride() == key.baseStride && page.getResolution() == resolution
                && page.getLevelCount() == mipCount;
    }

    private long cpuArrayBytes() {
        long bytes = 0;
        for (int mip = 0; mip < mipCount; mip++) {
            long side = (resolution >> mip) + 2L * HeightfieldSurfacePage.GUTTER;
            bytes = Math.addExact(bytes, Math.multiplyExact(side * side, HeightfieldSurfacePage.CPU_BYTES_PER_TEXEL));
        }
        return bytes;
    }

    private long encodedBytes() {
        long bytes = 0;
        for (int mip = 0; mip < mipCount; mip++) {
            long side = (resolution >> mip) + 2L * HeightfieldSurfacePage.GUTTER;
            bytes = Math.addExact(bytes, Math.multiplyExact(side * side, 8L + 16L + 1L));
        }
        return bytes;
    }

    private void dropPrepared(Prepared value) {
        prepared.remove(value.key);
        preparedBytes -= value.cpuBytes;
        if (value.reservedSlot != null) {
            value.reservedSlot.reserved = null;
            value.reservedSlot = null;
        }
    }

    private Slot findFreeSlot() {
        for (Slot slot : slots) {
            if (slot.page == null && slot.reserved == null) {
                return slot;
            }
        }
        return null;
    }

    private Slot retainedSlot(Handle handle) {
        if (handle == null || handle.owner != this || handle.epoch != epoch
                || handle.slot < 0 || handle.slot >= slots.length) {
            return null;
        }
        Slot slot = slots[handle.slot];
        return slot.key != null && slot.generation == handle.generation
                && slot.key.equals(handle.key) && slot.page != null ? slot : null;
    }

    private boolean ownsLiveFrame(Frame frame) {
        return frame != null && frame.owner == this && frame.epoch == epoch && frames.contains(frame);
    }

    private void releaseFrame(Frame frame) {
        for (Handle handle : frame.handles.values()) {
            Slot slot = retainedSlot(handle);
            if (slot == null || slot.references <= 0) {
                throw new IllegalStateException("frame lease invariant failed");
            }
            slot.references--;
            if (slot.retired && slot.references == 0) {
                clearSlot(slot);
            }
        }
        frame.state = Frame.RELEASED;
        frames.remove(frame);
    }

    private void clearSlot(Slot slot) {
        if (slot.retired) {
            retiredCount--;
        }
        slot.key = null;
        slot.page = null;
        slot.handle = null;
        slot.retired = false;
        slot.references = 0;
        slot.reserved = null;
    }

    private void clearState() {
        requests.clear();
        prepared.clear();
        ready.clear();
        pins.clear();
        queuedBytes = 0;
        preparedBytes = 0;
        for (Slot slot : slots) {
            clearSlot(slot);
        }
        retiredCount = 0;
        for (Frame frame : frames) {
            frame.state = Frame.RELEASED;
        }
        frames.clear();
    }

    private List<Completion> completionsToClose() {
        List<Completion> completions = new ArrayList<>();
        for (Frame frame : frames) {
            if (frame.completion != null) {
                completions.add(frame.completion);
            }
        }
        return completions;
    }

    private void closeCompletions(List<Completion> completions) {
        RuntimeException failure = null;
        for (Completion completion : completions) {
            try {
                externalCallback = true;
                completion.close();
            } catch (RuntimeException ex) {
                if (failure == null) {
                    failure = new IllegalStateException("one or more completion fences failed to close");
                }
                failure.addSuppressed(ex);
            } finally {
                externalCallback = false;
            }
        }
        if (failure != null) {
            failedClosed = true;
            throw failure;
        }
    }

    private void ensureEpochAvailable() {
        if (epoch == Long.MAX_VALUE) {
            throw new IllegalStateException("residency epoch exhausted");
        }
    }

    private void checkUsable() {
        checkOwner();
        if (closed) {
            throw new IllegalStateException("residency is closed");
        }
        if (failedClosed) {
            throw new IllegalStateException("residency is unusable after a completion fence failure");
        }
    }

    private void checkOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("residency must be accessed by its owner thread");
        }
        if (externalCallback) {
            throw new IllegalStateException("residency cannot be re-entered from an external callback");
        }
    }

    /** Independent CPU and upload bounds; physical atlas storage is external. */
    public static final class Limits {
        public final int slots;
        public final int activeRequests;
        public final long queuedReservedBytes;
        public final long preparedBytes;
        public final int uploadsPerCall;
        public final long uploadEncodedBytesPerCall;
        public final int retiredSlots;
        public final int inFlightFrames;

        public Limits(int slots, int activeRequests, long queuedReservedBytes, long preparedBytes,
                int uploadsPerCall, long uploadEncodedBytesPerCall, int retiredSlots, int inFlightFrames) {
            if (slots < 0 || activeRequests < 0 || queuedReservedBytes < 0 || preparedBytes < 0
                    || uploadsPerCall < 0 || uploadEncodedBytesPerCall < 0
                    || retiredSlots < 0 || inFlightFrames < 0) {
                throw new IllegalArgumentException("all residency limits must be nonnegative");
            }
            this.slots = slots;
            this.activeRequests = activeRequests;
            this.queuedReservedBytes = queuedReservedBytes;
            this.preparedBytes = preparedBytes;
            this.uploadsPerCall = uploadsPerCall;
            this.uploadEncodedBytesPerCall = uploadEncodedBytesPerCall;
            this.retiredSlots = retiredSlots;
            this.inFlightFrames = inFlightFrames;
        }
    }

    /** Immutable cache identity; height demand remains a separate pool/key. */
    public static final class PageKey {
        public final HeightfieldSurfacePage.Revision revision;
        public final long originX;
        public final long originZ;
        public final int baseStride;

        public PageKey(HeightfieldSurfacePage.Revision revision, long originX, long originZ, int baseStride) {
            this.revision = Objects.requireNonNull(revision, "revision");
            if (baseStride < 1 || baseStride > 8 || (baseStride & (baseStride - 1)) != 0) {
                throw new IllegalArgumentException("base stride must be a power of two from 1 through 8");
            }
            this.originX = originX;
            this.originZ = originZ;
            this.baseStride = baseStride;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PageKey)) {
                return false;
            }
            PageKey key = (PageKey) other;
            return revision.equals(key.revision) && originX == key.originX
                    && originZ == key.originZ && baseStride == key.baseStride;
        }

        @Override
        public int hashCode() {
            int result = revision.hashCode();
            result = 31 * result + Long.hashCode(originX);
            result = 31 * result + Long.hashCode(originZ);
            return 31 * result + baseStride;
        }
    }

    public static final class Request {
        private final HeightfieldSurfaceResidency owner;
        private final long token;
        private final long epoch;
        private final PageKey key;
        private final long cpuBytes;

        private Request(HeightfieldSurfaceResidency owner, long token, long epoch, PageKey key, long cpuBytes) {
            this.owner = owner;
            this.token = token;
            this.epoch = epoch;
            this.key = key;
            this.cpuBytes = cpuBytes;
        }

        public long getToken() { return token; }
        public long getEpoch() { return epoch; }
        public PageKey getKey() { return key; }
    }

    public static final class Handle {
        private final HeightfieldSurfaceResidency owner;
        private final PageKey key;
        private final int slot;
        private final long generation;
        private final long epoch;

        private Handle(HeightfieldSurfaceResidency owner, PageKey key, int slot, long generation, long epoch) {
            this.owner = owner;
            this.key = key;
            this.slot = slot;
            this.generation = generation;
            this.epoch = epoch;
        }

        public PageKey getKey() { return key; }
        public int getSlot() { return slot; }
        public long getGeneration() { return generation; }
        public long getEpoch() { return epoch; }
    }

    public static final class Frame {
        private static final int ACQUIRED = 0;
        private static final int SUBMITTED = 1;
        private static final int RELEASED = 2;
        private static final int POISONED = 3;
        private final HeightfieldSurfaceResidency owner;
        private final long epoch;
        private final Map<PageKey, Handle> handles;
        private int state = ACQUIRED;
        private Completion completion;

        private Frame(HeightfieldSurfaceResidency owner, long epoch, Map<PageKey, Handle> handles) {
            this.owner = owner;
            this.epoch = epoch;
            this.handles = Collections.unmodifiableMap(new LinkedHashMap<>(handles));
        }

        public Map<PageKey, Handle> getHandles() { return handles; }
        public Handle get(PageKey key) { return handles.get(key); }
    }

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

        private Stats(int requests, long queuedBytes, int preparedPages, long preparedBytes,
                int readySlots, int retiredSlots, int frames, int slotCapacity, int residentPayloadSlots) {
            this.requests = requests;
            this.queuedBytes = queuedBytes;
            this.preparedPages = preparedPages;
            this.preparedBytes = preparedBytes;
            this.readySlots = readySlots;
            this.retiredSlots = retiredSlots;
            this.frames = frames;
            this.slotCapacity = slotCapacity;
            this.residentPayloadSlots = residentPayloadSlots;
        }
    }

    @FunctionalInterface
    public interface Uploader {
        boolean upload(int slot, HeightfieldSurfacePage page);
    }

    public interface Completion extends AutoCloseable {
        boolean isComplete();
        @Override void close();
    }

    private static final class Prepared {
        final PageKey key;
        final HeightfieldSurfacePage page;
        final long token;
        final long cpuBytes;
        final long encodedBytes;
        Slot reservedSlot;

        Prepared(PageKey key, HeightfieldSurfacePage page, long token, long cpuBytes, long encodedBytes) {
            this.key = key;
            this.page = page;
            this.token = token;
            this.cpuBytes = cpuBytes;
            this.encodedBytes = encodedBytes;
        }
    }

    private static final class Slot {
        final int index;
        long generation;
        PageKey key;
        HeightfieldSurfacePage page;
        Handle handle;
        int references;
        boolean retired;
        Prepared reserved;

        Slot(int index) { this.index = index; }
    }
}
