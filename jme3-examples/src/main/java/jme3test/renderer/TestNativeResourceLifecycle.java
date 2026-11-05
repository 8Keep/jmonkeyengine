/*
 * Copyright (c) 2009-2024 jMonkeyEngine
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
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.renderer;

import com.jme3.app.SimpleApplication;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.opengl.GL4;
import com.jme3.renderer.opengl.GLFence;
import com.jme3.renderer.opengl.GLRenderer;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.Shader;
import com.jme3.shader.ShaderBufferBlock.BufferType;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.shader.bufferobject.BufferRegion;
import com.jme3.system.AppSettings;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import com.jme3.util.NativeObject;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Asset-free pixel probe for explicit resource deletion, recreation, stale
 * deferred disposal and shutdown cleanup. UBO drawing is required on GL/GLES;
 * the same buffer is also read through an independent SSBO binding on desktop
 * GL4. GLES storage-block/fence wrappers are not claimed by this example.
 * Arguments: renderer name, output directory. Run with Java assertions enabled.
 */
public final class TestNativeResourceLifecycle extends SimpleApplication {
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Path output;
    private volatile Throwable failure;
    private int checks;

    private TestNativeResourceLifecycle(Path output) {
        this.output = output;
    }

    public static void main(String[] args) throws Exception {
        TestNativeResourceLifecycle app = new TestNativeResourceLifecycle(Paths.get(
                args.length > 1 ? args[1] : "build/native-resource-lifecycle"));
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length > 0 ? args[0] : AppSettings.ANGLE_GLES3);
        settings.setResolution(320, 240);
        settings.setAudioRenderer(null);
        settings.setGraphicsDebug(true);
        settings.setGammaCorrection(true);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        boolean completed = app.finished.await(45, TimeUnit.SECONDS);
        if (!completed || app.failure != null) {
            app.stop(false);
            throw new IllegalStateException(completed ? "Native lifecycle probe failed"
                    : "Native lifecycle probe timed out", app.failure);
        }
        app.stop(true);
        if (app.failure != null) {
            throw new IllegalStateException("Native lifecycle shutdown failed", app.failure);
        }
        System.out.println("NATIVE_RESOURCE_LIFECYCLE_PASS checks=" + app.checks
                + " output=" + app.output.toAbsolutePath());
    }

    @Override
    public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            runProbe(renderManager.getRenderer());
        } catch (Throwable error) {
            failure = error;
        } finally {
            finished.countDown();
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        failure = error == null ? new IllegalStateException(message) : error;
        System.err.println(message);
        failure.printStackTrace();
        finished.countDown();
    }

    private void runProbe(Renderer renderer) throws Exception {
        require(renderer.getCaps().contains(Caps.UniformBufferObject), "UBO capability");
        boolean storage = !renderer.getCaps().contains(Caps.OpenGLES30)
                && renderer.getCaps().contains(Caps.ShaderStorageBufferObject);
        String language = renderer.getCaps().contains(Caps.OpenGLES30) ? "GLSL300"
                : storage ? "GLSL430" : "GLSL330";
        Shader shader = new Shader();
        shader.addSource(Shader.ShaderType.Vertex, "Lifecycle.vert",
                "in vec3 inPosition;\nvoid main(){gl_Position=vec4(inPosition,1.0);}", "", language);
        shader.addSource(Shader.ShaderType.Fragment, "Lifecycle.frag",
                "#ifdef GL_ES\nprecision highp float;\n#endif\n"
                + "layout(std140) uniform m_UniformBlock { vec4 uColor; };\n"
                + (storage ? "layout(std430) buffer m_StorageBlock { vec4 sColor; };\n" : "")
                + "out vec4 outFragColor;\nvoid main(){outFragColor="
                + (storage ? "(uColor+sColor)*0.5" : "uColor") + ";}", "", language);

        BufferObject color = new BufferObject();
        color.setRegions(Arrays.asList(new BufferRegion(0, 7), new BufferRegion(8, 15)));
        color.getData().order(ByteOrder.nativeOrder()).asFloatBuffer()
                .put(new float[]{.125f, .75f, .25f, 1f});
        color.setBinding(2);
        shader.getBufferBlock("m_UniformBlock").setBufferObject(BufferType.UniformBufferObject, color);
        if (storage) {
            shader.getBufferBlock("m_StorageBlock").setBufferObject(BufferType.ShaderStorageBufferObject, color);
        }

        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3,
                new float[]{-1, -1, 0, 3, -1, 0, -1, 3, 0});
        mesh.setBuffer(VertexBuffer.Type.Index, 3, new short[]{0, 1, 2});
        mesh.updateCounts();
        VertexBuffer position = mesh.getBuffer(VertexBuffer.Type.Position);
        VertexBuffer index = mesh.getBuffer(VertexBuffer.Type.Index);
        FrameBuffer framebuffer = new FrameBuffer(8, 8, 1);
        framebuffer.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA8));

        byte[] last = null;
        for (int round = 0; round < 4; round++) {
            last = draw(renderer, framebuffer, shader, mesh);
            // Queue deletion of this registration, then explicitly retire it
            // and recreate the same Java objects before the queue drains.
            shader.dispose();
            position.dispose();
            index.dispose();
            color.dispose();
            renderer.deleteShader(shader);
            renderer.deleteBuffer(position);
            renderer.deleteBuffer(index);
            renderer.deleteBuffer(color);
            require(shader.getId() == NativeObject.INVALID_ID, "shader retired");
            require(color.isUpdateNeeded() && color.getRegion(0).isDirty()
                    && color.getRegion(1).isDirty(), "complete BO reupload required");
            last = draw(renderer, framebuffer, shader, mesh);
            renderer.postFrame();
            require(shader.getId() > 0 && position.getId() > 0 && index.getId() > 0
                    && color.getId() > 0, "stale dispose preserves replacement resources");
            last = draw(renderer, framebuffer, shader, mesh);
        }

        GL4 gl4 = ((GLRenderer) renderer).getGl4();
        if (gl4 != null && renderer.getCaps().contains(Caps.OpenGL32)) {
            for (int i = 0; i < 4; i++) {
                GLFence fence = gl4.glFenceSync(GL4.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                renderer.registerNativeObject(fence);
                fence.dispose();
                renderer.deleteFence(fence);
                renderer.postFrame();
                require(fence.getId() == NativeObject.INVALID_ID, "fence retired");
            }
            // Leave a live replacement for manager-owned shutdown cleanup.
            renderer.registerNativeObject(gl4.glFenceSync(GL4.GL_SYNC_GPU_COMMANDS_COMPLETE, 0));
        }
        writePixels(last);
        renderer.setFrameBuffer(null);
        System.out.println("NATIVE_LIFECYCLE_DRAW_PASS storage=" + storage
                + " fence=" + (gl4 != null) + " checks=" + checks);
        // Shader, VBs, BO, FBO and optional fence remain registered. Assertion-
        // enabled app.stop(true) checks ordinary manager-owned teardown too.
    }

    private byte[] draw(Renderer renderer, FrameBuffer framebuffer, Shader shader, Mesh mesh) {
        renderer.setFrameBuffer(framebuffer);
        renderer.setBackgroundColor(ColorRGBA.Black);
        renderer.clearBuffers(true, false, false);
        renderer.setShader(shader);
        renderer.renderMesh(mesh, 0, 1, null);
        ByteBuffer pixels = BufferUtils.createByteBuffer(8 * 8 * 4);
        renderer.readFrameBufferWithFormat(framebuffer, pixels, Image.Format.RGBA8);
        byte[] actual = new byte[pixels.capacity()];
        pixels.rewind();
        pixels.get(actual);
        int[] expected = {32, 191, 64, 255};
        for (int i = 0; i < actual.length; i++) {
            require(Math.abs((actual[i] & 255) - expected[i % 4]) <= 1,
                    "pixel " + i + "=" + (actual[i] & 255));
        }
        return actual;
    }

    private void writePixels(byte[] pixels) throws Exception {
        Files.createDirectories(output);
        BufferedImage preview = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int i = (y * 8 + x) * 4;
                preview.setRGB(x, y, (pixels[i + 3] & 255) << 24 | (pixels[i] & 255) << 16
                        | (pixels[i + 1] & 255) << 8 | (pixels[i + 2] & 255));
            }
        }
        ImageIO.write(preview, "png", output.resolve("lifecycle.png").toFile());
    }

    private void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Lifecycle check failed: " + label);
        checks++;
    }
}
