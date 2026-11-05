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
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** Benchmarks steady-state point-particle buffer updates with and without an atlas. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
@State(Scope.Thread)
public class ParticlePointMeshBenchmark {

    @Param({"1", "64", "1024"})
    public int particleCount;

    @Param({"single", "atlas", "changing"})
    public String layout;

    private ParticlePointMesh mesh;
    private Particle[] particles;
    private VertexBuffer texcoords;
    private boolean alternate;
    private final Camera camera = new Camera(1, 1);

    @Setup
    public void setup() {
        ParticleEmitter emitter = new ParticleEmitter("emitter", ParticleMesh.Type.Point, particleCount);
        int imagesPerAxis = "atlas".equals(layout) ? 4 : 1;
        emitter.setImagesX(imagesPerAxis);
        emitter.setImagesY(imagesPerAxis);
        mesh = (ParticlePointMesh) emitter.getMesh();
        particles = emitter.getParticles();
        for (int index = 0; index < particles.length; index++) {
            Particle particle = particles[index];
            particle.size = 0.25f + index % 5;
            particle.position.set(index, -index, index * 0.5f);
            particle.color.set(1f, 0.5f, 0.25f, 1f);
            particle.imageIndex = index % (imagesPerAxis * imagesPerAxis);
        }
        mesh.updateParticleData(particles, camera, Matrix3f.IDENTITY);
        texcoords = mesh.getBuffer(VertexBuffer.Type.TexCoord);
    }

    @Benchmark
    public void updatePointParticles(Blackhole blackhole) {
        if ("changing".equals(layout)) {
            alternate = !alternate;
            particles[particles.length - 1].imageIndex = alternate ? 3 : 0;
        }
        texcoords.clearUpdateNeeded();
        mesh.updateParticleData(particles, camera, Matrix3f.IDENTITY);
        blackhole.consume(mesh.getBuffer(VertexBuffer.Type.Position).getData());
        blackhole.consume(texcoords.isUpdateNeeded());
    }
}
