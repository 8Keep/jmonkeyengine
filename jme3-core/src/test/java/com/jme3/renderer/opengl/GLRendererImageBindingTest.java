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
import com.jme3.renderer.MemoryBarrier;
import java.util.EnumSet;
import com.jme3.renderer.RendererException;
import com.jme3.renderer.TextureUnitException;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.TextureImage;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GLRendererImageBindingTest {
    @Test
    void glesAllocatesImmutableStorageAndBindsThroughTheComputeInterface() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        Texture2D texture = texture();
        gl.renderer.setTextureImage(0, new TextureImage(texture, TextureImage.Access.WriteOnly));
        assertEquals(1, gl.storageAllocations);
        assertEquals(0, gl.mutableAllocations);
        assertEquals(1, gl.bindings.size());
        assertEquals(texture.getImage().getId(), gl.bindings.get(0).id);
        assertEquals(TextureImage.Access.WriteOnly.getGlEnum(), gl.bindings.get(0).access);
    }

    @Test
    void imageUnitsUseTheirOwnLimitAndDoNotUseSamplerIndices() throws Exception {
        RecordingGl gl = new RecordingGl(false);
        gl.imageUnits = 64;
        gl.renderer.setTextureImage(63, new TextureImage(uploaded(7)));
        assertEquals(63, gl.bindings.get(0).unit);
        assertTrue(gl.activeTextureUnits.stream().allMatch(unit -> unit == GL.GL_TEXTURE0));
        assertThrows(TextureUnitException.class, () -> gl.renderer.setTextureImage(64, new TextureImage(uploaded(8))));
        assertEquals(1, gl.bindings.size());
    }

    @Test
    void interveningSamplerBindDoesNotReplaceTheImageBindingSnapshot() throws Exception {
        RecordingGl gl = new RecordingGl(false);
        Texture2D a = uploaded(7), b = uploaded(8);
        TextureImage aView = new TextureImage(a), bView = new TextureImage(b);
        gl.renderer.setTextureImage(0, aView);
        gl.renderer.setTexture(0, b);
        gl.renderer.setTextureImage(0, aView);
        assertEquals(1, gl.bindings.size());
        gl.renderer.setTextureImage(0, bView);
        gl.renderer.setTexture(0, a);
        gl.renderer.setTextureImage(0, aView);
        assertEquals(List.of(7, 8, 7), gl.bindings.stream().map(binding -> binding.id).toList());
    }

    @Test
    void distinctViewsOfTheSameImageRebindLevelLayerAndAccess() throws Exception {
        RecordingGl gl = new RecordingGl(false);
        Texture2D texture = uploaded(7);
        TextureImage first = new TextureImage(texture, 0, -1, TextureImage.Access.WriteOnly);
        TextureImage second = new TextureImage(texture, 1, 2, TextureImage.Access.ReadOnly);
        second.clearUpdateNeeded(); // Another unit may already have consumed its update flag.
        gl.renderer.setTextureImage(0, first);
        gl.renderer.setTextureImage(0, second);
        gl.renderer.setTextureImage(0, first);
        assertEquals(3, gl.bindings.size());
        Binding middle = gl.bindings.get(1);
        assertEquals(1, middle.level);
        assertEquals(2, middle.layer);
        assertFalse(middle.layered);
        assertEquals(TextureImage.Access.ReadOnly.getGlEnum(), middle.access);
    }

    @Test
    void aMutatedViewRebindsEveryImageUnitEvenAfterItsUpdateFlagIsConsumed() throws Exception {
        RecordingGl gl = new RecordingGl(false);
        TextureImage view = new TextureImage(uploaded(7));
        gl.renderer.setTextureImage(0, view);
        gl.renderer.setTextureImage(1, view);
        view.setLevel(1);
        view.setAccess(TextureImage.Access.ReadOnly);
        gl.renderer.setTextureImage(0, view);
        gl.renderer.setTextureImage(1, view);
        assertEquals(4, gl.bindings.size());
        assertEquals(1, gl.bindings.get(3).level);
        assertEquals(TextureImage.Access.ReadOnly.getGlEnum(), gl.bindings.get(3).access);
    }

    @Test
    void invalidationForcesImageRebindingWithoutReallocatingStorage() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        TextureImage view = new TextureImage(texture());
        gl.renderer.setTextureImage(0, view);
        gl.renderer.invalidateState();
        gl.renderer.setTextureImage(0, view);
        assertEquals(2, gl.bindings.size());
        assertEquals(1, gl.storageAllocations);
    }

    @Test
    void deletionAndContextLossRecreateStorageAndRebindTheView() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        TextureImage view = new TextureImage(texture());
        gl.renderer.setTextureImage(0, view);
        gl.renderer.deleteImage(view.getImage());
        gl.renderer.setTextureImage(0, view);
        gl.renderer.resetGLObjects();
        gl.renderer.setTextureImage(0, view);
        assertEquals(3, gl.storageAllocations);
        assertEquals(3, gl.bindings.size());
    }

    @Test
    void initialCpuMipUploadPreservesSourceRangeAndUpdatesUseSubImages() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        ByteBuffer pixels = BufferUtils.createByteBuffer(84);
        pixels.position(5).limit(7);
        Image image = new Image(Image.Format.RGBA8, 4, 4, pixels, ColorSpace.Linear);
        image.setMipMapSizes(new int[]{64, 16, 4});
        Texture2D texture = new Texture2D(image);
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        TextureImage view = new TextureImage(texture);
        gl.renderer.setTextureImage(0, view);
        pixels.put(5, (byte) 91); // Mutates Image.hashCode; storage must remain keyed by identity.
        image.setUpdateNeeded();
        gl.renderer.setTexture(0, texture);
        assertEquals(List.of(0, 1, 2, 0, 1, 2), gl.subImageLevels);
        assertEquals(5, pixels.position());
        assertEquals(7, pixels.limit());
        assertEquals(1, gl.storageAllocations);
        assertEquals(0, gl.mutableAllocations);
    }

    @Test
    void glesRejectsAnAlreadyUploadedMutableTextureWithoutReplacingItsContents() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        Texture2D texture = uploaded(7);
        assertThrows(RendererException.class, () -> gl.renderer.setTextureImage(0, new TextureImage(texture)));
        assertEquals(7, texture.getImage().getId());
        assertEquals(0, gl.storageAllocations);
        assertTrue(gl.bindings.isEmpty());
    }

    @Test
    void glesRejectsUnsupportedFormatsAndStorageResizing() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        Texture2D rgb = new Texture2D(4, 4, Image.Format.RGB8);
        assertThrows(RendererException.class, () -> gl.renderer.setTextureImage(0, new TextureImage(rgb)));
        assertEquals(-1, rgb.getImage().getId());
        Texture2D texture = texture();
        gl.renderer.setTextureImage(0, new TextureImage(texture));
        texture.getImage().setWidth(16);
        texture.getImage().setUpdateNeeded();
        assertThrows(RendererException.class, () -> gl.renderer.setTexture(0, texture));
        assertEquals(1, gl.storageAllocations);
    }

    @Test
    void unsupportedContextsFailBeforeUploadingOrClearingTheViewFlag() throws Exception {
        RecordingGl gl = new RecordingGl(false);
        gl.renderer.getCaps().remove(Caps.OpenGL42);
        TextureImage view = new TextureImage(texture());
        assertThrows(RendererException.class, () -> gl.renderer.setTextureImage(0, view));
        assertTrue(view.isUpdateNeeded());
        assertEquals(-1, view.getImageId());
        assertTrue(gl.bindings.isEmpty());
    }

    @Test
    void oldComputeBindingsDefaultToAnExplicitUnsupportedFailure() {
        GLCompute binding = new GLCompute() {
            @Override public void glDispatchCompute(int x, int y, int z) { }
            @Override public void glMemoryBarrier(int barriers) { }
        };
        assertThrows(UnsupportedOperationException.class,
                () -> binding.glBindImageTexture(0, 1, 0, false, 0, GL2.GL_READ_ONLY, GLExt.GL_RGBA8));
        assertThrows(UnsupportedOperationException.class,
                () -> binding.glTexStorage2D(GL.GL_TEXTURE_2D, 1, GLExt.GL_RGBA8, 1, 1));
    }

    @Test
    void imageConsumerBarrierUsesTheImageAccessBit() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        gl.renderer.getCaps().add(Caps.ComputeShader);
        gl.renderer.memoryBarrier(EnumSet.of(MemoryBarrier.ShaderImageAccess, MemoryBarrier.TextureFetch));
        assertEquals(GLCompute.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GLCompute.GL_TEXTURE_FETCH_BARRIER_BIT,
                gl.barriers);
    }

    @Test
    void uploadingAnImagePreservesTheNativeInputSamplerAndActiveUnit() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        Texture2D input = uploaded(7);
        gl.renderer.setTexture(0, input);
        gl.renderer.setTexture(3, uploaded(8));
        int previousUnit = gl.activeUnit;
        TextureImage output = new TextureImage(texture());
        gl.renderer.setTextureImage(0, output);
        assertEquals(7, gl.samplerBindings.get(GL.GL_TEXTURE0 + ":" + GL.GL_TEXTURE_2D));
        assertEquals(previousUnit, gl.activeUnit);
        int samplerCalls = gl.samplerBindCount;
        gl.renderer.setTextureImage(0, output);
        assertEquals(samplerCalls, gl.samplerBindCount);
        gl.renderer.setTexture(0, input);
        assertEquals(samplerCalls, gl.samplerBindCount); // The sampler cache was restored too.
    }

    @Test
    void distinctImagesWithEqualContentsHaveIndependentImmutableStorage() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        Texture2D first = texture(), second = texture();
        assertEquals(first.getImage(), second.getImage());
        gl.renderer.setTextureImage(0, new TextureImage(first));
        gl.renderer.setTextureImage(1, new TextureImage(second));
        assertEquals(2, gl.storageAllocations);
        assertNotEquals(first.getImage().getId(), second.getImage().getId());
    }

    @Test
    void invalidInitialDimensionsCanBeCorrectedAndRetriedWithoutDeletingAnUnallocatedImage() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        Texture2D texture = new Texture2D(0, 4, Image.Format.RGBA8);
        TextureImage view = new TextureImage(texture);
        assertThrows(RendererException.class, () -> gl.renderer.setTextureImage(0, view));
        assertEquals(-1, texture.getImage().getId());
        assertTrue(view.isUpdateNeeded());
        texture.getImage().setWidth(4);
        gl.renderer.setTextureImage(0, view);
        assertEquals(1, gl.storageAllocations);
        assertEquals(1, gl.bindings.size());
    }

    @Test
    void desktopRetainsVirtualDispatchToTheLegacyGl4BindingMethod() throws Exception {
        RecordingGl gl = new RecordingGl(false);
        int[] legacyCalls = {0};
        TextureImage view = new TextureImage(uploaded(7)) {
            @Override
            public void bindImage(GL4 binding, TextureUtil formats, int unit) {
                legacyCalls[0]++;
                super.bindImage(binding, formats, unit);
            }
        };
        gl.renderer.setTextureImage(0, view);
        assertEquals(1, legacyCalls[0]);
        assertEquals(1, gl.bindings.size());
    }

    @Test
    void glesRejectsVolumeAndLayerDataDisguisedAsTwoDimensionalTextures() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        Texture2D volume = texture();
        volume.getImage().setDepth(2);
        TextureImage volumeView = new TextureImage(volume);
        assertThrows(RendererException.class, () -> gl.renderer.setTextureImage(0, volumeView));
        assertTrue(volumeView.isUpdateNeeded());
        assertEquals(-1, volume.getImage().getId());

        Texture2D layers = texture();
        layers.getImage().addData(null);
        layers.getImage().addData(null);
        TextureImage layerView = new TextureImage(layers);
        assertThrows(RendererException.class, () -> gl.renderer.setTextureImage(0, layerView));
        assertTrue(layerView.isUpdateNeeded());
        assertEquals(-1, layers.getImage().getId());
        assertEquals(0, gl.storageAllocations);
    }

    @Test
    void immutableImageUpdatesRejectAdditionalDepthOrLayersBeforeUploading() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        Texture2D texture = texture();
        gl.renderer.setTextureImage(0, new TextureImage(texture));
        texture.getImage().setDepth(2);
        assertThrows(RendererException.class, () -> gl.renderer.setTexture(0, texture));
        texture.getImage().setDepth(1);
        texture.getImage().addData(null);
        texture.getImage().addData(null);
        assertThrows(RendererException.class, () -> gl.renderer.setTexture(0, texture));
        assertEquals(1, gl.storageAllocations);
        assertEquals(0, gl.mutableAllocations);
        assertTrue(gl.subImageLevels.isEmpty());
    }

    @Test
    void emptyAndMissingDataListsRemainValidGpuOnlyTwoDimensionalImages() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        for (boolean missing : new boolean[]{false, true}) {
            Texture2D texture = texture();
            texture.getImage().setData(missing ? null : new ArrayList<ByteBuffer>());
            texture.setMinFilter(Texture.MinFilter.NearestNearestMipMap);
            gl.renderer.setTextureImage(0, new TextureImage(texture));
            gl.renderer.setTexture(0, texture);
            assertFalse(texture.getImage().isUpdateNeeded());
        }
        assertEquals(2, gl.storageAllocations);
        assertEquals(0, gl.mutableAllocations);
        assertTrue(gl.subImageLevels.isEmpty());
    }

    @Test
    void imageUploadEstablishesTheScratchUnitWhenNativeActiveStateDiffersFromTheCache() throws Exception {
        RecordingGl gl = new RecordingGl(true);
        gl.renderer.setTexture(0, uploaded(7));
        gl.renderer.setTexture(3, uploaded(8));
        gl.renderer.invalidateState();
        // Simulate native state changed independently of the invalidated Java cache.
        gl.activeUnit = GL.GL_TEXTURE0 + 3;
        gl.renderer.setTextureImage(0, new TextureImage(texture()));
        assertEquals(7, gl.samplerBindings.get(GL.GL_TEXTURE0 + ":" + GL.GL_TEXTURE_2D));
        assertEquals(8, gl.samplerBindings.get((GL.GL_TEXTURE0 + 3) + ":" + GL.GL_TEXTURE_2D));
        assertEquals(GL.GL_TEXTURE0, gl.activeUnit);
        assertEquals(1, gl.storageAllocations);
    }

    private static Texture2D texture() {
        return new Texture2D(4, 4, Image.Format.RGBA8);
    }

    private static Texture2D uploaded(int id) {
        Texture2D texture = texture();
        texture.getImage().setId(id);
        texture.getImage().clearUpdateNeeded();
        return texture;
    }

    private static final class Binding {
        final int unit, id, level, layer, access;
        final boolean layered;
        Binding(Object[] arguments) {
            unit = (Integer) arguments[0];
            id = (Integer) arguments[1];
            level = (Integer) arguments[2];
            layered = (Boolean) arguments[3];
            layer = (Integer) arguments[4];
            access = (Integer) arguments[5];
        }
    }

    private static final class RecordingGl implements InvocationHandler {
        final GLRenderer renderer;
        final List<Binding> bindings = new ArrayList<>();
        final List<Integer> activeTextureUnits = new ArrayList<>();
        final List<Integer> subImageLevels = new ArrayList<>();
        final Map<String, Integer> samplerBindings = new HashMap<>();
        int activeUnit = GL.GL_TEXTURE0, samplerBindCount;
        int imageUnits = 8, nextId = 100, storageAllocations, mutableAllocations, barriers;

        RecordingGl(boolean gles) throws Exception {
            Class<?>[] interfaces = gles ? new Class<?>[]{GL.class, GL2.class, GLES_30.class, GLCompute.class}
                    : new Class<?>[]{GL4.class};
            GL gl = (GL) Proxy.newProxyInstance(GL.class.getClassLoader(), interfaces, this);
            GLExt ext = (GLExt) Proxy.newProxyInstance(GL.class.getClassLoader(), new Class<?>[]{GLExt.class}, this);
            GLFbo fbo = (GLFbo) Proxy.newProxyInstance(GL.class.getClassLoader(), new Class<?>[]{GLFbo.class}, this);
            renderer = new GLRenderer(gl, ext, fbo);
            renderer.getCaps().add(Caps.OpenGL20);
            renderer.getCaps().add(Caps.NonPowerOfTwoTextures);
            if (gles) {
                renderer.getCaps().remove(Caps.OpenGL20);
                renderer.getCaps().add(Caps.OpenGLES20);
                renderer.getCaps().add(Caps.OpenGLES30);
                renderer.getCaps().add(Caps.OpenGLES31);
            } else {
                renderer.getCaps().add(Caps.OpenGL42);
            }
            renderer.getLimits().put(Limits.TextureSize, 4096);
            Field field = GLRenderer.class.getDeclaredField("texUtil");
            field.setAccessible(true);
            ((TextureUtil) field.get(renderer)).initialize(renderer.getCaps());
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            switch (method.getName()) {
                case "glGetInteger":
                    int query = (Integer) arguments[0];
                    int value = query == GLCompute.GL_MAX_IMAGE_UNITS ? imageUnits
                            : query == GL.GL_TEXTURE_BINDING_2D
                                    ? samplerBindings.getOrDefault(activeUnit + ":" + GL.GL_TEXTURE_2D, 0) : 0;
                    ((IntBuffer) arguments[1]).put(0, value);
                    break;
                case "glGenTextures": ((IntBuffer) arguments[0]).put(0, nextId++); break;
                case "glBindImageTexture": bindings.add(new Binding(arguments)); break;
                case "glMemoryBarrier": barriers = (Integer) arguments[0]; break;
                case "glTexStorage2D": storageAllocations++; break;
                case "glTexImage2D": mutableAllocations++; break;
                case "glTexSubImage2D": subImageLevels.add((Integer) arguments[1]); break;
                case "glActiveTexture":
                    activeUnit = (Integer) arguments[0];
                    activeTextureUnits.add(activeUnit);
                    break;
                case "glBindTexture":
                    samplerBindCount++;
                    samplerBindings.put(activeUnit + ":" + arguments[0], (Integer) arguments[1]);
                    break;
                default: break;
            }
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            return null;
        }
    }
}
