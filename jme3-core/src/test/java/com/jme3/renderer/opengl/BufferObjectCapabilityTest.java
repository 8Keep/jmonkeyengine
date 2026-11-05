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

import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderContext;
import com.jme3.shader.Shader;
import com.jme3.shader.ShaderBufferBlock;
import com.jme3.shader.ShaderBufferBlock.BufferType;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.util.NativeObjectManager;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public renderer contract checks for managed buffer blocks and fences. */
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
        assertNull(context(renderer).boundBO[0]);
        assertNull(context(renderer).boundSSBO[0]);
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
        assertTrue(allNull(context(renderer).boundBO));
        assertTrue(allNull(context(renderer).boundSSBO));
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
        assertThrows(IllegalArgumentException.class, () -> renderer.updateShaderStorageBufferObjectData(null));
        assertEquals(0, gl.bufferCalls);
        assertNull(context(renderer).boundBO[0]);
        assertNull(context(renderer).boundSSBO[0]);
    }

    @Test
    void cpuOnlyDirectBindingsRejectBeforeDeleteUploadOrCacheMutation() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        renderer.getCaps().add(Caps.UniformBufferObject);
        renderer.getCaps().add(Caps.ShaderStorageBufferObject);

        BufferObject uniform = cleanBuffer(101);
        uniform.setAccessHint(BufferObject.AccessHint.CpuOnly);
        uniform.clearUpdateNeeded();
        BufferObject storage = cleanBuffer(102);
        storage.setAccessHint(BufferObject.AccessHint.CpuOnly);
        storage.clearUpdateNeeded();

        assertThrows(IllegalArgumentException.class,
                () -> renderer.setUniformBufferObject(0, uniform));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.setShaderStorageBufferObject(0, storage));

        assertEquals(0, gl.bufferCalls);
        assertEquals(101, uniform.getId());
        assertEquals(102, storage.getId());
        assertNull(context(renderer).boundBO[0]);
        assertNull(context(renderer).boundSSBO[0]);
    }

    @Test
    void shaderBlockPreflightRejectsLaterUnsupportedBlockBeforeCompilingOrBinding() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        renderer.getCaps().add(Caps.UniformBufferObject);
        RenderContext context = context(renderer);
        context.boundShaderProgram = 91;
        Shader existing = new Shader();
        context.boundShader = existing;

        Shader shader = new Shader();
        ShaderBufferBlock first = shader.getBufferBlock("m_first");
        first.setBufferObject(BufferType.UniformBufferObject, dirtyBuffer());
        ShaderBufferBlock later = shader.getBufferBlock("m_later");
        later.setBufferObject(BufferType.ShaderStorageBufferObject, dirtyBuffer());

        assertThrows(UnsupportedOperationException.class, () -> renderer.setShader(shader));
        assertEquals(0, gl.shaderCalls);
        assertEquals(0, gl.bufferCalls);
        assertEquals(91, context.boundShaderProgram);
        assertEquals(existing, context.boundShader);
        assertTrue(first.isUpdateNeeded());
        assertTrue(later.isUpdateNeeded());
    }

    @Test
    void shaderBlockPreflightRejectsInvalidBindingBeforeEarlierBlockUpload() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        renderer.getCaps().add(Caps.UniformBufferObject);
        RenderContext context = context(renderer);
        context.boundShaderProgram = 92;
        Shader existing = new Shader();
        context.boundShader = existing;

        Shader shader = new Shader();
        ShaderBufferBlock first = shader.getBufferBlock("m_first");
        first.setBufferObject(BufferType.UniformBufferObject, dirtyBuffer());
        ShaderBufferBlock later = shader.getBufferBlock("m_later");
        BufferObject invalid = dirtyBuffer();
        invalid.setBinding(RenderContext.maxBufferObjectUnits);
        later.setBufferObject(BufferType.UniformBufferObject, invalid);

        assertThrows(IllegalArgumentException.class, () -> renderer.setShader(shader));
        assertEquals(0, gl.shaderCalls);
        assertEquals(0, gl.bufferCalls);
        assertEquals(92, context.boundShaderProgram);
        assertEquals(existing, context.boundShader);
        assertTrue(first.isUpdateNeeded());
        assertTrue(later.isUpdateNeeded());
    }

    @Test
    void shaderBlockPreflightRejectsMissingTypeOrBufferBeforeProgramSideEffects() {
        for (boolean missingType : new boolean[]{true, false}) {
            RecordingGl gl = new RecordingGl();
            GLRenderer renderer = gl.renderer(false);
            renderer.getCaps().add(Caps.UniformBufferObject);
            RenderContext context = context(renderer);
            context.boundShaderProgram = 95;
            Shader existing = new Shader();
            context.boundShader = existing;

            Shader shader = new Shader();
            ShaderBufferBlock block = shader.getBufferBlock("m_incomplete");
            if (missingType) {
                block.setBufferObject(null, dirtyBuffer());
            }

            assertThrows(IllegalArgumentException.class, () -> renderer.setShader(shader));
            assertEquals(0, gl.shaderCalls);
            assertEquals(0, gl.bufferCalls);
            assertEquals(95, context.boundShaderProgram);
            assertEquals(existing, context.boundShader);
            assertTrue(block.isUpdateNeeded());
        }
    }

    @Test
    void cpuOnlyShaderBlockDoesNotValidateAnUnusedBindingIndex() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        RenderContext context = context(renderer);

        Shader shader = new Shader();
        shader.setId(93);
        shader.clearUpdateNeeded();
        context.boundShaderProgram = 93;
        context.boundShader = shader;
        BufferObject cpuOnly = cleanBuffer(94);
        cpuOnly.setAccessHint(BufferObject.AccessHint.CpuOnly);
        cpuOnly.clearUpdateNeeded();
        cpuOnly.setBinding(RenderContext.maxBufferObjectUnits);
        shader.getBufferBlock("m_cpuOnly").setBufferObject(BufferType.UniformBufferObject, cpuOnly);

        renderer.setShader(shader);

        assertEquals(0, gl.shaderCalls);
        assertEquals(0, gl.bufferCalls);
        assertEquals(93, context.boundShaderProgram);
        assertTrue(shader.getBufferBlock("m_cpuOnly").isUpdateNeeded());
    }

    @Test
    void es31CapabilityDiscoveryDoesNotAdvertiseManagedStorageBuffers() throws Exception {
        RecordingGl gl = new RecordingGl();
        gl.version = "OpenGL ES 3.1 test";
        GLRenderer renderer = gl.glesRenderer();
        invokePrivate(renderer, "loadCapabilities");

        assertTrue(renderer.getCaps().contains(Caps.OpenGLES31));
        assertFalse(renderer.getCaps().contains(Caps.ShaderStorageBufferObject));
    }

    @Test
    void desktop43CapabilityDiscoveryAdvertisesManagedStorageBuffers() throws Exception {
        RecordingGl gl = new RecordingGl();
        gl.version = "4.3 test";
        gl.glslVersion = "4.30 test";
        GLRenderer renderer = gl.renderer(true);
        invokePrivate(renderer, "loadCapabilities");

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
        invokePrivate(unsupported, "loadCapabilities");
        assertFalse(unsupported.getCaps().contains(Caps.ShaderStorageBufferObject));

        RecordingGl withInterfaceQuery = new RecordingGl();
        withInterfaceQuery.version = "4.2 test";
        withInterfaceQuery.glslVersion = "4.20 test";
        withInterfaceQuery.extensions = "GL_ARB_shader_storage_buffer_object GL_ARB_program_interface_query";
        GLRenderer supported = withInterfaceQuery.renderer(true);
        invokePrivate(supported, "loadCapabilities");
        assertTrue(supported.getCaps().contains(Caps.ShaderStorageBufferObject));
    }

    @Test
    void gl42ArbStorageBlocksUseExtensionEntryPoints() throws Exception {
        RecordingGl gl = new RecordingGl();
        gl.version = "4.2 test";
        gl.glslVersion = "4.20 test";
        gl.extensions = "GL_ARB_shader_storage_buffer_object GL_ARB_program_interface_query";
        GLRenderer renderer = gl.renderer(true);
        invokePrivate(renderer, "loadCapabilities");

        Shader shader = new Shader();
        shader.setId(111);
        shader.clearUpdateNeeded();
        BufferObject buffer = cleanBuffer(112);
        buffer.setBinding(0);
        ShaderBufferBlock block = shader.getBufferBlock("m_storage");
        block.setBufferObject(BufferType.ShaderStorageBufferObject, buffer);

        renderer.setShader(shader);

        assertEquals(1, gl.extensionResourceIndexCalls);
        assertEquals(0, gl.primaryResourceIndexCalls);
        assertEquals(1, gl.extensionStorageBlockBindingCalls);
        assertEquals(0, gl.primaryStorageBlockBindingCalls);
        assertEquals(0, gl.extensionBufferBaseCalls);
        assertEquals(1, gl.primaryBufferBaseCalls);
    }

    @Test
    void gl30ArbUniformBlocksUseExtensionQueriesAndPrimaryIndexedBinding() throws Exception {
        RecordingGl gl = new RecordingGl();
        gl.version = "3.0 test";
        gl.glslVersion = "1.30 test";
        gl.extensions = "GL_ARB_uniform_buffer_object";
        GLRenderer renderer = gl.renderer(true);
        invokePrivate(renderer, "loadCapabilities");

        Shader shader = new Shader();
        shader.setId(121);
        shader.clearUpdateNeeded();
        BufferObject buffer = cleanBuffer(122);
        buffer.setBinding(0);
        shader.getBufferBlock("m_uniform").setBufferObject(BufferType.UniformBufferObject, buffer);

        renderer.setShader(shader);

        assertEquals(1, gl.extensionUniformBlockIndexCalls);
        assertEquals(0, gl.primaryUniformBlockIndexCalls);
        assertEquals(1, gl.extensionUniformBlockBindingCalls);
        assertEquals(0, gl.primaryUniformBlockBindingCalls);
        assertEquals(0, gl.extensionBufferBaseCalls);
        assertEquals(1, gl.primaryBufferBaseCalls);
    }

    @Test
    void desktop31ArbSyncUsesExtensionAndPreservesOpaqueNativeHandles() throws Exception {
        RecordingGl gl = new RecordingGl();
        gl.version = "3.1 test";
        gl.glslVersion = "1.40 test";
        gl.extensions = "GL_ARB_sync";
        GLRenderer renderer = gl.renderer(true);
        invokePrivate(renderer, "loadCapabilities");

        GLFence longHandleFence = new GLFence(0x23456789L, null);
        renderer.registerNativeObject(longHandleFence);
        renderer.deleteFence(longHandleFence);
        assertEquals(Long.valueOf(0x23456789L), gl.deletedSyncHandles.get(0));

        Object nativeSync = new Object();
        GLFence nativeHandleFence = new GLFence(0x3456789AL, nativeSync);
        renderer.registerNativeObject(nativeHandleFence);
        renderer.deleteFence(nativeHandleFence);
        assertEquals(nativeSync, gl.deletedSyncHandles.get(1));

        int deleteCalls = gl.extensionDeleteSyncCalls;
        manager(renderer).deleteAllObjects(renderer);
        assertEquals(deleteCalls, gl.extensionDeleteSyncCalls);
        assertEquals(2, gl.extensionDeleteSyncCalls);
        assertEquals(0, gl.primaryDeleteSyncCalls);
    }

    @Test
    void esFenceDeleteUsesLongHandleAndResets() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.glesRenderer();
        renderer.getCaps().add(Caps.OpenGLES30);
        GLFence fence = new GLFence(0x123456789L, null);
        renderer.registerNativeObject(fence);

        renderer.deleteFence(fence);

        assertEquals(1, gl.deletedFenceIds.size());
        assertEquals(Long.valueOf(0x123456789L), gl.deletedFenceIds.get(0));
        assertEquals(-1, fence.getId());
        manager(renderer).deleteAllObjects(renderer);
        assertEquals(1, gl.deletedFenceIds.size());
    }

    @Test
    void unsupportedFenceDeleteRejectsBeforeMutationButInvalidDeleteIsHarmless() throws Exception {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        GLFence fence = new GLFence(123L, null);

        assertThrows(UnsupportedOperationException.class,
                () -> renderer.registerNativeObject(fence));
        assertTrue(fence.getId() >= 0);
        manager(renderer).deleteAllObjects(renderer);
        assertEquals(0, gl.deletedFenceIds.size());
        assertThrows(UnsupportedOperationException.class, () -> renderer.deleteFence(fence));
        assertTrue(fence.getId() >= 0);
        assertEquals(0, gl.deletedFenceIds.size());

        GLFence invalid = new GLFence(124L, null);
        invalid.resetObject();
        renderer.deleteFence(invalid);
        assertEquals(0, gl.deletedFenceIds.size());
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

    private static boolean allNull(Object[] values) {
        for (Object value : values) if (value != null) return false;
        return true;
    }

    private static RenderContext context(GLRenderer renderer) {
        return (RenderContext) field(renderer, "context");
    }

    private static NativeObjectManager manager(GLRenderer renderer) {
        return (NativeObjectManager) field(renderer, "objManager");
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void invokePrivate(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static final class RecordingGl implements InvocationHandler {
        final List<Long> deletedFenceIds = new ArrayList<>();
        final List<Object> deletedSyncHandles = new ArrayList<>();
        int bufferCalls;
        int shaderCalls;
        int extensionResourceIndexCalls;
        int primaryResourceIndexCalls;
        int extensionUniformBlockIndexCalls;
        int primaryUniformBlockIndexCalls;
        int extensionStorageBlockBindingCalls;
        int primaryStorageBlockBindingCalls;
        int extensionUniformBlockBindingCalls;
        int primaryUniformBlockBindingCalls;
        int extensionBufferBaseCalls;
        int primaryBufferBaseCalls;
        int extensionDeleteSyncCalls;
        int primaryDeleteSyncCalls;
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
            if (name.equals("glGetProgramResourceIndex")) {
                if (extensionProxy) extensionResourceIndexCalls++;
                else primaryResourceIndexCalls++;
            } else if (name.equals("glGetUniformBlockIndex")) {
                if (extensionProxy) extensionUniformBlockIndexCalls++;
                else primaryUniformBlockIndexCalls++;
            } else if (name.equals("glShaderStorageBlockBinding")) {
                if (extensionProxy) extensionStorageBlockBindingCalls++;
                else primaryStorageBlockBindingCalls++;
            } else if (name.equals("glUniformBlockBinding")) {
                if (extensionProxy) extensionUniformBlockBindingCalls++;
                else primaryUniformBlockBindingCalls++;
            } else if (name.equals("glBindBufferBase")) {
                if (extensionProxy) extensionBufferBaseCalls++;
                else primaryBufferBaseCalls++;
            }
            if (name.startsWith("glGen") || name.startsWith("glBindBuffer")
                    || name.startsWith("glBuffer") || name.startsWith("glDeleteBuffers")) {
                bufferCalls++;
            }
            if (name.startsWith("glCreate") || name.startsWith("glCompile")
                    || name.startsWith("glLink") || name.equals("glUseProgram")) {
                shaderCalls++;
            }
            if (name.equals("glDeleteSync") && arguments != null && arguments.length > 0) {
                Object handle = arguments[0];
                deletedSyncHandles.add(handle);
                if (extensionProxy) extensionDeleteSyncCalls++;
                else primaryDeleteSyncCalls++;
                if (handle instanceof Long) deletedFenceIds.add((Long) handle);
                else if (handle instanceof GLFence) deletedFenceIds.add(((GLFence) handle).getFenceId());
            }
            if (name.equals("glGetString")) {
                int pname = (Integer) arguments[0];
                if (pname == GL.GL_VERSION) return version;
                if (pname == GL.GL_SHADING_LANGUAGE_VERSION) return glslVersion;
                if (pname == GL.GL_EXTENSIONS) {
                    if (arguments.length == 1) return extensions;
                    String[] values = extensions.isEmpty() ? new String[0] : extensions.split(" ");
                    int index = (Integer) arguments[1];
                    return index < values.length ? values[index] : null;
                }
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

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) return null;
            if (type == boolean.class) return false;
            if (type == char.class) return '\0';
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            throw new AssertionError(type);
        }
    }
}
