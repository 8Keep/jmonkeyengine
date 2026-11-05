/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
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

import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector4f;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmarks changed scalar Vector4 uniform values, including source mutation.
 * The bounded counter keeps every value distinct from its predecessor even in
 * long runs, so {@link Uniform#setValue} cannot take its equal-value fast path.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
@State(Scope.Thread)
public class UniformVector4Benchmark {

    @Param({"color", "vector", "quaternion"})
    public String destinationType;

    private Uniform uniform;
    private ColorRGBA color;
    private Vector4f vector;
    private Quaternion quaternion;
    private int sequence;

    @Setup(Level.Trial)
    public void setupTrial() {
        uniform = new Uniform();
        color = new ColorRGBA();
        vector = new Vector4f();
        quaternion = new Quaternion();
        if (destinationType.equals("color")) {
            uniform.setValue(VarType.Vector4, color);
        } else if (destinationType.equals("vector")) {
            uniform.setValue(VarType.Vector4, vector);
        } else {
            uniform.setValue(VarType.Vector4, quaternion);
        }
    }

    private float nextValue() {
        sequence = (sequence + 1) & 1023;
        return sequence;
    }

    @Benchmark
    public void setColor(Blackhole blackhole) {
        float value = nextValue();
        color.set(value + 0f, value + 1f, value + 2f, value + 3f);
        uniform.setValue(VarType.Vector4, color);
        blackhole.consume(uniform.getValue());
    }

    @Benchmark
    public void setVector(Blackhole blackhole) {
        float value = nextValue();
        vector.set(value + 4f, value + 5f, value + 6f, value + 7f);
        uniform.setValue(VarType.Vector4, vector);
        blackhole.consume(uniform.getValue());
    }

    @Benchmark
    public void setQuaternion(Blackhole blackhole) {
        float value = nextValue();
        quaternion.set(value + 8f, value + 9f, value + 10f, value + 11f);
        uniform.setValue(VarType.Vector4, quaternion);
        blackhole.consume(uniform.getValue());
    }
}
