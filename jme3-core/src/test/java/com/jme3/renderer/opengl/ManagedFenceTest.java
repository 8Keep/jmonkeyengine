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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jme3.renderer.Caps;
import com.jme3.util.NativeObject;
import com.jme3.util.NativeObjectManager;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;


class ManagedFenceTest {

    @Test
    void coreFenceIsRegisteredPolledWithoutBlockingAndDeleted() throws Exception {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        renderer.getCaps().add(Caps.OpenGL32);

        final GLFence fence = renderer.createFence();
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
        final GLFence fence = first.createFence();

        RecordingGl secondGl = new RecordingGl();
        GLRenderer second = secondGl.renderer();
        second.getCaps().add(Caps.OpenGL32);
        assertThrows(IllegalArgumentException.class, () -> second.isFenceSignaled(fence));
        assertThrows(IllegalArgumentException.class, () -> second.deleteFence(fence));
        assertThrows(IllegalArgumentException.class, () -> second.registerNativeObject(fence));
        assertEquals(0, secondGl.waitCalls);
        assertEquals(0, secondGl.deleteCalls);
        assertTrue(fence.getId() != NativeObject.INVALID_ID);
        assertFalse(first.isFenceSignaled(fence));

        firstGl.waitStatus = GLExt.GL_WAIT_FAILED;
        assertThrows(IllegalStateException.class, () -> first.isFenceSignaled(fence));
        firstGl.waitStatus = 0xDEAD;
        assertThrows(IllegalStateException.class, () -> first.isFenceSignaled(fence));

        GLFence unregistered = new GLFence(91L, null);
        assertThrows(IllegalArgumentException.class, () -> first.isFenceSignaled(unregistered));
        first.deleteFence(fence);
        assertThrows(IllegalArgumentException.class, () -> first.isFenceSignaled(fence));
        assertThrows(IllegalArgumentException.class, () -> second.deleteFence(fence));
        assertEquals(0, secondGl.deleteCalls);
    }

    @Test
    void externallyRegisteredFenceStillSupportsDirectDeletion() throws Exception {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        renderer.getCaps().add(Caps.OpenGL32);
        final GLFence fence = new GLFence(0x5678L, null);

        renderer.registerNativeObject(fence);
        renderer.deleteFence(fence);

        assertEquals(1, gl.deleteCalls);
        assertEquals(NativeObject.INVALID_ID, fence.getId());
        manager(renderer).deleteAllObjects(renderer);
        assertEquals(1, gl.deleteCalls);
    }

    @Test
    void managerCleanupInvalidatesTheTrackedWrapper() throws Exception {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        renderer.getCaps().add(Caps.OpenGL32);
        final GLFence fence = renderer.createFence();

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
        final GLFence fence = renderer.createFence();

        GLFence staleClone = (GLFence) fence.createDestructableClone();
        renderer.resetGLObjects();

        assertThrows(IllegalArgumentException.class, () -> renderer.deleteFence(staleClone));
        assertEquals(0, gl.deleteCalls);
        assertEquals(NativeObject.INVALID_ID, fence.getId());
        assertThrows(IllegalArgumentException.class, () -> renderer.isFenceSignaled(fence));
        assertEquals(0, ownedFenceCount(renderer));
        assertEquals(0, gl.waitCalls);
    }

    @Test
    void extensionPathPreservesLongAndOpaqueNativeHandles() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        renderer.getCaps().add(Caps.OpenGLES30);
        gl.createdExtensionHandle = Long.valueOf(0x123456789L);
        final GLFence fence = renderer.createFence();

        gl.waitStatus = GLExt.GL_CONDITION_SATISFIED;
        assertTrue(renderer.isFenceSignaled(fence));
        assertEquals(Long.valueOf(0x123456789L), gl.waitedExtensionHandle);
        renderer.deleteFence(fence);
        assertEquals(Long.valueOf(0x123456789L), gl.deletedExtensionHandle);

        RecordingGl desktopGl = new RecordingGl();
        GLRenderer desktop = desktopGl.renderer(false);
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

    @Test
    void cloneOfDeletedFenceCannotDeleteOrRegisterTheRetiredHandleAgain() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        renderer.getCaps().add(Caps.OpenGL32);
        final GLFence fence = renderer.createFence();
        final GLFence clone = (GLFence) fence.createDestructableClone();
        renderer.deleteFence(fence);

        renderer.deleteFence(clone);
        assertThrows(IllegalArgumentException.class, () -> renderer.registerNativeObject(clone));
        assertEquals(1, gl.deleteCalls);
    }

    @Test
    void disposalDeletesOnceAndRetiresEveryClone() throws Exception {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        renderer.getCaps().add(Caps.OpenGL32);
        final GLFence fence = renderer.createFence();
        final GLFence clone = (GLFence) fence.createDestructableClone();
        fence.dispose();
        manager(renderer).deleteUnused(renderer);

        assertEquals(NativeObject.INVALID_ID, fence.getId());
        assertEquals(0, ownedFenceCount(renderer));
        renderer.deleteFence(clone);
        renderer.deleteFence(fence);
        manager(renderer).deleteAllObjects(renderer);
        assertEquals(1, gl.deleteCalls);
    }

    @Test
    void legacyRegistrationClaimsPreexistingClonesWithoutEnablingManagedPolling() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        renderer.getCaps().add(Caps.OpenGL32);
        GLFence external = new GLFence(0xABCDL, null);
        final GLFence clone = (GLFence) external.createDestructableClone();
        renderer.registerNativeObject(external);
        RecordingGl otherGl = new RecordingGl();
        GLRenderer other = otherGl.renderer();
        other.getCaps().add(Caps.OpenGL32);

        assertThrows(IllegalArgumentException.class, () -> other.deleteFence(clone));
        assertThrows(IllegalArgumentException.class, () -> renderer.isFenceSignaled(external));
        renderer.deleteFence(external);
        renderer.deleteFence(clone);
        assertEquals(0, otherGl.deleteCalls);
        assertEquals(1, gl.deleteCalls);
    }

    @Test
    void coreContextWithoutGl4WrapperUsesExtensionSyncEntryPoints() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer(false);
        renderer.getCaps().add(Caps.OpenGL32);
        final GLFence fence = renderer.createFence();
        gl.waitStatus = GLExt.GL_ALREADY_SIGNALED;
        assertTrue(renderer.isFenceSignaled(fence));
        renderer.deleteFence(fence);
        assertSame(gl.createdExtensionHandle, gl.waitedExtensionHandle);
        assertSame(gl.createdExtensionHandle, gl.deletedExtensionHandle);
    }

    @Test
    void nullInputsDoNotIssueNativeCalls() {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        renderer.getCaps().add(Caps.OpenGL32);
        assertThrows(IllegalArgumentException.class, () -> renderer.isFenceSignaled(null));
        assertThrows(IllegalArgumentException.class, () -> renderer.deleteFence(null));
        assertEquals(0, gl.waitCalls);
        assertEquals(0, gl.deleteCalls);
    }

    @Test
    void cloneFirstDeletionUnregistersAndResetsTheCanonicalWrapper() throws Exception {
        for (boolean managed : new boolean[]{false, true}) {
            RecordingGl gl = new RecordingGl();
            GLRenderer renderer = gl.renderer();
            renderer.getCaps().add(Caps.OpenGL32);
            GLFence fence = managed ? renderer.createFence() : new GLFence(0x1234L, null);
            if (!managed) {
                renderer.registerNativeObject(fence);
            }
            final GLFence clone = (GLFence) fence.createDestructableClone();

            clone.deleteObject(renderer);

            assertEquals(NativeObject.INVALID_ID, fence.getId());
            assertEquals(NativeObject.INVALID_ID, clone.getId());
            assertEquals(0, ownedFenceCount(renderer));
            renderer.deleteFence(fence);
            manager(renderer).deleteAllObjects(renderer);
            assertEquals(1, gl.deleteCalls);
        }
    }

    @Test
    void collectedCanonicalWrapperDoesNotCauseLaterManagerDoubleDeletion() throws Exception {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        renderer.getCaps().add(Caps.OpenGL32);
        GLFence fence = renderer.createFence();
        final GLFence clone = (GLFence) fence.createDestructableClone();
        final NativeObjectManager manager = manager(renderer);

        // Model GC deterministically: clear both weak references to the real
        // wrapper without relying on collection timing or changing runtime APIs.
        Field stateField = GLFence.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object state = stateField.get(fence);
        Field original = state.getClass().getDeclaredField("original");
        original.setAccessible(true);
        ((Reference<?>) original.get(state)).clear();
        Field registrationsField = NativeObjectManager.class.getDeclaredField("refMap");
        registrationsField.setAccessible(true);
        Map<?, ?> registrations = (Map<?, ?>) registrationsField.get(manager);
        Object registration = registrations.get(fence.getUniqueId());
        Field realObject = registration.getClass().getDeclaredField("realObj");
        realObject.setAccessible(true);
        ((Reference<?>) realObject.get(registration)).clear();

        clone.deleteObject(renderer);
        manager.deleteAllObjects(renderer);

        assertEquals(1, gl.deleteCalls);
        assertEquals(NativeObject.INVALID_ID, clone.getId());
        assertEquals(0, ownedFenceCount(renderer));
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
            return renderer(true);
        }

        GLRenderer renderer(boolean coreApi) {
            Class<?> type = coreApi ? GL4.class : GL.class;
            GL gl = (GL) Proxy.newProxyInstance(GL.class.getClassLoader(), new Class<?>[]{type}, this);
            GLExt extension = (GLExt) Proxy.newProxyInstance(GLExt.class.getClassLoader(),
                    new Class<?>[]{GLExt.class}, this);
            return new GLRenderer(gl, extension, null);
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
                    if (!type.isPrimitive() || type == void.class) {
                        return null;
                    }
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == char.class) {
                        return '\0';
                    }
                    if (type == byte.class) {
                        return (byte) 0;
                    }
                    if (type == short.class) {
                        return (short) 0;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    if (type == long.class) {
                        return 0L;
                    }
                    if (type == float.class) {
                        return 0f;
                    }
                    if (type == double.class) {
                        return 0d;
                    }
                    throw new AssertionError(type);
            }
        }
    }
}
