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
package com.jme3.renderer.opengl;

import com.jme3.renderer.Caps;
import com.jme3.util.NativeObject;
import com.jme3.util.NativeObjectManager;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedFenceTest {

    @Test
    void coreFenceIsRegisteredPolledWithoutBlockingAndDeleted() throws Exception {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        renderer.getCaps().add(Caps.OpenGL32);

        GLFence fence = renderer.createFence();
        assertEquals(GLExt.GL_SYNC_GPU_COMMANDS_COMPLETE, gl.fenceCondition);
        assertEquals(0, gl.fenceFlags);

        gl.waitStatus = GLExt.GL_ALREADY_SIGNALED;
        assertTrue(renderer.isFenceSignaled(fence));
        gl.waitStatus = GLExt.GL_CONDITION_SATISFIED;
        assertTrue(renderer.isFenceSignaled(fence));
        gl.waitStatus = GLExt.GL_TIMEOUT_EXPIRED;
        assertFalse(renderer.isFenceSignaled(fence));
        assertEquals(fence, gl.waitedFence);
        assertEquals(GLExt.GL_SYNC_FLUSH_COMMANDS_BIT, gl.waitFlags);
        assertEquals(0L, gl.waitTimeout);

        renderer.deleteFence(fence);
        assertEquals(NativeObject.INVALID_ID, fence.getId());
        assertThrows(IllegalArgumentException.class, () -> renderer.isFenceSignaled(fence));
        assertEquals(1, gl.deleteCalls);
        assertEquals(0, ownedFenceCount(renderer));
        manager(renderer).deleteAllObjects(renderer);
        assertEquals(1, gl.deleteCalls);
    }

    @Test
    void rejectsForeignStaleFailedAndUnknownFences() {
        RecordingGl firstGl = new RecordingGl();
        GLRenderer first = firstGl.renderer();
        first.getCaps().add(Caps.OpenGL32);
        GLFence fence = first.createFence();

        RecordingGl secondGl = new RecordingGl();
        GLRenderer second = secondGl.renderer();
        second.getCaps().add(Caps.OpenGL32);
        assertThrows(IllegalArgumentException.class, () -> second.isFenceSignaled(fence));
        assertEquals(0, secondGl.waitCalls);

        firstGl.waitStatus = GLExt.GL_WAIT_FAILED;
        assertThrows(IllegalStateException.class, () -> first.isFenceSignaled(fence));
        firstGl.waitStatus = 0xDEAD;
        assertThrows(IllegalStateException.class, () -> first.isFenceSignaled(fence));

        GLFence unregistered = new GLFence(91L, null);
        assertThrows(IllegalArgumentException.class, () -> first.isFenceSignaled(unregistered));
        first.deleteFence(fence);
        assertThrows(IllegalArgumentException.class, () -> first.isFenceSignaled(fence));
    }

    @Test
    void managerCleanupInvalidatesTheTrackedWrapper() throws Exception {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        renderer.getCaps().add(Caps.OpenGL32);
        GLFence fence = renderer.createFence();

        manager(renderer).deleteAllObjects(renderer);

        assertEquals(NativeObject.INVALID_ID, fence.getId());
        assertThrows(IllegalArgumentException.class, () -> renderer.isFenceSignaled(fence));
        assertEquals(1, gl.deleteCalls);
        assertEquals(0, ownedFenceCount(renderer));
    }

    @Test
    void rendererResetInvalidatesAndForgetsTrackedWrapper() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        renderer.getCaps().add(Caps.OpenGL32);
        GLFence fence = renderer.createFence();

        renderer.resetGLObjects();

        assertEquals(NativeObject.INVALID_ID, fence.getId());
        assertThrows(IllegalArgumentException.class, () -> renderer.isFenceSignaled(fence));
        assertEquals(0, ownedFenceCount(renderer));
        assertEquals(0, gl.waitCalls);
    }

    @Test
    void extensionPathPreservesLongAndOpaqueNativeHandles() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        renderer.getCaps().add(Caps.OpenGLES30);
        gl.createdExtensionHandle = Long.valueOf(0x123456789L);
        GLFence fence = renderer.createFence();

        gl.waitStatus = GLExt.GL_CONDITION_SATISFIED;
        assertTrue(renderer.isFenceSignaled(fence));
        assertEquals(Long.valueOf(0x123456789L), gl.waitedExtensionHandle);
        renderer.deleteFence(fence);
        assertEquals(Long.valueOf(0x123456789L), gl.deletedExtensionHandle);

        RecordingGl desktopGl = new RecordingGl();
        GLRenderer desktop = desktopGl.renderer();
        desktop.getCaps().add(Caps.OpenGL31);
        setExtensions(desktop, "GL_ARB_sync");
        Object opaque = new Object();
        desktopGl.createdExtensionHandle = opaque;
        GLFence opaqueFence = desktop.createFence();
        desktopGl.waitStatus = GLExt.GL_ALREADY_SIGNALED;
        assertTrue(desktop.isFenceSignaled(opaqueFence));
        assertSame(opaque, desktopGl.waitedExtensionHandle);
        desktop.deleteFence(opaqueFence);
        assertSame(opaque, desktopGl.deletedExtensionHandle);
    }

    @Test
    void unsupportedAndInvalidNativeCreationFailBeforeRegistration() {
        RecordingGl unsupportedGl = new RecordingGl();
        GLRenderer unsupported = unsupportedGl.renderer();
        assertThrows(UnsupportedOperationException.class, unsupported::createFence);
        assertEquals(0, unsupportedGl.fenceCalls);

        RecordingGl zeroGl = new RecordingGl();
        GLRenderer gles = zeroGl.renderer();
        gles.getCaps().add(Caps.OpenGLES30);
        zeroGl.createdExtensionHandle = Long.valueOf(0L);
        assertThrows(IllegalStateException.class, gles::createFence);
        assertEquals(0, ownedFenceCount(gles));

        RecordingGl nullGl = new RecordingGl();
        GLRenderer nullGles = nullGl.renderer();
        nullGles.getCaps().add(Caps.OpenGLES30);
        nullGl.createdExtensionHandle = null;
        assertThrows(IllegalStateException.class, nullGles::createFence);
        assertEquals(0, ownedFenceCount(nullGles));
    }

    @Test
    void coreCreationRejectsNullAndZeroNativeHandles() {
        RecordingGl nullGl = new RecordingGl();
        GLRenderer nullRenderer = nullGl.renderer();
        nullRenderer.getCaps().add(Caps.OpenGL32);
        nullGl.coreFence = null;
        assertThrows(IllegalStateException.class, nullRenderer::createFence);
        assertEquals(0, ownedFenceCount(nullRenderer));

        RecordingGl zeroGl = new RecordingGl();
        GLRenderer zeroRenderer = zeroGl.renderer();
        zeroRenderer.getCaps().add(Caps.OpenGL32);
        zeroGl.coreFence = new GLFence(0L, null);
        assertThrows(IllegalStateException.class, zeroRenderer::createFence);
        assertEquals(0, ownedFenceCount(zeroRenderer));
    }

    private static NativeObjectManager manager(GLRenderer renderer) throws Exception {
        Field field = GLRenderer.class.getDeclaredField("objManager");
        field.setAccessible(true);
        return (NativeObjectManager) field.get(renderer);
    }

    private static int ownedFenceCount(GLRenderer renderer) {
        try {
            Field field = GLRenderer.class.getDeclaredField("managedFences");
            field.setAccessible(true);
            return ((java.util.Set<?>) field.get(renderer)).size();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void setExtensions(GLRenderer renderer, String extension) {
        try {
            Field field = GLRenderer.class.getDeclaredField("extensions");
            field.setAccessible(true);
            field.set(renderer, new java.util.HashSet<>(java.util.Collections.singleton(extension)));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class RecordingGl implements InvocationHandler {
        Object createdExtensionHandle = Long.valueOf(0x1234L);
        Object waitedExtensionHandle;
        Object deletedExtensionHandle;
        GLFence coreFence = new GLFence(0x1234L, null);
        GLFence waitedFence;
        int fenceCondition;
        int fenceFlags;
        int waitFlags;
        int waitStatus = GLExt.GL_TIMEOUT_EXPIRED;
        long waitTimeout;
        int fenceCalls;
        int waitCalls;
        int deleteCalls;

        GLRenderer renderer() {
            GL4 gl4 = (GL4) Proxy.newProxyInstance(GL4.class.getClassLoader(),
                    new Class<?>[]{GL4.class}, this);
            GLExt extension = (GLExt) Proxy.newProxyInstance(GLExt.class.getClassLoader(),
                    new Class<?>[]{GLExt.class}, this);
            return new GLRenderer(gl4, extension, null);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            switch (method.getName()) {
                case "glFenceSync":
                    fenceCalls++;
                    fenceCondition = (Integer) args[0];
                    fenceFlags = (Integer) args[1];
                    return proxy instanceof GL4 ? coreFence : createdExtensionHandle;
                case "glClientWaitSync":
                    waitCalls++;
                    waitFlags = (Integer) args[1];
                    waitTimeout = (Long) args[2];
                    if (args[0] instanceof GLFence) {
                        waitedFence = (GLFence) args[0];
                    } else {
                        waitedExtensionHandle = args[0];
                    }
                    return waitStatus;
                case "glDeleteSync":
                    deleteCalls++;
                    if (!(args[0] instanceof GLFence)) {
                        deletedExtensionHandle = args[0];
                    }
                    return null;
                default:
                    Class<?> type = method.getReturnType();
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
}
