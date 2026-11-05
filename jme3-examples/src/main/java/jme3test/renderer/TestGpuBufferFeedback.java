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
package jme3test.renderer;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetKey;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.MemoryBarrier;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.opengl.GLFence;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.shader.bufferobject.BufferObject.AccessHint;
import com.jme3.shader.bufferobject.BufferObject.NatureHint;
import com.jme3.system.AppSettings;
import com.jme3.util.BufferUtils;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Finite compute-write, fence and GPU-buffer readback example. */
public final class TestGpuBufferFeedback extends SimpleApplication {
    private static final int SLOT_COUNT = 3;
    private static final int WORDS = 64;
    private static final int PAYLOAD_BYTES = WORDS * Integer.BYTES;
    private static final int TOTAL_SUBMISSIONS = 12;
    private static final int RETAIN_FRAMES = 4;
    private static final int MAX_FRAMES = 120;
    private static final int SLICE_OFFSET = 28;
    private static final int SLICE_BYTES = 52;
    private static final String COMPUTE_PATH = "Common/MatDefs/Misc/GpuBufferFeedback.comp";

    private final CountDownLatch finished = new CountDownLatch(1);
    private final List<Slot> slots = new ArrayList<>();
    private volatile Throwable failure;
    private Renderer renderer;
    private Shader compute;
    private int frame;
    private int nextSequence = 1;
    private int completed;
    private int fencePolls;
    private int pendingObservations;
    private int retainedButUnreadFrames;
    private int readbacks;

    public static void main(String[] args) throws Exception {
        TestGpuBufferFeedback app = new TestGpuBufferFeedback();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length > 0 ? args[0] : AppSettings.LWJGL_OPENGL45);
        settings.setResolution(640, 360);
        settings.setAudioRenderer(null);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (!app.finished.await(45, TimeUnit.SECONDS)) {
            app.stop(false);
            throw new IllegalStateException("GPU buffer feedback example timed out", app.failure);
        }
        app.stop(true);
        if (app.failure != null) {
            throw new IllegalStateException("GPU buffer feedback example failed", app.failure);
        }
    }

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        try {
            initialize(renderer = renderManager.getRenderer());
        } catch (Throwable error) {
            fail(error);
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (failure != null || finished.getCount() == 0) return;
        try {
            frame++;
            serviceSlots();
            issueOneIfAvailable();
            if (completed == TOTAL_SUBMISSIONS && pendingCount() == 0) {
                System.out.println("GPU_BUFFER_FEEDBACK_PASS submissions=" + completed
                        + " readbacks=" + readbacks + " fencePolls=" + fencePolls
                        + " pendingPolls=" + pendingObservations
                        + " retainedButUnreadFrames=" + retainedButUnreadFrames + " frames=" + frame);
                finished.countDown();
            } else if (frame >= MAX_FRAMES) {
                throw new IllegalStateException("bounded run did not drain: completed=" + completed
                        + " pending=" + pendingCount() + " submitted=" + (nextSequence - 1));
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        fail(error == null ? new IllegalStateException(message) : error);
    }

    private void initialize(Renderer r) throws Exception {
        if (!r.getCaps().contains(Caps.ComputeShader)) {
            throw new UnsupportedOperationException("fixed compute storage support is required");
        }
        Map<Limits, Integer> limits = r.getLimits();
        if (limits.get(Limits.ComputeStorageBufferBindings) < 1
                || limits.get(Limits.ComputeStorageBufferBlockSize) < PAYLOAD_BYTES
                || limits.get(Limits.ComputeWorkGroupSizeX) < WORDS
                || limits.get(Limits.ComputeWorkGroupInvocations) < WORDS
                || limits.get(Limits.ComputeWorkGroupCountX) < 1) {
            throw new UnsupportedOperationException("renderer limits are below the example's fixed workload");
        }
        compute = new Shader();
        AssetInfo asset = assetManager.locateAsset(new AssetKey<>(COMPUTE_PATH));
        if (asset == null) throw new IllegalStateException("missing compute asset: " + COMPUTE_PATH);
        String source;
        try (InputStream input = asset.openStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[2048];
            int count;
            while ((count = input.read(block)) >= 0) bytes.write(block, 0, count);
            source = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        boolean es = r.getCaps().contains(Caps.OpenGLES31);
        compute.addSource(Shader.ShaderType.Compute, "GpuBufferFeedback.comp", source, "",
                es ? "GLSL310" : "GLSL430");

        for (int i = 0; i < SLOT_COUNT; i++) {
            BufferObject buffer = new BufferObject();
            ByteBuffer initial = BufferUtils.createByteBuffer(PAYLOAD_BYTES).order(ByteOrder.nativeOrder());
            buffer.setData(initial);
            buffer.setAccessHint(AccessHint.Dynamic);
            buffer.setNatureHint(NatureHint.Copy);
            buffer.setUpdateNeeded();
            slots.add(new Slot(i, buffer));
        }

        Material unshaded = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        unshaded.setColor("Color", new ColorRGBA(0.15f, 0.7f, 0.85f, 1f));
        Geometry visibleControl = new Geometry("post-compute ordinary draw control", new Box(1, 1, 1));
        visibleControl.setMaterial(unshaded);
        rootNode.attachChild(visibleControl);
        viewPort.setBackgroundColor(new ColorRGBA(0.04f, 0.06f, 0.1f, 1f));
    }

    private void serviceSlots() {
        for (Slot slot : slots) {
            if (slot.fence == null) continue;
            if (slot.signaledFrame < 0) {
                fencePolls++;
                if (renderer.isFenceSignaled(slot.fence)) {
                    slot.signaledFrame = frame;
                } else {
                    pendingObservations++;
                    continue;
                }
            }
            if (frame - slot.signaledFrame >= RETAIN_FRAMES) {
                verifyReadback(slot);
                renderer.deleteFence(slot.fence);
                slot.fence = null;
                slot.signaledFrame = -1;
                completed++;
            } else {
                retainedButUnreadFrames++;
            }
        }
    }

    private void issueOneIfAvailable() {
        if (nextSequence > TOTAL_SUBMISSIONS) return;
        Slot free = null;
        for (Slot slot : slots) {
            if (slot.fence == null) {
                free = slot;
                break;
            }
        }
        if (free == null) return;

        int sequence = nextSequence++;
        renderer.setFixedShaderStorageBufferObject(0, free.buffer);
        compute.getUniform("m_Sequence").setValue(VarType.Int, sequence);
        compute.getUniform("m_Slot").setValue(VarType.Int, free.index);
        renderer.dispatchCompute(compute, 1, 1, 1);
        renderer.memoryBarrier(EnumSet.of(MemoryBarrier.BufferUpdate));
        free.fence = renderer.createFence();
        free.sequence = sequence;
        free.signaledFrame = -1;
    }

    private void verifyReadback(Slot slot) {
        ByteBuffer expected = expectedPayload(slot.index, slot.sequence);
        ByteBuffer whole = BufferUtils.createByteBuffer(PAYLOAD_BYTES).order(ByteOrder.nativeOrder());
        renderer.readBufferObject(slot.buffer, 0, whole);
        for (int i = 0; i < PAYLOAD_BYTES; i++) {
            if (whole.get(i) != expected.get(i)) {
                throw new IllegalStateException("whole payload mismatch slot=" + slot.index
                        + " sequence=" + slot.sequence + " byte=" + i
                        + " expected=" + (expected.get(i) & 0xff) + " actual=" + (whole.get(i) & 0xff));
            }
        }

        ByteBuffer window = BufferUtils.createByteBuffer(SLICE_BYTES + 16).order(ByteOrder.nativeOrder());
        while (window.hasRemaining()) window.put((byte) 0x5a);
        int position = 7;
        int limit = position + SLICE_BYTES;
        window.position(position).limit(limit);
        renderer.readBufferObject(slot.buffer, SLICE_OFFSET, window);
        if (window.position() != position || window.limit() != limit) {
            throw new IllegalStateException("buffer readback changed destination position or limit");
        }
        ByteBuffer observed = window.duplicate();
        observed.clear();
        for (int i = 0; i < observed.capacity(); i++) {
            byte value = observed.get(i);
            if (i >= position && i < limit) {
                int source = SLICE_OFFSET + i - position;
                if (value != expected.get(source)) {
                    throw new IllegalStateException("slice mismatch slot=" + slot.index + " sequence="
                            + slot.sequence + " sourceByte=" + source);
                }
            } else if (value != (byte) 0x5a) {
                throw new IllegalStateException("readback modified destination guard byte " + i);
            }
        }
        readbacks++;
    }

    private static ByteBuffer expectedPayload(int slot, int sequence) {
        ByteBuffer bytes = BufferUtils.createByteBuffer(PAYLOAD_BYTES).order(ByteOrder.nativeOrder());
        int seed = sequence * 65537 ^ slot * 0x01020304;
        for (int i = 0; i < WORDS; i++) bytes.putInt(seed ^ (i * 0x9e3779b9));
        bytes.flip();
        return bytes;
    }

    private int pendingCount() {
        int count = 0;
        for (Slot slot : slots) if (slot.fence != null) count++;
        return count;
    }

    private void fail(Throwable error) {
        failure = error;
        error.printStackTrace();
        finished.countDown();
    }

    @Override
    public void destroy() {
        if (renderer != null) {
            for (Slot slot : slots) {
                if (slot.fence != null) renderer.deleteFence(slot.fence);
                renderer.deleteBuffer(slot.buffer);
            }
            if (compute != null) renderer.deleteShader(compute);
        }
        super.destroy();
    }

    private static final class Slot {
        final int index;
        final BufferObject buffer;
        GLFence fence;
        int sequence;
        int signaledFrame = -1;

        Slot(int index, BufferObject buffer) {
            this.index = index;
            this.buffer = buffer;
        }
    }
}
