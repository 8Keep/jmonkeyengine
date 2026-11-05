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
import com.jme3.renderer.Limits;
import com.jme3.renderer.MemoryBarrier;
import com.jme3.shader.Shader;
import com.jme3.shader.bufferobject.BufferObject;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedComputeTest {
    @Test
    void unsupportedAndInvalidRequestsRejectBeforeNativeMutation() throws Exception {
        Backend backend = new Backend();
        GLRenderer renderer = backend.renderer();
        Shader compute = compute("GLSL430");
        BufferObject buffer = new BufferObject();
        buffer.setData(ByteBuffer.allocateDirect(16));
        assertThrows(UnsupportedOperationException.class, () -> renderer.dispatchCompute(compute, 1, 1, 1));
        assertThrows(UnsupportedOperationException.class, () -> renderer.setShader(compute));
        assertThrows(UnsupportedOperationException.class, () -> renderer.updateShaderSourceData(compute.getSources().iterator().next()));
        assertThrows(UnsupportedOperationException.class, () -> renderer.setFixedShaderStorageBufferObject(0, buffer));
        assertThrows(UnsupportedOperationException.class, () -> renderer.memoryBarrier(EnumSet.of(MemoryBarrier.UniformBuffer)));
        assertEquals(0, backend.calls);
        renderer.getCaps().add(Caps.OpenGL43);
        discover(renderer);
        backend.calls = 0;
        assertThrows(IllegalArgumentException.class, () -> renderer.dispatchCompute(compute, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> renderer.dispatchCompute(compute, 9, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> renderer.dispatchCompute(compute("GLSL150"), 1, 1, 1));
        compute.addSource(Shader.ShaderType.Vertex, "mixed", "void main(){}", "", "GLSL430");
        assertThrows(IllegalArgumentException.class, () -> renderer.setShader(compute));
        assertThrows(IllegalArgumentException.class, () -> renderer.updateShaderData(compute));
        assertThrows(IllegalArgumentException.class, () -> renderer.setFixedShaderStorageBufferObject(2, buffer));
        assertThrows(IllegalArgumentException.class, () -> renderer.setFixedShaderStorageBufferObject(0, new BufferObject()));
        buffer.setData(ByteBuffer.allocateDirect(65));
        assertThrows(IllegalArgumentException.class, () -> renderer.setFixedShaderStorageBufferObject(0, buffer));
        assertEquals(0, backend.calls);
        assertEquals(-1, compute.getId());
        assertEquals(-1, buffer.getId());
    }

    @Test
    void capabilityRequiresPortableQueriesAndACompletePositiveLimitSet() throws Exception {
        for (boolean es : new boolean[]{false, true}) {
            Backend backend = new Backend();
            GLRenderer renderer = backend.renderer();
            renderer.getCaps().add(es ? Caps.OpenGLES31 : Caps.OpenGL43);
            discover(renderer);
            assertTrue(renderer.getCaps().contains(Caps.ComputeShader));
            assertEquals(8, renderer.getLimits().get(Limits.ComputeWorkGroupCountZ));
            assertEquals(2, renderer.getLimits().get(Limits.ComputeStorageBufferBindings));
            // This separate path must not claim desktop reflection on GLES.
            assertFalse(renderer.getCaps().contains(Caps.ShaderStorageBufferObject));
            backend.missingQuery = true;
            GLRenderer legacyAdapter = backend.renderer();
            legacyAdapter.getCaps().add(Caps.OpenGL43);
            discover(legacyAdapter);
            assertFalse(legacyAdapter.getCaps().contains(Caps.ComputeShader));
            assertFalse(legacyAdapter.getLimits().containsKey(Limits.ComputeWorkGroupCountX));
            backend.missingQuery = false;
            backend.zeroLimit = true;
            GLRenderer invalidLimits = backend.renderer();
            invalidLimits.getCaps().add(Caps.OpenGL43);
            discover(invalidLimits);
            assertFalse(invalidLimits.getCaps().contains(Caps.ComputeShader));
            assertFalse(invalidLimits.getLimits().containsKey(Limits.ComputeStorageBufferBindings));
        }
    }

    private static Shader compute(String language) {
        Shader shader = new Shader();
        shader.addSource(Shader.ShaderType.Compute, "compute", "void main(){}", "", language);
        return shader;
    }

    private static void discover(GLRenderer renderer) throws Exception {
        Method method = GLRenderer.class.getDeclaredMethod("loadComputeCapabilities");
        method.setAccessible(true);
        method.invoke(renderer);
    }

    private static class Backend {
        int calls;
        boolean missingQuery;
        boolean zeroLimit;

        GLRenderer renderer() {
            Object gl = Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{GL4.class, GLExt.class, GLFbo.class}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    return "compute backend";
                }
                calls++;
                if (method.getName().equals("glGetIntegerIndexed")) {
                    if (missingQuery) throw new UnsupportedOperationException("old adapter");
                    ((IntBuffer) args[2]).put(0, 8);
                } else if (method.getName().equals("glGetInteger")) {
                    int pname = (Integer) args[0];
                    int value = pname == GLCompute.GL_MAX_SHADER_STORAGE_BLOCK_SIZE ? 64 : 2;
                    ((IntBuffer) args[1]).put(0, zeroLimit ? 0 : value);
                }
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
            return new GLRenderer((GL) gl, (GLExt) gl, (GLFbo) gl);
        }
    }
}
