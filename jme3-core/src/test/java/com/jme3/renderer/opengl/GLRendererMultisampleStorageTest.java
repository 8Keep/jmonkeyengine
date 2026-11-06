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
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.renderer.opengl;

import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.RendererException;
import com.jme3.system.NullRenderer;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.util.BufferUtils;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GLRendererMultisampleStorageTest {
    @Test
    void glesUsesImmutableStorageAndReportsTheActualRoundedSampleCount() throws Exception {
        RecordingGl gl = new RecordingGl();
        Texture2D texture = texture(3);
        gl.renderer.setTexture(0, texture);
        assertEquals(1, gl.allocations.size());
        assertEquals(3, gl.allocations.get(0).requested);
        assertEquals(4, texture.getImage().getMultiSamples());
        assertEquals(0, gl.mutableMultisampleAllocations);
        assertEquals(0, gl.mutableAllocations);
    }

    @Test
    void unchangedUpdatesAndStateInvalidationPreserveRenderedStorage() throws Exception {
        RecordingGl gl = new RecordingGl();
        Texture2D texture = texture(3);
        gl.renderer.setTexture(0, texture);
        int id = texture.getImage().getId();
        texture.getImage().setUpdateNeeded();
        gl.renderer.setTexture(1, texture);
        // Repeating the original minimum request also fits the existing allocation.
        texture.getImage().setMultiSamples(3);
        texture.getImage().setUpdateNeeded();
        gl.renderer.setTexture(0, texture);
        gl.renderer.invalidateState();
        gl.renderer.setTexture(0, texture);
        assertEquals(id, texture.getImage().getId());
        assertEquals(4, texture.getImage().getMultiSamples());
        assertEquals(1, gl.allocations.size());
        assertTrue(gl.deleted.isEmpty());
    }

    @Test
    void resizingFormatChangesAndSampleChangesAllocateFreshNativeNames() throws Exception {
        RecordingGl gl = new RecordingGl();
        Texture2D texture = texture(4);
        Image image = texture.getImage();
        gl.renderer.setTexture(0, texture);
        int original = image.getId();
        image.setWidth(8);
        gl.renderer.setTexture(0, texture);
        int resized = image.getId();
        image.setFormat(Image.Format.RGB8);
        gl.renderer.setTexture(0, texture);
        int reformatted = image.getId();
        image.setMultiSamples(2);
        image.setUpdateNeeded();
        gl.renderer.setTexture(0, texture);
        assertEquals(4, gl.allocations.size());
        assertEquals(List.of(original, resized, reformatted), gl.deleted);
        assertNotEquals(reformatted, image.getId());
        assertEquals(2, image.getMultiSamples());
    }

    @Test
    void switchingBetweenSingleAndMultisampleStorageRetiresTheOldTextureTarget() throws Exception {
        RecordingGl gl = new RecordingGl();
        Texture2D texture = texture(4);
        Image image = texture.getImage();
        gl.renderer.setTexture(0, texture);
        int multisample = image.getId();
        image.setMultiSamples(1);
        image.setUpdateNeeded();
        gl.renderer.setTexture(0, texture);
        int singleSample = image.getId();
        image.setMultiSamples(4);
        image.setUpdateNeeded();
        gl.renderer.setTexture(0, texture);
        assertEquals(List.of(multisample, singleSample), gl.deleted);
        assertEquals(2, gl.allocations.size());
        assertEquals(1, gl.mutableAllocations);
        assertEquals(0, gl.mutableMultisampleAllocations);
    }

    @Test
    void deletionContextResetAndNativeNameReuseRecreateStorage() throws Exception {
        RecordingGl gl = new RecordingGl();
        Texture2D texture = texture(4);
        gl.renderer.setTexture(0, texture);
        int id = texture.getImage().getId();
        gl.renderer.deleteImage(texture.getImage());
        gl.nextTexture = id; // A driver can immediately reuse a deleted native name.
        gl.renderer.setTexture(0, texture);
        assertEquals(id, texture.getImage().getId());
        gl.renderer.resetGLObjects();
        gl.renderer.setTexture(0, texture);
        assertEquals(3, gl.allocations.size());
        assertFalse(texture.getImage().isUpdateNeeded());
    }

    @Test
    void reallocationInvalidatesFramebufferAttachmentsEvenWhenTheNativeNameIsReused() throws Exception {
        RecordingGl gl = new RecordingGl();
        Texture2D texture = texture(4);
        FrameBuffer framebuffer = new FrameBuffer(4, 4, 4);
        framebuffer.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(texture));
        gl.renderer.setFrameBuffer(framebuffer);
        int original = texture.getImage().getId();
        texture.getImage().setFormat(Image.Format.RGB8);
        gl.nextTexture = original;
        gl.renderer.setTexture(0, texture);
        assertEquals(original, texture.getImage().getId());
        gl.renderer.setFrameBuffer(framebuffer);
        assertEquals(List.of(original, original), gl.attachments);
        assertEquals(2, gl.allocations.size());
    }

    @Test
    void failedAllocationAndInvalidDriverCountsReleaseTheirNativeNamesAndAllowRetry() throws Exception {
        RecordingGl gl = new RecordingGl();
        Texture2D texture = texture(3);
        gl.failAllocation = true;
        assertThrows(RendererException.class, () -> gl.renderer.setTexture(0, texture));
        assertEquals(-1, texture.getImage().getId());
        assertTrue(texture.getImage().isUpdateNeeded());
        gl.failAllocation = false;
        gl.reportedSamples = 2; // Below the requested minimum of three.
        assertThrows(RendererException.class, () -> gl.renderer.setTexture(0, texture));
        assertEquals(-1, texture.getImage().getId());
        gl.reportedSamples = 0;
        gl.renderer.setTexture(0, texture);
        assertEquals(4, texture.getImage().getMultiSamples());
        assertEquals(2, gl.deleted.size());
    }

    @Test
    void validationPrecedesAllocationAndPreservesPreviouslyRenderedStorage() throws Exception {
        RecordingGl gl = new RecordingGl();
        Texture2D texture = texture(4);
        gl.renderer.setTexture(0, texture);
        int id = texture.getImage().getId();
        texture.getImage().setWidth(0);
        assertThrows(RendererException.class, () -> gl.renderer.setTexture(0, texture));
        assertEquals(id, texture.getImage().getId());
        assertTrue(gl.deleted.isEmpty());
        texture.getImage().setWidth(4);
        texture.getImage().setDepth(2);
        assertThrows(RendererException.class, () -> gl.renderer.setTexture(0, texture));
        assertEquals(1, gl.allocations.size());
    }

    @Test
    void unsupportedBackendAndEs30RejectBeforeAnyNativeAllocation() throws Exception {
        RecordingGl gl = new RecordingGl();
        gl.backendSupport = false;
        assertThrows(RendererException.class, () -> gl.renderer.setTexture(0, texture(4)));
        gl.backendSupport = true;
        gl.renderer.getCaps().remove(Caps.OpenGLES31);
        assertThrows(RendererException.class, () -> gl.renderer.setTexture(0, texture(4)));
        assertEquals(100, gl.nextTexture);
        assertTrue(gl.allocations.isEmpty());
    }

    @Test
    void multisampleArraysAndMipmapsAreRejectedBeforeAllocation() throws Exception {
        RecordingGl gl = new RecordingGl();
        Texture2D array = texture(4);
        assertThrows(RendererException.class, () -> gl.renderer.updateTexImageData(
                array.getImage(), Texture.Type.TwoDimensionalArray, 0, false));
        Texture2D mipmapped = texture(4);
        mipmapped.setMinFilter(Texture.MinFilter.Trilinear);
        assertThrows(RendererException.class, () -> gl.renderer.setTexture(0, mipmapped));
        assertEquals(100, gl.nextTexture);
        assertTrue(gl.allocations.isEmpty());
    }

    @Test
    void textureAllocationClampsToItsFormatLimitWithoutRestoringTheRequest() throws Exception {
        RecordingGl gl = new RecordingGl();
        gl.colorSamples = new int[]{4, 2, 1};
        Texture2D texture = texture(32);
        gl.renderer.setTexture(0, texture);
        assertEquals(4, gl.allocations.get(0).requested);
        assertEquals(4, texture.getImage().getMultiSamples());
    }

    @Test
    void negotiationIntersectsActualFormatCountsAndNeverExceedsTheRequest() throws Exception {
        RecordingGl gl = new RecordingGl();
        gl.colorSamples = new int[]{8, 4, 2, 1};
        gl.depthSamples = new int[]{4, 1};
        assertEquals(4, gl.renderer.getPreferredFrameBufferSamples(Image.Format.RGBA8, Image.Format.Depth24, 99));
        assertEquals(1, gl.renderer.getPreferredFrameBufferSamples(Image.Format.RGBA8, Image.Format.Depth24, 3));
        assertEquals(2, gl.renderer.getPreferredFrameBufferSamples(Image.Format.RGBA8, null, 3));
        gl.depthSamples = new int[]{8, 4, 2, 1};
        assertEquals(2, gl.renderer.getPreferredFrameBufferSamples(Image.Format.RGBA8, Image.Format.Depth24, 3));
        assertEquals(4, gl.renderer.getPreferredFrameBufferSamples(null, Image.Format.Depth24, 5));
        gl.depthSamples = new int[0];
        assertEquals(1, gl.renderer.getPreferredFrameBufferSamples(Image.Format.RGBA8, Image.Format.Depth24, 4));
    }

    @Test
    void defaultNegotiationRetainsRenderbufferFallbackAndValidatesArguments() {
        NullRenderer renderer = new NullRenderer();
        renderer.getCaps().clear();
        renderer.getCaps().add(Caps.FrameBufferMultisample);
        renderer.getLimits().put(Limits.FrameBufferSamples, 8);
        assertEquals(4, renderer.getPreferredFrameBufferSamples(Image.Format.RGBA8, Image.Format.Depth24, 7));
        renderer.getCaps().add(Caps.TextureMultisample);
        renderer.getLimits().put(Limits.ColorTextureSamples, 8);
        renderer.getLimits().put(Limits.DepthTextureSamples, 2);
        assertEquals(2, renderer.getPreferredFrameBufferSamples(Image.Format.RGBA8, Image.Format.Depth24, 7));
        assertThrows(IllegalArgumentException.class, () -> renderer.getPreferredFrameBufferSamples(null, null, 4));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.getPreferredFrameBufferSamples(Image.Format.Depth24, null, 4));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.getPreferredFrameBufferSamples(Image.Format.RGBA8, null, 0));
    }

    @Test
    void samplePositionQueriesUseActualFramebufferSamples() throws Exception {
        RecordingGl gl = new RecordingGl();
        FrameBuffer framebuffer = new FrameBuffer(4, 4, 3);
        framebuffer.setId(77);
        framebuffer.clearUpdateNeeded();
        assertEquals(4, gl.renderer.getFrameBufferSamplePositions(framebuffer).length);
        assertEquals(List.of(0, 1, 2, 3), gl.sampleQueries);
    }

    private static Texture2D texture(int samples) {
        Texture2D texture = new Texture2D(4, 4, samples, Image.Format.RGBA8);
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        return texture;
    }

    private static final class Allocation {
        final int requested;
        Allocation(int requested) { this.requested = requested; }
    }

    private static final class RecordingGl implements InvocationHandler {
        final GLRenderer renderer;
        final List<Allocation> allocations = new ArrayList<>();
        final List<Integer> deleted = new ArrayList<>(), sampleQueries = new ArrayList<>();
        final List<Integer> attachments = new ArrayList<>();
        final Map<Integer, Integer> bound = new HashMap<>(), nativeSamples = new HashMap<>();
        int nextTexture = 100, mutableAllocations, mutableMultisampleAllocations, reportedSamples;
        int[] colorSamples = {8, 4, 2, 1}, depthSamples = {8, 4, 2, 1};
        boolean backendSupport = true, failAllocation;

        RecordingGl() throws Exception {
            GL gl = (GL) Proxy.newProxyInstance(GL.class.getClassLoader(),
                    new Class<?>[]{GL.class, GL2.class, GLES_30.class}, this);
            GLExt ext = (GLExt) Proxy.newProxyInstance(GL.class.getClassLoader(), new Class<?>[]{GLExt.class}, this);
            GLFbo fbo = (GLFbo) Proxy.newProxyInstance(GL.class.getClassLoader(), new Class<?>[]{GLFbo.class}, this);
            renderer = new GLRenderer(gl, ext, fbo);
            renderer.getCaps().addAll(List.of(Caps.OpenGLES20, Caps.OpenGLES30, Caps.OpenGLES31,
                    Caps.TextureMultisample, Caps.DepthTexture, Caps.Depth24, Caps.NonPowerOfTwoTextures, Caps.FrameBuffer,
                    Caps.FrameBufferMultisample));
            renderer.getLimits().put(Limits.TextureSize, 4096);
            renderer.getLimits().put(Limits.FrameBufferSamples, 8);
            renderer.getLimits().put(Limits.FrameBufferAttachments, 1);
            renderer.getLimits().put(Limits.ColorTextureSamples, 8);
            renderer.getLimits().put(Limits.DepthTextureSamples, 8);
            Field field = GLRenderer.class.getDeclaredField("texUtil");
            field.setAccessible(true);
            ((TextureUtil) field.get(renderer)).initialize(renderer.getCaps());
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "supportsTextureMultisampleStorage": return backendSupport;
                case "glGenTextures": ((IntBuffer) args[0]).put(0, nextTexture++); break;
                case "glGenFramebuffersEXT": ((IntBuffer) args[0]).put(0, 700); break;
                case "glCheckFramebufferStatusEXT": return GLFbo.GL_FRAMEBUFFER_COMPLETE_EXT;
                case "glFramebufferTexture2DEXT": attachments.add((Integer) args[3]); break;
                case "glDeleteTextures": deleted.add(((IntBuffer) args[0]).get(0)); break;
                case "glBindTexture": bound.put((Integer) args[0], (Integer) args[1]); break;
                case "glTexStorage2DMultisample":
                    if (failAllocation) throw new RendererException("injected allocation failure");
                    int requested = (Integer) args[1];
                    allocations.add(new Allocation(requested));
                    nativeSamples.put(bound.get((Integer) args[0]), Integer.highestOneBit(requested - 1) << 1);
                    break;
                case "glGetTexLevelParameteriv":
                    ((IntBuffer) args[3]).put(0, reportedSamples == 0
                            ? nativeSamples.get(bound.get((Integer) args[0])) : reportedSamples);
                    break;
                case "glGetInternalformativ":
                    int[] counts = (Integer) args[1] == GL2.GL_DEPTH_COMPONENT24 ? depthSamples : colorSamples;
                    IntBuffer result = (IntBuffer) args[3];
                    if ((Integer) args[2] == GLExt.GL_NUM_SAMPLE_COUNTS) result.put(0, counts.length);
                    else for (int i = 0; i < counts.length; i++) result.put(i, counts[i]);
                    break;
                case "glGetInteger":
                    ((IntBuffer) args[1]).put(0, (Integer) args[0] == GLExt.GL_SAMPLES_ARB ? 4 : 0);
                    break;
                case "glGetMultisample":
                    sampleQueries.add((Integer) args[1]);
                    ((FloatBuffer) args[2]).put(0, .25f).put(1, .75f);
                    break;
                case "glTexImage2D": mutableAllocations++; break;
                case "glTexImage2DMultisample": mutableMultisampleAllocations++; break;
                default: break;
            }
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            return null;
        }
    }
}
