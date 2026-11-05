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
package com.jme3.scene;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.shape.Box;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** Measures actual clean, dirty and inherited world-matrix updates. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
public class GeometryWorldMatrixBenchmark {
    @State(Scope.Thread)
    public static class Matrices {
        @Param({"root", "child", "ignored"})
        public String hierarchy;
        final Geometry[] geometries = new Geometry[32];
        final Node parent = new Node("parent");
        final Vector3f firstScale = new Vector3f(-2f, .25f, 3.5f);
        final Vector3f secondScale = new Vector3f(.5f, -4f, 1.25f);
        boolean alternate;

        @Setup
        public void setup() {
            parent.setLocalTranslation(-2, 4, 6);
            parent.setLocalRotation(new Quaternion().fromAngles(-.8f, .3f, 1.1f));
            parent.setLocalScale(1.5f, -.75f, 2);
            parent.updateGeometricState();
            Box mesh = new Box(1, 2, 3);
            for (int i = 0; i < geometries.length; i++) {
                Geometry geometry = new Geometry("geometry", mesh);
                geometry.setLocalTranslation(i - 16f, i * .25f, 7f - i);
                geometry.setLocalRotation(new Quaternion().fromAngles(i * .07f, -.6f, 1.1f));
                geometry.setLocalScale(firstScale);
                if (!"root".equals(hierarchy)) {
                    parent.attachChild(geometry);
                }
                geometry.setIgnoreTransform("ignored".equals(hierarchy));
                geometry.computeWorldMatrix();
                geometries[i] = geometry;
            }
            parent.updateGeometricState();
        }
    }

    @State(Scope.Thread)
    public static class MovingParent {
        final Node parent = new Node("parent");
        final Geometry[] geometries = new Geometry[32];
        boolean alternate;

        @Setup
        public void setup() {
            parent.setLocalRotation(new Quaternion().fromAngles(-.8f, .3f, 1.1f));
            parent.setLocalScale(1.5f, -.75f, 2);
            Box mesh = new Box(1, 2, 3);
            for (int i = 0; i < geometries.length; i++) {
                Geometry geometry = new Geometry("geometry", mesh);
                geometry.setLocalTranslation(i - 16f, i * .25f, 7f - i);
                geometry.setLocalRotation(new Quaternion().fromAngles(i * .07f, -.6f, 1.1f));
                geometry.setLocalScale(-2f, .25f, 3.5f);
                parent.attachChild(geometry);
                geometries[i] = geometry;
            }
            parent.updateGeometricState();
        }
    }

    @Benchmark
    @OperationsPerInvocation(32)
    public void computeCleanWorldMatrix(Matrices state, Blackhole blackhole) {
        for (Geometry geometry : state.geometries) {
            geometry.computeWorldMatrix();
            blackhole.consume(geometry.getWorldMatrix());
        }
    }

    @Benchmark
    @OperationsPerInvocation(32)
    public void changeScaleAndComputeWorldMatrix(Matrices state, Blackhole blackhole) {
        state.alternate = !state.alternate;
        Vector3f scale = state.alternate ? state.firstScale : state.secondScale;
        for (Geometry geometry : state.geometries) {
            geometry.setLocalScale(scale);
            geometry.computeWorldMatrix();
            blackhole.consume(geometry.getWorldMatrix());
        }
    }

    @Benchmark
    @OperationsPerInvocation(32)
    public void moveParentAndUpdateWorldMatrices(MovingParent state, Blackhole blackhole) {
        state.alternate = !state.alternate;
        state.parent.setLocalTranslation(state.alternate ? -2f : 3f, 4, 6);
        state.parent.updateGeometricState();
        for (Geometry geometry : state.geometries) {
            blackhole.consume(geometry.getWorldMatrix());
        }
    }
}
