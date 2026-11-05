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
import com.jme3.renderer.Limits;
import com.jme3.renderer.RenderContext;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameBufferRenderbufferLifecycleTest {

    @Test
    void deletingBoundRenderbufferInvalidatesBindingBeforeSameIdIsRecreated() throws Exception {
        RecordingGl gl = new RecordingGl();
        GLRenderer renderer = gl.renderer();
        renderer.getCaps().add(Caps.FrameBuffer);
        renderer.getCaps().add(Caps.OpenGL32);
        renderer.getLimits().put(Limits.RenderBufferSize, 256);
        textureUtil(renderer).initialize(renderer.getCaps());

        FrameBuffer first = framebuffer();
        renderer.updateFrameBufferAttachment(first, first.getColorTarget());
        int deletedId = first.getColorTarget().getId();
        assertEquals(19, deletedId);
        assertTrue(gl.events.indexOf("bind-renderbuffer:19")
                < gl.events.indexOf("storage-renderbuffer"));

        RenderContext context = context(renderer);
        context.boundFBO = first.getId();
        context.boundFB = first;
        context.boundRB = deletedId;
        renderer.deleteFrameBuffer(first);
        assertEquals(0, context.boundRB);

        FrameBuffer second = framebuffer();
        renderer.updateFrameBufferAttachment(second, second.getColorTarget());
        assertEquals(deletedId, second.getColorTarget().getId());

        int deleteIndex = gl.events.indexOf("delete-renderbuffer:19");
        int secondBindIndex = gl.events.lastIndexOf("bind-renderbuffer:19");
        int secondStorageIndex = gl.events.lastIndexOf("storage-renderbuffer");
        int secondAttachIndex = gl.events.lastIndexOf("attach-renderbuffer:19");
        assertTrue(deleteIndex >= 0);
        assertTrue(secondBindIndex > deleteIndex);
        assertTrue(secondStorageIndex > secondBindIndex);
        assertTrue(secondAttachIndex > secondStorageIndex);
    }

    private static FrameBuffer framebuffer() {
        FrameBuffer result = new FrameBuffer(8, 8, 1);
        result.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA8));
        return result;
    }

    private static TextureUtil textureUtil(GLRenderer renderer) throws Exception {
        Field field = GLRenderer.class.getDeclaredField("texUtil");
        field.setAccessible(true);
        return (TextureUtil) field.get(renderer);
    }

    private static RenderContext context(GLRenderer renderer) throws Exception {
        Field field = GLRenderer.class.getDeclaredField("context");
        field.setAccessible(true);
        return (RenderContext) field.get(renderer);
    }

    private static final class RecordingGl implements InvocationHandler {
        final List<String> events = new ArrayList<>();
        int nextFrameBuffer = 31;

        GLRenderer renderer() {
            GL gl = (GL) Proxy.newProxyInstance(GL.class.getClassLoader(),
                    new Class<?>[]{GL.class}, this);
            GLExt extension = (GLExt) Proxy.newProxyInstance(GLExt.class.getClassLoader(),
                    new Class<?>[]{GLExt.class}, this);
            GLFbo fbo = (GLFbo) Proxy.newProxyInstance(GLFbo.class.getClassLoader(),
                    new Class<?>[]{GLFbo.class}, this);
            return new GLRenderer(gl, extension, fbo);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            switch (method.getName()) {
                case "glGenFramebuffersEXT":
                    ((IntBuffer) args[0]).put(0, nextFrameBuffer++);
                    return null;
                case "glGenRenderbuffersEXT":
                    ((IntBuffer) args[0]).put(0, 19);
                    events.add("gen-renderbuffer:19");
                    return null;
                case "glBindRenderbufferEXT":
                    events.add("bind-renderbuffer:" + args[1]);
                    return null;
                case "glRenderbufferStorageEXT":
                case "glRenderbufferStorageMultisampleEXT":
                    events.add("storage-renderbuffer");
                    return null;
                case "glFramebufferRenderbufferEXT":
                    events.add("attach-renderbuffer:" + args[3]);
                    return null;
                case "glDeleteRenderbuffersEXT":
                    events.add("delete-renderbuffer:" + ((IntBuffer) args[0]).get(0));
                    return null;
                case "glBindFramebufferEXT":
                    events.add("bind-framebuffer:" + args[1]);
                    return null;
                case "glDeleteFramebuffersEXT":
                    events.add("delete-framebuffer:" + ((IntBuffer) args[0]).get(0));
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
