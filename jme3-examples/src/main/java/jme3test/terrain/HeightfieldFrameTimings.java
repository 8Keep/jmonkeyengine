/*
 * Copyright (c) 2026 jMonkeyEngine
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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.post.SceneProcessor;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.texture.FrameBuffer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Finite, non-blocking frame timing capture for the public heightfield demo. */
final class HeightfieldFrameTimings implements SceneProcessor {

    private static final int MAX_SAMPLES = 4096;
    private static final int GPU_QUERY_COUNT = 8;

    private final Renderer renderer;
    private final int warmupFrames;
    private final List<Sample> samples = new ArrayList<>(MAX_SAMPLES);
    private final List<Query> queries = new ArrayList<>(GPU_QUERY_COUNT);
    private boolean initialized;
    private boolean sampling = true;
    private boolean gpuAvailable;
    private boolean gpuValidationAvailable;
    private boolean gpuDisabled;
    private String gpuUnavailableReason;
    private int framesSeen;
    private int warmupDiscarded;
    private int overflowFrames;
    private int nextQuery;
    private long previousFrameStart = -1;
    private Sample current;
    private Query currentQuery;

    HeightfieldFrameTimings(Renderer renderer, int warmupFrames) {
        if (renderer == null) {
            throw new IllegalArgumentException("renderer must not be null");
        }
        if (warmupFrames < 0) {
            throw new IllegalArgumentException("warmupFrames must be non-negative");
        }
        this.renderer = renderer;
        this.warmupFrames = warmupFrames;
        if (!renderer.getCaps().contains(Caps.GpuTimerQuery)) {
            gpuDisabled = true;
            gpuUnavailableReason = "renderer does not advertise Caps.GpuTimerQuery";
            return;
        }
        try {
            int[] ids = renderer.generateProfilingTasks(GPU_QUERY_COUNT);
            if (ids != null && ids.length >= GPU_QUERY_COUNT) {
                for (int i = 0; i < GPU_QUERY_COUNT; i++) {
                    queries.add(new Query(ids[i]));
                }
                gpuAvailable = true;
            } else {
                gpuDisabled = true;
                gpuUnavailableReason = "renderer returned fewer than " + GPU_QUERY_COUNT + " GPU timer query ids";
            }
        } catch (RuntimeException ex) {
            // CPU timings remain useful on renderers without timer queries.
            gpuDisabled = true;
            gpuUnavailableReason = "GPU timer query allocation failed: " + exceptionReason(ex);
        }
    }

    /** Call immediately before selecting/updating the scene for this frame. */
    void beginFrame(String phase) {
        if (!sampling || current != null) {
            return;
        }
        pollGpuResults();
        long now = System.nanoTime();
        long period = previousFrameStart < 0 ? -1 : now - previousFrameStart;
        previousFrameStart = now;
        framesSeen++;
        if (framesSeen <= warmupFrames) {
            warmupDiscarded++;
            return;
        }
        if (samples.size() >= MAX_SAMPLES) {
            overflowFrames++;
            sampling = false;
            return;
        }

        current = new Sample(samples.size(), phase == null ? "" : phase, now, period);
        currentQuery = acquireQuery();
        if (currentQuery != null) {
            try {
                renderer.startProfiling(currentQuery.id);
                currentQuery.sample = current;
                currentQuery.invalid = false;
            } catch (RuntimeException ex) {
                disableGpu("GPU timer query start failed: " + exceptionReason(ex));
                currentQuery = null;
            }
        }
    }

    /** Ends CPU/GPU intervals after the viewport has flushed its render queue. */
    @Override
    public void postFrame(FrameBuffer out) {
        if (current == null) {
            return;
        }
        current.cpuNanos = System.nanoTime() - current.startNanos;
        if (currentQuery != null) {
            try {
                renderer.stopProfiling();
            } catch (RuntimeException ex) {
                disableGpu("GPU timer query stop failed: " + exceptionReason(ex));
            }
        }
        samples.add(current);
        current = null;
        currentQuery = null;
        pollGpuResults();
        if (samples.size() >= MAX_SAMPLES) {
            sampling = false;
        }
    }

    /** Stop after the current frame. Pending timer queries are drained by polling. */
    void stopSampling() {
        sampling = false;
    }

    /** Polls query availability without waiting for the GPU. */
    boolean isDrained() {
        pollGpuResults();
        if (current != null) {
            return false;
        }
        for (Query query : queries) {
            if (query.sample != null) {
                return false;
            }
        }
        return true;
    }

    /** Writes the bounded frame table and a scope/percentile summary. */
    void write(Path folder) throws IOException {
        if (folder == null) {
            throw new IllegalArgumentException("folder must not be null");
        }
        Files.createDirectories(folder);
        Path csv = folder.resolve("heightfield-frame-timings.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("frame,phase,cpu_ms,period_ms,gpu_ms\n");
            for (Sample sample : samples) {
                writer.write(Integer.toString(sample.frame));
                writer.write(',');
                writer.write(csvEscape(sample.phase));
                writer.write(',');
                writer.write(milliseconds(sample.cpuNanos));
                writer.write(',');
                writer.write(milliseconds(sample.periodNanos));
                writer.write(',');
                writer.write(milliseconds(sample.gpuNanos));
                writer.write('\n');
            }
        }

        List<Long> cpu = new ArrayList<>();
        List<Long> period = new ArrayList<>();
        List<Long> gpu = new ArrayList<>();
        int gpuMissing = 0;
        int gpuDisjoint = 0;
        for (Sample sample : samples) {
            if (sample.cpuNanos >= 0) cpu.add(sample.cpuNanos);
            if (sample.periodNanos >= 0) period.add(sample.periodNanos);
            if (sample.gpuNanos >= 0) gpu.add(sample.gpuNanos);
            else gpuMissing++;
            if (sample.disjoint) gpuDisjoint++;
        }
        Path summary = folder.resolve("heightfield-frame-timings-summary.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(summary, StandardCharsets.UTF_8)) {
            writer.write("Finite public terrain demo frame timings\n");
            writer.write("CPU scope: wall time from the caller's beginFrame before scene selection through this viewport's postFrame callback after its scene processors; includes CPU selection and intervening frame work.\n");
            writer.write("GPU scope: renderer timer query opened at beginFrame and closed at this viewport's postFrame; measures GPU work between those query commands, including shadow/main/sky/post work submitted in that interval.\n");
            writer.write("GPU limits: elapsed query intervals can include submission-induced idle gaps; they do not isolate GPU busy time and must not be added to CPU intervals. Window presentation, display synchronization and GUI are outside the intended scope; this small static page is not a regional performance benchmark.\n");
            writer.write("Warmup frames discarded: " + warmupDiscarded + " (configured " + warmupFrames + ")\n");
            writer.write("Recorded frames: " + samples.size() + "\n");
            writer.write("Frames beyond bounded capacity: " + overflowFrames + "\n");
            writer.write("GPU timer queries: " + (gpuAvailable && !gpuDisabled ? "available" : "unavailable") + "\n");
            if (gpuUnavailableReason != null) {
                writer.write("GPU unavailable reason: " + gpuUnavailableReason + "\n");
            }
            writer.write("GPU disjoint validation: " + (gpuValidationAvailable ? "available" : "unavailable") + "\n");
            writer.write("GPU samples: " + gpu.size() + "; missing/unavailable: " + gpuMissing + "; disjoint: " + gpuDisjoint + "\n");
            writeStats(writer, "CPU ms", cpu);
            writeStats(writer, "Frame period ms", period);
            writeStats(writer, "GPU ms", gpu);
        }
    }

    private Query acquireQuery() {
        if (!gpuAvailable || gpuDisabled || queries.isEmpty()) {
            return null;
        }
        for (int i = 0; i < queries.size(); i++) {
            Query query = queries.get((nextQuery + i) % queries.size());
            if (query.sample == null) {
                nextQuery = (queries.indexOf(query) + 1) % queries.size();
                return query;
            }
        }
        return null;
    }

    private void pollGpuResults() {
        if (!gpuAvailable || gpuDisabled) {
            return;
        }
        List<Query> available = new ArrayList<>();
        try {
            for (Query query : queries) {
                if (query.sample != null && renderer.isTaskResultAvailable(query.id)) {
                    available.add(query);
                }
            }
        } catch (RuntimeException ex) {
            disableGpu("GPU timer query availability check failed: " + exceptionReason(ex));
            return;
        }

        List<Long> values = new ArrayList<>(available.size());
        try {
            for (Query query : available) {
                values.add(renderer.getProfilingTime(query.id));
            }
        } catch (RuntimeException ex) {
            disableGpu("GPU timer query read failed: " + exceptionReason(ex));
            return;
        }

        boolean disjoint = false;
        try {
            disjoint = renderer.isGpuTimingDisjoint();
            gpuValidationAvailable = true;
        } catch (RuntimeException ex) {
            disableGpu("GPU disjoint validation unavailable: " + exceptionReason(ex));
            return;
        }

        if (disjoint) {
            for (Query query : queries) {
                if (query.sample != null) {
                    query.invalid = true;
                    query.sample.disjoint = true;
                }
            }
        }
        for (int i = 0; i < available.size(); i++) {
            Query query = available.get(i);
            if (!query.invalid && !disjoint && query.sample != null) {
                query.sample.gpuNanos = values.get(i);
            }
            query.sample = null;
            query.invalid = false;
        }
    }

    private void disableGpu(String reason) {
        gpuDisabled = true;
        gpuAvailable = false;
        gpuValidationAvailable = false;
        if (gpuUnavailableReason == null) {
            gpuUnavailableReason = reason;
        }
        discardPendingGpu();
        for (Sample sample : samples) {
            sample.gpuNanos = -1;
        }
    }

    private void discardPendingGpu() {
        for (Query query : queries) {
            query.sample = null;
            query.invalid = false;
        }
    }

    private static void writeStats(BufferedWriter writer, String label, List<Long> values) throws IOException {
        writer.write(label + " p50/p95/p99: ");
        if (values.isEmpty()) {
            writer.write("n/a (0 samples)\n");
            return;
        }
        Collections.sort(values);
        writer.write(percentile(values, 0.50) + "/" + percentile(values, 0.95) + "/" + percentile(values, 0.99));
        writer.write(" (" + values.size() + " samples)\n");
    }

    private static String percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return milliseconds(sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))));
    }

    private static String milliseconds(long nanos) {
        return nanos < 0 ? "" : String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String csvEscape(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String exceptionReason(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isEmpty() ? ex.getClass().getSimpleName() : message;
    }

    @Override
    public void initialize(RenderManager rm, ViewPort vp) {
        initialized = true;
    }

    @Override
    public void reshape(ViewPort vp, int w, int h) {
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void preFrame(float tpf) {
    }

    @Override
    public void postQueue(RenderQueue rq) {
    }

    @Override
    public void cleanup() {
        sampling = false;
        if (initialized && current != null) {
            current.cpuNanos = System.nanoTime() - current.startNanos;
            if (currentQuery != null) {
                try {
                    renderer.stopProfiling();
                } catch (RuntimeException ex) {
                    disableGpu("GPU timer query stop failed during cleanup: " + exceptionReason(ex));
                }
            }
            samples.add(current);
            current = null;
            currentQuery = null;
            pollGpuResults();
        }
        initialized = false;
    }

    @Override
    public void setProfiler(com.jme3.profile.AppProfiler profiler) {
    }

    private static final class Sample {
        final int frame;
        final String phase;
        final long startNanos;
        final long periodNanos;
        long cpuNanos = -1;
        long gpuNanos = -1;
        boolean disjoint;

        Sample(int frame, String phase, long startNanos, long periodNanos) {
            this.frame = frame;
            this.phase = phase;
            this.startNanos = startNanos;
            this.periodNanos = periodNanos;
        }
    }

    private static final class Query {
        final int id;
        Sample sample;
        boolean invalid;

        Query(int id) {
            this.id = id;
        }
    }
}
