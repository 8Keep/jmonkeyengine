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
package com.jme3.renderer.opengl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderContext;
import com.jme3.shader.Shader;
import com.jme3.shader.ShaderBufferBlock;
import com.jme3.shader.ShaderBufferBlock.BufferType;
import com.jme3.shader.bufferobject.BufferObject;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reject invalid managed buffer operations before allocation or shader changes. */
class BufferObjectCapabilityTest {

    @Test
    void cleanBufferObjectEntryPointsStillRequireTheirCapabilities() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        BufferObject buffer = cleanBuffer(10);

        assertThrows(UnsupportedOperationException.class,
                () -> renderer.setUniformBufferObject(0, buffer));
        assertThrows(UnsupportedOperationException.class,
                () -> renderer.setShaderStorageBufferObject(0, buffer));
        assertThrows(UnsupportedOperationException.class,
                () -> renderer.updateUniformBufferObjectData(buffer));
        assertThrows(UnsupportedOperationException.class,
                () -> renderer.updateShaderStorageBufferObjectData(buffer));

        assertEquals(0, gl.bufferCalls);
        assertEquals(10, buffer.getId());
    }

    @Test
    void invalidBindingIndicesAreRejectedBeforeDirtyBufferUpload() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        renderer.getCaps().add(Caps.UniformBufferObject);
        renderer.getCaps().add(Caps.ShaderStorageBufferObject);

        for (int index : new int[]{-1, RenderContext.maxBufferObjectUnits}) {
            BufferObject uniform = dirtyBuffer();
            BufferObject storage = dirtyBuffer();
            assertThrows(IllegalArgumentException.class,
                    () -> renderer.setUniformBufferObject(index, uniform));
            assertThrows(IllegalArgumentException.class,
                    () -> renderer.setShaderStorageBufferObject(index, storage));
            assertEquals(-1, uniform.getId());
            assertEquals(-1, storage.getId());
            assertTrue(uniform.isUpdateNeeded());
            assertTrue(storage.isUpdateNeeded());
        }

        assertEquals(0, gl.bufferCalls);
    }

    @Test
    void nullBuffersAreRejectedBeforeAnyGpuMutation() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        renderer.getCaps().add(Caps.UniformBufferObject);
        renderer.getCaps().add(Caps.ShaderStorageBufferObject);

        assertThrows(IllegalArgumentException.class, () -> renderer.setUniformBufferObject(0, null));
        assertThrows(IllegalArgumentException.class, () -> renderer.setShaderStorageBufferObject(0, null));
        assertThrows(IllegalArgumentException.class, () -> renderer.updateUniformBufferObjectData(null));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.updateShaderStorageBufferObjectData(null));
        assertEquals(0, gl.bufferCalls);
    }

    @Test
    void cpuOnlyBindingsRejectBeforeRetiringCleanOrDirtyBuffers() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        renderer.getCaps().add(Caps.UniformBufferObject);
        renderer.getCaps().add(Caps.ShaderStorageBufferObject);

        BufferObject uniform = cleanBuffer(101);
        uniform.setAccessHint(BufferObject.AccessHint.CpuOnly);
        uniform.clearUpdateNeeded();
        BufferObject storage = cleanBuffer(102);
        storage.setAccessHint(BufferObject.AccessHint.CpuOnly);

        assertThrows(IllegalArgumentException.class,
                () -> renderer.setUniformBufferObject(0, uniform));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.setShaderStorageBufferObject(0, storage));

        assertEquals(0, gl.bufferCalls);
        assertEquals(101, uniform.getId());
        assertEquals(102, storage.getId());
    }

    @Test
    void shaderBlockPreflightRejectsLaterUnsupportedBlockBeforeCompilingOrBinding() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        renderer.getCaps().add(Caps.UniformBufferObject);

        Shader shader = new Shader();
        ShaderBufferBlock first = shader.getBufferBlock("m_first");
        first.setBufferObject(BufferType.UniformBufferObject, dirtyBuffer());
        ShaderBufferBlock later = shader.getBufferBlock("m_later");
        later.setBufferObject(BufferType.ShaderStorageBufferObject, dirtyBuffer());

        assertThrows(UnsupportedOperationException.class, () -> renderer.setShader(shader));
        assertEquals(0, gl.shaderCalls);
        assertEquals(0, gl.bufferCalls);
        assertTrue(first.isUpdateNeeded());
        assertTrue(later.isUpdateNeeded());
    }

    @Test
    void shaderBlockPreflightRejectsInvalidBindingBeforeEarlierBlockUpload() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        renderer.getCaps().add(Caps.UniformBufferObject);

        Shader shader = new Shader();
        shader.setId(96);
        shader.clearUpdateNeeded();
        ShaderBufferBlock first = shader.getBufferBlock("m_first");
        first.setBufferObject(BufferType.UniformBufferObject, dirtyBuffer());
        ShaderBufferBlock later = shader.getBufferBlock("m_later");
        BufferObject invalid = dirtyBuffer();
        invalid.setBinding(RenderContext.maxBufferObjectUnits);
        later.setBufferObject(BufferType.UniformBufferObject, invalid);

        assertThrows(IllegalArgumentException.class, () -> renderer.setShader(shader));
        assertEquals(0, gl.shaderCalls);
        assertEquals(0, gl.bufferCalls);
        assertTrue(first.isUpdateNeeded());
        assertTrue(later.isUpdateNeeded());
    }

    @Test
    void unconfiguredShaderBlockRejectsBeforeCompilation() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        Shader shader = new Shader();
        shader.getBufferBlock("m_unconfigured");

        assertThrows(IllegalArgumentException.class, () -> renderer.setShader(shader));
        assertEquals(0, gl.shaderCalls);
        assertEquals(0, gl.bufferCalls);
    }

    @Test
    void cpuOnlyBlocksIgnoreUnusedBindingsAndRetireDirtyGpuStorage() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);

        Shader shader = new Shader();
        shader.setId(93);
        shader.clearUpdateNeeded();
        renderer.setShader(shader);
        gl.shaderCalls = 0;
        BufferObject cpuOnly = cleanBuffer(94);
        cpuOnly.setAccessHint(BufferObject.AccessHint.CpuOnly);
        cpuOnly.clearUpdateNeeded();
        cpuOnly.setBinding(RenderContext.maxBufferObjectUnits);
        shader.getBufferBlock("m_cpuOnly").setBufferObject(BufferType.UniformBufferObject, cpuOnly);

        renderer.setShader(shader);

        assertEquals(0, gl.shaderCalls);
        assertEquals(0, gl.bufferCalls);
        assertTrue(shader.getBufferBlock("m_cpuOnly").isUpdateNeeded());

        // Changing an uploaded block to CPU-only must still retire its GPU storage.
        renderer.getCaps().add(Caps.UniformBufferObject);
        cpuOnly.setAccessHint(BufferObject.AccessHint.CpuOnly);
        renderer.setShader(shader);
        assertEquals(-1, cpuOnly.getId());
        assertEquals(1, gl.count("primary:glDeleteBuffers"));
        assertEquals(0, gl.count("extension:glBindBufferBase"));
    }

    @Test
    void es31CapabilityDiscoveryDoesNotAdvertiseManagedStorageBuffers() throws Exception {
        RecordingGl gl = new RecordingGl();
        gl.version = "OpenGL ES 3.1 test";
        GLRenderer renderer = gl.glesRenderer();
        renderer.initialize();

        assertTrue(renderer.getCaps().contains(Caps.OpenGLES31));
        assertFalse(renderer.getCaps().contains(Caps.ShaderStorageBufferObject));
    }

    @Test
    void desktop43CapabilityDiscoveryAdvertisesManagedStorageBuffers() throws Exception {
        RecordingGl gl = new RecordingGl();
        gl.version = "4.3 test";
        gl.glslVersion = "4.30 test";
        GLRenderer renderer = gl.renderer(true);
        renderer.initialize();

        // GL 4.3 core support is the source of this public capability.
        assertTrue(renderer.getCaps().contains(Caps.OpenGL43));
        assertTrue(renderer.getCaps().contains(Caps.ShaderStorageBufferObject));
    }

    @Test
    void arbShaderStorageCapabilityRequiresProgramInterfaceQueryExtension() throws Exception {
        RecordingGl withoutInterfaceQuery = new RecordingGl();
        withoutInterfaceQuery.version = "4.2 test";
        withoutInterfaceQuery.glslVersion = "4.20 test";
        withoutInterfaceQuery.extensions = "GL_ARB_shader_storage_buffer_object";
        GLRenderer unsupported = withoutInterfaceQuery.renderer(true);
        unsupported.initialize();
        assertFalse(unsupported.getCaps().contains(Caps.ShaderStorageBufferObject));

        RecordingGl withInterfaceQuery = new RecordingGl();
        withInterfaceQuery.version = "4.2 test";
        withInterfaceQuery.glslVersion = "4.20 test";
        withInterfaceQuery.extensions = "GL_ARB_shader_storage_buffer_object GL_ARB_program_interface_query";
        GLRenderer supported = withInterfaceQuery.renderer(true);
        supported.initialize();
        assertTrue(supported.getCaps().contains(Caps.ShaderStorageBufferObject));
    }

    @Test
    void gl42ArbStorageBlocksUseExtensionEntryPoints() throws Exception {
        RecordingGl gl = new RecordingGl();
        gl.version = "4.2 test";
        gl.glslVersion = "4.20 test";
        gl.extensions = "GL_ARB_shader_storage_buffer_object GL_ARB_program_interface_query";
        GLRenderer renderer = gl.renderer(true);
        renderer.initialize();

        Shader shader = new Shader();
        shader.setId(111);
        shader.clearUpdateNeeded();
        BufferObject buffer = cleanBuffer(112);
        buffer.setBinding(0);
        ShaderBufferBlock block = shader.getBufferBlock("m_storage");
        block.setBufferObject(BufferType.ShaderStorageBufferObject, buffer);

        renderer.setShader(shader);

        assertEquals(1, gl.count("extension:glGetProgramResourceIndex"));
        assertEquals(0, gl.count("primary:glGetProgramResourceIndex"));
        assertEquals(1, gl.count("extension:glShaderStorageBlockBinding"));
        assertEquals(0, gl.count("primary:glShaderStorageBlockBinding"));
        assertEquals(0, gl.count("extension:glBindBufferBase"));
        assertEquals(1, gl.count("primary:glBindBufferBase"));
    }

    @Test
    void gl30ArbUniformBlocksUseExtensionQueriesAndPrimaryIndexedBinding() throws Exception {
        RecordingGl gl = new RecordingGl();
        gl.version = "3.0 test";
        gl.glslVersion = "1.30 test";
        gl.extensions = "GL_ARB_uniform_buffer_object";
        GLRenderer renderer = gl.renderer(true);
        renderer.initialize();

        Shader shader = new Shader();
        shader.setId(121);
        shader.clearUpdateNeeded();
        BufferObject buffer = cleanBuffer(122);
        buffer.setBinding(0);
        shader.getBufferBlock("m_uniform").setBufferObject(BufferType.UniformBufferObject, buffer);

        renderer.setShader(shader);

        assertEquals(1, gl.count("extension:glGetUniformBlockIndex"));
        assertEquals(0, gl.count("primary:glGetUniformBlockIndex"));
        assertEquals(1, gl.count("extension:glUniformBlockBinding"));
        assertEquals(0, gl.count("primary:glUniformBlockBinding"));
        assertEquals(0, gl.count("extension:glBindBufferBase"));
        assertEquals(1, gl.count("primary:glBindBufferBase"));
    }

    private static BufferObject cleanBuffer(int id) {
        BufferObject buffer = new BufferObject();
        buffer.setId(id);
        buffer.clearUpdateNeeded();
        return buffer;
    }

    private static BufferObject dirtyBuffer() {
        BufferObject buffer = new BufferObject();
        buffer.setData(ByteBuffer.allocateDirect(4));
        buffer.setBinding(0);
        buffer.setUpdateNeeded();
        return buffer;
    }

    private static final class RecordingGl implements InvocationHandler {
        final List<String> calls = new ArrayList<>();
        int bufferCalls;
        int shaderCalls;
        String version = "OpenGL ES 3.1 test";
        String glslVersion = "OpenGL ES GLSL ES 3.10";
        String extensions = "";

        GLRenderer renderer(boolean desktop) {
            Class<?> glInterface = desktop ? GL4.class : GL.class;
            GL gl = (GL) Proxy.newProxyInstance(glInterface.getClassLoader(),
                    new Class<?>[]{glInterface}, this);
            GLExt extension = (GLExt) Proxy.newProxyInstance(GLExt.class.getClassLoader(),
                    new Class<?>[]{GLExt.class}, this);
            return new GLRenderer(gl, extension, null);
        }

        GLRenderer glesRenderer() {
            GL gl = (GL) Proxy.newProxyInstance(GLES_30.class.getClassLoader(),
                    new Class<?>[]{GLES_30.class}, this);
            GLExt extension = (GLExt) Proxy.newProxyInstance(GLExt.class.getClassLoader(),
                    new Class<?>[]{GLExt.class}, this);
            return new GLRenderer(gl, extension, null);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            String name = method.getName();
            boolean extensionProxy = proxy.getClass().getInterfaces()[0] == GLExt.class;
            calls.add((extensionProxy ? "extension:" : "primary:") + name);
            if (name.equals("glGenBuffers")) {
                ((IntBuffer) arguments[0]).put(0, 201);
            }
            if (name.equals("glCreateProgram")) {
                shaderCalls++;
                return 202;
            }
            if (name.equals("glGetProgram") && (Integer) arguments[1] == GL.GL_LINK_STATUS) {
                ((IntBuffer) arguments[2]).put(0, GL.GL_TRUE);
            }
            if (name.startsWith("glGen") || name.startsWith("glBindBuffer")
                    || name.startsWith("glBuffer") || name.startsWith("glDeleteBuffers")) {
                bufferCalls++;
            }
            if (name.startsWith("glCreate") || name.startsWith("glCompile")
                    || name.startsWith("glLink") || name.equals("glUseProgram")) {
                shaderCalls++;
            }
            if (name.equals("glGetString")) {
                int pname = (Integer) arguments[0];
                if (pname == GL.GL_VERSION) {
                    return version;
                }
                if (pname == GL.GL_SHADING_LANGUAGE_VERSION) {
                    return glslVersion;
                }
                if (pname == GL.GL_EXTENSIONS) {
                    if (arguments.length == 1) {
                        return extensions;
                    }
                    String[] values = extensions.isEmpty() ? new String[0] : extensions.split(" ");
                    int index = (Integer) arguments[1];
                    return index < values.length ? values[index] : null;
                }
                return "Recording driver";
            }
            if (name.equals("glGetInteger") && arguments != null && arguments.length > 1
                    && arguments[1] instanceof IntBuffer) {
                int pname = (Integer) arguments[0];
                int value = pname == GL3.GL_NUM_EXTENSIONS || pname == GLES_30.GL_NUM_EXTENSIONS
                        ? (extensions.isEmpty() ? 0 : extensions.split(" ").length) : 0;
                ((IntBuffer) arguments[1]).put(0, value);
            }
            return defaultValue(method.getReturnType());
        }

        int count(String call) {
            return Collections.frequency(calls, call);
        }

        private static Object defaultValue(Class<?> type) {
            if (type == void.class) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            throw new AssertionError("Unexpected GL return type: " + type);
        }
    }
}
