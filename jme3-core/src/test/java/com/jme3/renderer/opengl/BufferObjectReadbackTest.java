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
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.renderer.opengl;

import com.jme3.renderer.Caps;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.util.BufferUtils;
import com.jme3.util.NativeObject;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BufferObjectReadbackTest {

    @Test
    void validatesRangesReadsWithoutChangingDestinationAndRestoresBinding() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        BufferObject source = buffer(41);
        ByteBuffer destination = BufferUtils.createByteBuffer(12);
        destination.position(2);
        destination.limit(8);

        renderer.readBufferObject(source, 3, destination);

        assertEquals(1, gl.readCalls);
        assertEquals(3L, gl.lastOffset);
        assertEquals(77, gl.boundStorageBuffer);
        assertEquals(2, destination.position());
        assertEquals(8, destination.limit());
        assertEquals(9, destination.get(2));

        ByteBuffer empty = BufferUtils.createByteBuffer(0);
        renderer.readBufferObject(source, 16, empty);
        assertEquals(1, gl.readCalls);

        int calls = gl.bindCalls;
        assertThrows(IllegalArgumentException.class, () -> renderer.readBufferObject(source, -1, destination));
        assertThrows(IllegalArgumentException.class, () -> renderer.readBufferObject(source, 11, destination));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.readBufferObject(source, Long.MAX_VALUE, destination));
        assertThrows(IllegalArgumentException.class, () -> renderer.readBufferObject(source, 0, ByteBuffer.allocate(4)));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.readBufferObject(source, 0, BufferUtils.createByteBuffer(4).asReadOnlyBuffer()));
        assertThrows(IllegalArgumentException.class, () -> renderer.readBufferObject(null, 0, destination));
        assertEquals(calls, gl.bindCalls);
        assertEquals(0, gl.bufferDataCalls);
        assertEquals(1, gl.readCalls);

        BufferObject dirty = buffer(42);
        dirty.setUpdateNeeded();
        assertThrows(IllegalStateException.class, () -> renderer.readBufferObject(dirty, 0, destination));
        BufferObject invalid = buffer(NativeObject.INVALID_ID);
        assertThrows(IllegalStateException.class, () -> renderer.readBufferObject(invalid, 0, destination));
        BufferObject cpuOnly = buffer(43);
        cpuOnly.setAccessHint(BufferObject.AccessHint.CpuOnly);
        assertThrows(IllegalArgumentException.class, () -> renderer.readBufferObject(cpuOnly, 0, destination));
        assertEquals(calls, gl.bindCalls);

        gl.failRead = true;
        assertThrows(IllegalStateException.class, () -> renderer.readBufferObject(source, 0, destination));
        assertEquals(77, gl.boundStorageBuffer);

        RecordingGl unsupportedGl = new RecordingGl();
        GLRenderer unsupported = unsupportedGl.renderer();
        unsupported.getCaps().remove(Caps.ShaderStorageBufferObject);
        assertThrows(UnsupportedOperationException.class,
                () -> unsupported.readBufferObject(source, 0, destination));
        assertFalse(unsupportedGl.readCalls > 0);

        // GLES exposes fixed compute storage without desktop block reflection.
        unsupported.getCaps().add(Caps.ComputeShader);
        unsupported.readBufferObject(source, 3, destination);
        assertEquals(1, unsupportedGl.readCalls);
        assertEquals(77, unsupportedGl.boundStorageBuffer);
    }

    private static BufferObject buffer(int id) {
        BufferObject result = new BufferObject();
        result.setData(BufferUtils.createByteBuffer(16));
        result.setId(id);
        result.clearUpdateNeeded();
        return result;
    }

    private static final class RecordingGl implements InvocationHandler {
        int boundStorageBuffer = 77;
        int bindCalls;
        int bufferDataCalls;
        int readCalls;
        long lastOffset;
        boolean failRead;

        GLRenderer renderer() {
            GL4 gl = (GL4) Proxy.newProxyInstance(GL4.class.getClassLoader(),
                    new Class<?>[]{GL4.class}, this);
            GLExt extension = (GLExt) Proxy.newProxyInstance(GLExt.class.getClassLoader(),
                    new Class<?>[]{GLExt.class}, this);
            GLRenderer renderer = new GLRenderer(gl, extension, null);
            renderer.getCaps().add(Caps.ShaderStorageBufferObject);
            return renderer;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            String name = method.getName();
            if (name.equals("glGetInteger")) {
                ((IntBuffer) arguments[1]).put(0, boundStorageBuffer);
            } else if (name.equals("glBindBuffer")) {
                bindCalls++;
                if ((Integer) arguments[0] == GL4.GL_SHADER_STORAGE_BUFFER) {
                    boundStorageBuffer = (Integer) arguments[1];
                }
            } else if (name.equals("glBufferData")) {
                bufferDataCalls++;
            } else if (name.equals("glGetBufferSubData")) {
                readCalls++;
                lastOffset = (Long) arguments[1];
                if (failRead) throw new IllegalStateException("synthetic read failure");
                ByteBuffer output = (ByteBuffer) arguments[2];
                while (output.hasRemaining()) output.put((byte) 9);
            }
            Class<?> resultType = method.getReturnType();
            if (resultType == boolean.class) return false;
            if (resultType == int.class) return 0;
            if (resultType == long.class) return 0L;
            if (resultType == float.class) return 0f;
            if (resultType == double.class) return 0d;
            return null;
        }
    }
}
