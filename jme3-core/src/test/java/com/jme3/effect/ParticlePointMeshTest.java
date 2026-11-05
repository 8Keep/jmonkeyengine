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
package com.jme3.effect;

import com.jme3.math.Matrix3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.VertexBuffer;
import java.nio.FloatBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticlePointMeshTest {

    @Test
    void defaultTexCoordsBecomeStaticButAtlasChangesStillUpdateThem() {
        ParticleEmitter emitter = new ParticleEmitter("emitter", ParticleMesh.Type.Point, 2);
        Particle[] particles = emitter.getParticles();
        particles[0].imageIndex = 0;
        particles[1].imageIndex = 0;
        ParticlePointMesh mesh = (ParticlePointMesh) emitter.getMesh();
        VertexBuffer texCoordBuffer = mesh.getBuffer(VertexBuffer.Type.TexCoord);

        mesh.updateParticleData(particles, new Camera(1, 1), Matrix3f.IDENTITY);
        assertTexCoords(texCoordBuffer, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f);

        texCoordBuffer.clearUpdateNeeded();
        mesh.updateParticleData(particles, new Camera(1, 1), Matrix3f.IDENTITY);
        assertFalse(texCoordBuffer.isUpdateNeeded());

        particles[1].imageIndex = 3;
        emitter.setImagesX(2);
        emitter.setImagesY(2);
        texCoordBuffer.clearUpdateNeeded();
        mesh.updateParticleData(particles, new Camera(1, 1), Matrix3f.IDENTITY);
        assertTrue(texCoordBuffer.isUpdateNeeded());
        assertTexCoords(texCoordBuffer, 0f, 0f, 0.5f, 0.5f, 0.5f, 0.5f, 1f, 1f);

        emitter.setImagesX(1);
        emitter.setImagesY(1);
        texCoordBuffer.clearUpdateNeeded();
        mesh.updateParticleData(particles, new Camera(1, 1), Matrix3f.IDENTITY);
        assertTrue(texCoordBuffer.isUpdateNeeded());
        assertTexCoords(texCoordBuffer, 0f, 0f, 1f, 1f, 0f, 3f, 1f, 4f);

        particles[1].imageIndex = 0;
        texCoordBuffer.clearUpdateNeeded();
        mesh.updateParticleData(particles, new Camera(1, 1), Matrix3f.IDENTITY);
        assertTrue(texCoordBuffer.isUpdateNeeded());
        assertTexCoords(texCoordBuffer, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f);
    }

    @Test
    void lateAndNegativeIndicesInvalidateAndRestoreTheWholeDefaultBuffer() {
        ParticleEmitter emitter = new ParticleEmitter("emitter", ParticleMesh.Type.Point, 4);
        ParticlePointMesh mesh = (ParticlePointMesh) emitter.getMesh();
        Particle[] particles = emitter.getParticles();
        VertexBuffer buffer = mesh.getBuffer(VertexBuffer.Type.TexCoord);
        Camera camera = new Camera(1, 1);
        mesh.updateParticleData(particles, camera, Matrix3f.IDENTITY);
        for (int image : new int[]{3, 0, -1, 0, 2, 0}) {
            particles[3].imageIndex = image;
            buffer.clearUpdateNeeded();
            mesh.updateParticleData(particles, camera, Matrix3f.IDENTITY);
            assertTrue(buffer.isUpdateNeeded());
            assertLegacyTexCoords(mesh, particles, 1, 1);
            buffer.clearUpdateNeeded();
            mesh.updateParticleData(particles, camera, Matrix3f.IDENTITY);
            assertEquals(image != 0, buffer.isUpdateNeeded());
            assertLegacyTexCoords(mesh, particles, 1, 1);
        }
    }

    @Test
    void resizingReinitializingAndNonSquareAtlasChangesInvalidateTheCache() {
        ParticleEmitter emitter = new ParticleEmitter("emitter", ParticleMesh.Type.Point, 2);
        ParticlePointMesh mesh = (ParticlePointMesh) emitter.getMesh();
        Camera camera = new Camera(1, 1);
        for (int count : new int[]{2, 5, 1, 0, 3}) {
            emitter.setNumParticles(count);
            Particle[] particles = emitter.getParticles();
            mesh.updateParticleData(particles, camera, Matrix3f.IDENTITY);
            assertLegacyTexCoords(mesh, particles, 1, 1);
            if (count > 0) {
                particles[count - 1].imageIndex = 5;
            }
            emitter.setImagesX(3);
            emitter.setImagesY(2);
            mesh.updateParticleData(particles, camera, Matrix3f.IDENTITY);
            assertLegacyTexCoords(mesh, particles, 3, 2);
            emitter.setImagesX(1);
            emitter.setImagesY(1);
            for (Particle particle : particles) {
                particle.imageIndex = 0;
            }
            mesh.updateParticleData(particles, camera, Matrix3f.IDENTITY);
            assertLegacyTexCoords(mesh, particles, 1, 1);
            mesh.getBuffer(VertexBuffer.Type.TexCoord).clearUpdateNeeded();
            mesh.updateParticleData(particles, camera, Matrix3f.IDENTITY);
            assertFalse(mesh.getBuffer(VertexBuffer.Type.TexCoord).isUpdateNeeded());
        }
    }

    @Test
    void positionColorAndSizeUpdatesDoNotInvalidateDefaultTexCoords() {
        ParticleEmitter emitter = new ParticleEmitter("emitter", ParticleMesh.Type.Point, 2);
        ParticlePointMesh mesh = (ParticlePointMesh) emitter.getMesh();
        Particle[] particles = emitter.getParticles();
        Camera camera = new Camera(1, 1);
        mesh.updateParticleData(particles, camera, Matrix3f.IDENTITY);
        VertexBuffer texcoords = mesh.getBuffer(VertexBuffer.Type.TexCoord);
        texcoords.clearUpdateNeeded();
        particles[0].position.set(3, -5, 7);
        particles[0].size = 2.5f;
        particles[0].color.set(1, .5f, .25f, 1);
        emitter.setLocalScale(2);
        mesh.updateParticleData(particles, camera, Matrix3f.IDENTITY);
        assertFalse(texcoords.isUpdateNeeded());
        FloatBuffer position = mesh.getFloatBuffer(VertexBuffer.Type.Position);
        assertEquals(3f, position.get(0), 0f);
        assertEquals(-5f, position.get(1), 0f);
        assertEquals(7f, position.get(2), 0f);
        assertEquals(5f, mesh.getFloatBuffer(VertexBuffer.Type.Size).get(0), 0f);
        java.nio.ByteBuffer colors = (java.nio.ByteBuffer) mesh.getBuffer(VertexBuffer.Type.Color).getData();
        assertEquals(particles[0].color.asIntABGR(), colors.getInt(0));
        assertLegacyTexCoords(mesh, particles, 1, 1);
    }

    @Test
    void varyingParticleArrayLengthRefreshesTheTextureBufferLimit() {
        ParticleEmitter emitter = new ParticleEmitter("emitter", ParticleMesh.Type.Point, 4);
        ParticlePointMesh mesh = (ParticlePointMesh) emitter.getMesh();
        Particle[] particles = emitter.getParticles();
        Camera camera = new Camera(1, 1);
        for (int count : new int[]{4, 2, 1, 0}) {
            Particle[] active = java.util.Arrays.copyOf(particles, count);
            mesh.updateParticleData(active, camera, Matrix3f.IDENTITY);
            assertLegacyTexCoords(mesh, active, 1, 1);
        }
    }

    @Test
    void replacingTheTextureBufferInvalidatesCachedCoordinates() {
        ParticleEmitter emitter = new ParticleEmitter("emitter", ParticleMesh.Type.Point, 2);
        ParticlePointMesh mesh = (ParticlePointMesh) emitter.getMesh();
        Particle[] particles = emitter.getParticles();
        Camera camera = new Camera(1, 1);
        mesh.updateParticleData(particles, camera, Matrix3f.IDENTITY);
        mesh.setBuffer(VertexBuffer.Type.TexCoord, 4,
                com.jme3.util.BufferUtils.createFloatBuffer(8));
        mesh.updateParticleData(particles, camera, Matrix3f.IDENTITY);
        assertLegacyTexCoords(mesh, particles, 1, 1);
    }

    private static void assertLegacyTexCoords(ParticlePointMesh mesh, Particle[] particles,
            int imagesX, int imagesY) {
        FloatBuffer actual = mesh.getFloatBuffer(VertexBuffer.Type.TexCoord);
        assertEquals(particles.length * 4, actual.limit());
        for (int i = 0; i < particles.length; i++) {
            int image = particles[i].imageIndex;
            float x = ((float) (image % imagesX)) / imagesX;
            float y = ((float) (image / imagesX)) / imagesY;
            assertEquals(x, actual.get(i * 4), 0f);
            assertEquals(y, actual.get(i * 4 + 1), 0f);
            assertEquals(x + 1f / imagesX, actual.get(i * 4 + 2), 0f);
            assertEquals(y + 1f / imagesY, actual.get(i * 4 + 3), 0f);
        }
    }

    private static void assertTexCoords(VertexBuffer buffer, float... expected) {
        FloatBuffer actual = (FloatBuffer) buffer.getData();
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual.get(index), 0f);
        }
    }
}
