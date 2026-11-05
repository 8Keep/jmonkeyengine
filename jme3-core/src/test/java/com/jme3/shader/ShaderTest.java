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
package com.jme3.shader;

import com.jme3.scene.VertexBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ShaderTest {


    @Test
    void resetAndSourceChangesKeepTheSameAttributeObjects() {
        Shader shader = new Shader();
        Attribute position = shader.getAttribute(VertexBuffer.Type.Position);
        position.setLocation(-1); // A linker may report an absent input.
        shader.setUpdateNeeded();
        assertEquals(ShaderVariable.LOC_UNKNOWN, position.getLocation());
        assertSame(position, shader.getAttribute(VertexBuffer.Type.Position));
        position.setLocation(19);
        shader.addSource(Shader.ShaderType.Vertex, "test", "void main(){}", "", "GLSL100");
        assertEquals(ShaderVariable.LOC_UNKNOWN, position.getLocation());
        Attribute normal = shader.getAttribute(VertexBuffer.Type.Normal);
        normal.setLocation(7);
        position.setLocation(8);
        shader.setId(23);
        shader.resetObject();
        assertEquals(-1, shader.getId());
        assertEquals(ShaderVariable.LOC_UNKNOWN, position.getLocation());
        assertEquals(ShaderVariable.LOC_UNKNOWN, normal.getLocation());
        assertSame(position, shader.getAttribute(VertexBuffer.Type.Position));
        assertSame(normal, shader.getAttribute(VertexBuffer.Type.Normal));
    }

    @Test
    void shadersAndDestructibleClonesDoNotShareAttributeState() {
        Shader first = new Shader();
        Shader second = new Shader();
        Attribute attribute = first.getAttribute(VertexBuffer.Type.Position);
        attribute.setLocation(17);
        org.junit.jupiter.api.Assertions.assertNotSame(attribute, second.getAttribute(VertexBuffer.Type.Position));
        first.setId(37);
        first.addSource(Shader.ShaderType.Vertex, "test", "void main(){}", "", "GLSL100");
        attribute.setLocation(17);
        Shader clone = (Shader) first.createDestructableClone();
        assertEquals(37, clone.getId());
        clone.resetLocations();
        clone.resetObject();
        assertEquals(17, attribute.getLocation());
        assertSame(attribute, first.getAttribute(VertexBuffer.Type.Position));
    }

    @Test
    void attributeLookupsReuseAllOrdinalEntriesAndResetLocations() {
        Shader shader = new Shader();
        Attribute[] attributes = new Attribute[VertexBuffer.Type.values().length];

        for (VertexBuffer.Type type : VertexBuffer.Type.values()) {
            Attribute attribute = shader.getAttribute(type);
            attributes[type.ordinal()] = attribute;
            attribute.setLocation(type.ordinal());
            assertEquals(type.name(), attribute.getName());
            assertSame(attribute, shader.getAttribute(type));
        }

        shader.resetLocations();

        for (VertexBuffer.Type type : VertexBuffer.Type.values()) {
            Attribute attribute = attributes[type.ordinal()];
            assertEquals(ShaderVariable.LOC_UNKNOWN, attribute.getLocation());
            assertSame(attribute, shader.getAttribute(type));
        }
    }
}
