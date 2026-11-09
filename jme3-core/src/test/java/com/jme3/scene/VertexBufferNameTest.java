/*
 * Copyright (c) 2026 jMonkeyEngine
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
package com.jme3.scene;

import com.jme3.export.binary.BinaryExporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Contracts for generated debug labels and explicitly named buffers. */
class VertexBufferNameTest {
    @Test void repeatedDefaultsDoNotAllocateAndCustomNamesRemainIndependent() {
        VertexBuffer source = buffer();
        String generated = source.getName();
        assertEquals("VertexBuffer(Position)", generated);
        assertSame(generated, source.getName());
        source.setName("positions");
        assertEquals("positions", source.getName());
        VertexBuffer clone = source.clone();
        clone.setName(null);
        assertEquals("positions", source.getName());
        assertEquals(generated, clone.getName());
        source.setName(null);
        assertSame(generated, source.getName());
        VertexBuffer normals = source.clone(VertexBuffer.Type.Normal);
        assertEquals("VertexBuffer(Normal)", normals.getName());
        assertSame(normals.getName(), normals.getName());
    }

    @Test void derivedTypesAndChangedBufferTypesKeepTheirLabels() {
        TypedBuffer buffer = new TypedBuffer();
        assertEquals("TypedBuffer(Position)", buffer.getName());
        buffer.bufType = VertexBuffer.Type.Normal;
        assertEquals("TypedBuffer(Normal)", buffer.getName());
        assertSame(buffer.getName(), buffer.getName());
    }

    @Test void readingTheDefaultDoesNotChangeSerializedState() throws IOException {
        VertexBuffer source = buffer();
        byte[] before = save(source);
        source.getName();
        assertArrayEquals(before, save(source));
        source.setName("named attribute");
        VertexBuffer restored = BinaryExporter.saveAndLoad(null, source);
        assertEquals("named attribute", restored.getName());
        restored.setName(null);
        assertEquals("VertexBuffer(Position)", restored.getName());
        assertSame(restored.getName(), restored.getName());
    }

    private static VertexBuffer buffer() {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Type.Position);
        buffer.setupData(VertexBuffer.Usage.Static, 3, VertexBuffer.Format.Float,
                FloatBuffer.wrap(new float[3]));
        return buffer;
    }

    private static byte[] save(VertexBuffer buffer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BinaryExporter().save(buffer, bytes);
        return bytes.toByteArray();
    }

    private static final class TypedBuffer extends VertexBuffer {
        TypedBuffer() { super(Type.Position); }
    }
}
