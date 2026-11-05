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
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
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
import com.jme3.renderer.Camera;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.MemoryBarrier;
import com.jme3.renderer.Renderer;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.shader.bufferobject.BufferRegion;
import com.jme3.system.AppSettings;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.util.BufferUtils;
import com.jme3.util.NativeObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Finite managed compute-to-material UBO pixel probe. Arguments: renderer, output directory, optional instrumented. */
public final class TestManagedCompute extends SimpleApplication {
    private static final int WIDTH = 16;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Path output;
    private volatile Throwable failure;
    private int checks;
    private double maxError;

    private TestManagedCompute(Path output) { this.output = output; }

    public static void main(String[] args) throws Exception {
        TestManagedCompute app = new TestManagedCompute(Paths.get(
                args.length > 1 ? args[1] : "build/managed-compute"));
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length > 0 ? args[0] : AppSettings.LWJGL_OPENGL45);
        settings.setResolution(320, 240);
        settings.setAudioRenderer(null);
        settings.setGraphicsDebug(true);
        if (args.length > 2 && "instrumented".equals(args[2])) {
            settings.setGraphicsTiming(true);
            settings.setGraphicsTrace(true);
        }
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (!app.finished.await(45, TimeUnit.SECONDS)) {
            app.stop(false);
            throw new IllegalStateException("Managed compute probe timed out", app.failure);
        }
        app.stop(true);
        if (app.failure != null) throw new IllegalStateException("Managed compute probe failed", app.failure);
        System.out.println("MANAGED_COMPUTE_PASS checks=" + app.checks + " output=" + app.output.toAbsolutePath());
    }

    @Override public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            runProbe(renderManager.getRenderer());
        } catch (Throwable error) {
            fail(error);
        } finally {
            finished.countDown();
        }
    }

    @Override public void handleError(String message, Throwable error) {
        fail(error == null ? new IllegalStateException(message) : error);
    }

    private void fail(Throwable error) {
        failure = error;
        error.printStackTrace();
        finished.countDown();
    }

    private void runProbe(Renderer r) throws Exception {
        if (!r.getCaps().contains(Caps.ComputeShader) || !r.getCaps().contains(Caps.UniformBufferObject))
            throw new UnsupportedOperationException("Managed compute and UBO support required");
        boolean es = r.getCaps().contains(Caps.OpenGLES30);
        int maxX = r.getLimits().get(Limits.ComputeWorkGroupCountX);
        require(maxX >= 4 && r.getLimits().get(Limits.ComputeWorkGroupCountY) >= 1
                && r.getLimits().get(Limits.ComputeWorkGroupCountZ) >= 1, "dispatch count limits");
        require(r.getLimits().get(Limits.ComputeWorkGroupSizeX) >= 4
                && r.getLimits().get(Limits.ComputeWorkGroupSizeY) >= 1
                && r.getLimits().get(Limits.ComputeWorkGroupSizeZ) >= 1
                && r.getLimits().get(Limits.ComputeWorkGroupInvocations) >= 4, "local size limits");
        require(r.getLimits().get(Limits.ComputeStorageBufferBindings) >= 1
                && r.getLimits().get(Limits.ComputeStorageBufferBlockSize) >= WIDTH * 16, "storage limits");

        BufferObject payload = dataBuffer();
        BufferObject control = new BufferObject();
        control.setData(BufferUtils.createByteBuffer(16).order(ByteOrder.nativeOrder()));
        control.getData().asFloatBuffer().put(new float[]{1f, 0f, 0f, 0f});
        control.setUpdateNeeded();
        Shader compute = new Shader();
        String source = readComputeSource();
        compute.addSource(Shader.ShaderType.Compute, "ManagedCompute.comp", source, "", es ? "GLSL310" : "GLSL430");

        Material material = new Material(assetManager, "Shaders/ManagedCompute/ManagedCompute.j3md");
        material.setUniformBufferObject("Control", control);
        material.setUniformBufferObject("Payload", payload);
        Geometry geometry = new Geometry("managed-compute-probe", new Quad(2, 2));
        geometry.setMaterial(material);
        geometry.updateGeometricState();
        FrameBuffer target = floatTarget(WIDTH, 1);
        Material unlit = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        unlit.setColor("Color", new ColorRGBA(.2f, .4f, .6f, 1f));
        Geometry unlitGeometry = new Geometry("ordinary-unlit-control", new Quad(2, 2));
        unlitGeometry.setMaterial(unlit);
        unlitGeometry.setLocalTranslation(-1, -1, 0);
        unlitGeometry.updateGeometricState();
        Camera probeCamera = new Camera(WIDTH, 1);
        probeCamera.setParallelProjection(true);
        probeCamera.setFrustum(.1f, 10f, -1f, 1f, 1f, -1f);
        probeCamera.setLocation(new Vector3f(0, 0, 1));
        probeCamera.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        probeCamera.update();
        renderManager.setCamera(probeCamera, false);
        drawUnlitControl(r, target, unlitGeometry);
        FrameBuffer a = floatTarget(2, 2), b = floatTarget(2, 2);
        r.setMainFrameBufferOverride(a);
        r.setFrameBuffer(b);
        FrameBuffer actualB = r.getCurrentFrameBuffer();
        require(actualB == b, "physical framebuffer B bound");
        try {
            renderAndRead(r, target, geometry, expected(0), "initial");
        } finally {
            r.setFrameBuffer(actualB);
        }
        require(r.getCurrentFrameBuffer() == actualB, "restore exact physically bound framebuffer B");
        r.setMainFrameBufferOverride(null);

        // A normal material draw is deliberately already current before dispatch.
        r.setFrameBuffer(target);
        renderManager.renderGeometry(geometry);
        setPhase(compute, 1);
        r.setFixedShaderStorageBufferObject(0, payload);
        require(payload.getBinding() == 1, "fixed SSBO binding preserves material UBO binding metadata");
        r.dispatchCompute(compute, 4, 1, 1);
        r.memoryBarrier(EnumSet.of(MemoryBarrier.UniformBuffer, MemoryBarrier.ShaderStorage));
        renderAndRead(r, target, geometry, expected(1), "dispatch1");
        setPhase(compute, 2);
        r.dispatchCompute(compute, 4, 1, 1);
        r.memoryBarrier(EnumSet.of(MemoryBarrier.UniformBuffer, MemoryBarrier.ShaderStorage));
        renderAndRead(r, target, geometry, expected(2), "dispatch2");
        drawUnlitControl(r, target, unlitGeometry);
        renderAndRead(r, target, geometry, expected(2), "after-unlit-control");

        // A zero dispatch must preserve the previously computed values.
        setPhase(compute, 1);
        r.dispatchCompute(compute, 0, 1, 1);
        renderAndRead(r, target, geometry, expected(2), "zero-groups");
        r.dispatchCompute(compute, maxX, 0, 1);
        renderAndRead(r, target, geometry, expected(2), "zero-y-groups");

        Shader invalidGroupShader = compute;
        expect(IllegalArgumentException.class, () -> r.dispatchCompute(invalidGroupShader, -1, 1, 1));
        int maxGroupsY = r.getLimits().get(Limits.ComputeWorkGroupCountY);
        if (maxGroupsY < Integer.MAX_VALUE) {
            expect(IllegalArgumentException.class,
                    () -> r.dispatchCompute(invalidGroupShader, 1, maxGroupsY + 1, 1));
        }
        require(r.getCurrentFrameBuffer() == target, "invalid dispatch preserves current framebuffer");
        renderAndRead(r, target, geometry, expected(2), "invalid-groups");

        // Fixed storage binding must leave the BO's ordinary UBO binding point intact.
        require(payload.getBinding() == 1, "material UBO remains bound at point 1");
        r.setFixedShaderStorageBufferObject(0, payload);
        require(payload.getBinding() == 1, "fixed SSBO binding leaves payload UBO binding intact");

        // Capability failure is checked before dispatch and restored even on failure.
        Shader unsupported = new Shader();
        unsupported.addSource(Shader.ShaderType.Compute, "ManagedCompute.comp", source, "", es ? "GLSL310" : "GLSL430");
        try {
            r.getCaps().remove(Caps.ComputeShader);
            expect(UnsupportedOperationException.class, () -> r.dispatchCompute(unsupported, 1, 1, 1));
            require(unsupported.getId() == NativeObject.INVALID_ID, "unsupported dispatch rejects before shader compilation");
        } finally {
            r.getCaps().add(Caps.ComputeShader);
        }

        Shader mixed = new Shader();
        mixed.addSource(Shader.ShaderType.Compute, "ManagedCompute.comp", source, "", es ? "GLSL310" : "GLSL430");
        mixed.addSource(Shader.ShaderType.Vertex, "Mixed.vert", "in vec3 inPosition; void main(){gl_Position=vec4(inPosition,1.0);}", "", "GLSL150");
        expect(IllegalArgumentException.class, () -> r.dispatchCompute(mixed, 1, 1, 1));
        require(mixed.getId() == NativeObject.INVALID_ID, "mixed stages reject before shader compilation");

        writePreview(r, target, geometry);
        // Force native recreation and replay from the same CPU-initialized BO contents.
        r.deleteBuffer(payload); r.deleteShader(compute);
        require(payload.getId() == NativeObject.INVALID_ID && compute.getId() == NativeObject.INVALID_ID,
                "explicit resources retired");
        setPhase(compute, 1);
        r.setFixedShaderStorageBufferObject(0, payload);
        r.dispatchCompute(compute, 4, 1, 1);
        r.memoryBarrier(EnumSet.of(MemoryBarrier.UniformBuffer, MemoryBarrier.ShaderStorage));
        renderAndRead(r, target, geometry, expected(1), "recreated");
        Files.write(output.resolve("summary.txt"), Arrays.asList(
                "managed compute write -> BufferObject -> material UBO read",
                "checks=" + checks, "maximumFloatError=" + maxError,
                "renderer=" + r.getCaps(), "limits=" + r.getLimits()));
        System.out.println("MANAGED_COMPUTE_DRAW_PASS renderer=" + (es ? "GLES" : "GL")
                + " checks=" + checks + " maxError=" + maxError);
        r.setFrameBuffer(null);
    }

    private BufferObject dataBuffer() {
        BufferObject bo = new BufferObject();
        bo.setRegions(Arrays.asList(new BufferRegion(0, WIDTH * 16 - 1)));
        bo.getData().order(ByteOrder.nativeOrder()).asFloatBuffer().put(expected(0));
        bo.setUpdateNeeded();
        bo.setBinding(1);
        return bo;
    }

    private String readComputeSource() throws Exception {
        AssetInfo asset = assetManager.locateAsset(new AssetKey<>("Shaders/ManagedCompute/ManagedCompute.comp"));
        if (asset == null) throw new IllegalStateException("Missing managed compute shader asset");
        try (InputStream input = asset.openStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[4096];
            int count;
            while ((count = input.read(block)) >= 0) bytes.write(block, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void setPhase(Shader shader, int phase) {
        shader.getUniform("m_Phase").setValue(VarType.Int, phase);
    }

    private FrameBuffer floatTarget(int width, int height) {
        FrameBuffer fb = new FrameBuffer(width, height, 1);
        fb.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        return fb;
    }

    private void renderAndRead(Renderer r, FrameBuffer fb, Geometry g, float[] expected, String label) {
        r.setFrameBuffer(fb);
        r.setBackgroundColor(ColorRGBA.Black);
        r.clearBuffers(true, false, false);
        renderManager.renderGeometry(g);
        ByteBuffer bytes = BufferUtils.createByteBuffer(WIDTH * 16).order(ByteOrder.nativeOrder());
        r.readFrameBufferWithFormat(fb, bytes, Image.Format.RGBA32F);
        FloatBuffer pixels = bytes.asFloatBuffer();
        float[] actual = new float[pixels.remaining()];
        pixels.get(actual);
        for (int i = 0; i < expected.length; i++) {
            double error = Math.abs(actual[i] - expected[i]);
            maxError = Math.max(maxError, error);
            require(error < 0.0001f, label + " pixel[" + i + "] " + actual[i] + " != " + expected[i]);
        }
    }

    private void drawUnlitControl(Renderer r, FrameBuffer fb, Geometry geometry) {
        r.setFrameBuffer(fb);
        r.setBackgroundColor(ColorRGBA.Black);
        r.clearBuffers(true, false, false);
        renderManager.renderGeometry(geometry);
        ByteBuffer bytes = BufferUtils.createByteBuffer(WIDTH * 16).order(ByteOrder.nativeOrder());
        r.readFrameBufferWithFormat(fb, bytes, Image.Format.RGBA32F);
        FloatBuffer pixels = bytes.asFloatBuffer();
        for (int i = 0; i < WIDTH; i++) {
            require(Math.abs(pixels.get(i * 4) - .2f) < .01f, "unlit control red");
            require(Math.abs(pixels.get(i * 4 + 1) - .4f) < .01f, "unlit control green");
            require(Math.abs(pixels.get(i * 4 + 2) - .6f) < .01f, "unlit control blue");
        }
    }

    private float[] expected(int phase) {
        float[] values = new float[WIDTH * 4];
        for (int i = 0; i < WIDTH; i++) {
            if (phase == 0) {
                values[i * 4] = 0.05f * (i + 1); values[i * 4 + 1] = 0.1f;
                values[i * 4 + 2] = 0.2f; values[i * 4 + 3] = 1f;
            } else if (phase == 1) {
                values[i * 4] = i * 0.0625f; values[i * 4 + 1] = .25f;
                values[i * 4 + 2] = .5f; values[i * 4 + 3] = 1f;
            } else {
                values[i * 4] = .75f; values[i * 4 + 1] = i * .03125f;
                values[i * 4 + 2] = .125f; values[i * 4 + 3] = 1f;
            }
        }
        return values;
    }

    private void writePreview(Renderer r, FrameBuffer fb, Geometry g) throws Exception {
        renderAndRead(r, fb, g, expected(2), "capture");
        ByteBuffer bytes = BufferUtils.createByteBuffer(WIDTH * 16).order(ByteOrder.nativeOrder());
        r.readFrameBufferWithFormat(fb, bytes, Image.Format.RGBA32F);
        FloatBuffer pixels = bytes.asFloatBuffer();
        BufferedImage image = new BufferedImage(WIDTH, 1, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < WIDTH; x++) {
            int i = x * 4;
            int red = Math.round(pixels.get(i) * 255), green = Math.round(pixels.get(i + 1) * 255), blue = Math.round(pixels.get(i + 2) * 255);
            image.setRGB(x, 0, 0xff000000 | (red << 16) | (green << 8) | blue);
        }
        Files.createDirectories(output);
        ImageIO.write(image, "png", output.resolve("managed-compute.png").toFile());

    }

    private void expect(Class<? extends RuntimeException> type, Runnable operation) {
        try { operation.run(); } catch (RuntimeException ex) { require(type.isInstance(ex), "rejection type " + ex); return; }
        throw new IllegalStateException("Expected " + type.getSimpleName());
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException("Managed compute check failed: " + message);
        checks++;
    }
}
