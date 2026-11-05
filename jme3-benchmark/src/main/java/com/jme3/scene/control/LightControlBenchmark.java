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
package com.jme3.scene.control;

import com.jme3.light.DirectionalLight;
import com.jme3.light.Light;
import com.jme3.light.PointLight;
import com.jme3.light.SpotLight;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
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

/** Actual LightControl updates, including transform changes in the timed work. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
public class LightControlBenchmark {
    @State(Scope.Thread)
    public static class Forward {
        @Param({"point", "directional", "spot"})
        public String kind;
        @Param({"steady", "dirty"})
        public String motion;
        final Node parent = new Node("parent");
        final Node spatial = new Node("spatial");
        Light light;
        LightControl control;
        boolean alternate;

        @Setup
        public void setup() {
            parent.setLocalTranslation(-2, 4, 6);
            parent.setLocalRotation(new Quaternion().fromAngles(.4f, -1.2f, .7f));
            parent.setLocalScale(1.5f, .5f, 2);
            spatial.setLocalTranslation(3, -5, 7);
            spatial.setLocalRotation(new Quaternion().fromAngles(-.7f, .2f, 1.1f));
            parent.attachChild(spatial);
            parent.updateGeometricState();
            light = "point".equals(kind) ? new PointLight()
                    : "directional".equals(kind) ? new DirectionalLight() : new SpotLight();
            control = new LightControl(light);
            control.setSpatial(spatial);
            control.update(0);
        }
    }

    @State(Scope.Thread)
    public static class Reverse {
        final Node spatial = new Node("spatial");
        final PointLight light = new PointLight();
        final LightControl control = new LightControl(light, LightControl.ControlDirection.LightToSpatial);
        final Vector3f first = new Vector3f(3, -5, 7);
        final Vector3f second = new Vector3f(-2, 4, 6);
        boolean alternate;
        @Setup
        public void setup() {
            control.setSpatial(spatial);
        }
    }

    @Benchmark
    public void spatialToLight(Forward state, Blackhole blackhole) {
        if ("dirty".equals(state.motion)) {
            state.alternate = !state.alternate;
            state.parent.setLocalTranslation(state.alternate ? -2f : 8f, 4, 6);
            state.spatial.setLocalTranslation(state.alternate ? 3f : -4f, -5, 7);
        }
        state.control.update(0);
        if (state.light instanceof PointLight) {
            consume(((PointLight) state.light).getPosition(), blackhole);
        } else if (state.light instanceof DirectionalLight) {
            consume(((DirectionalLight) state.light).getDirection(), blackhole);
        } else {
            consume(((SpotLight) state.light).getPosition(), blackhole);
            consume(((SpotLight) state.light).getDirection(), blackhole);
        }
    }

    @Benchmark
    public void lightToSpatial(Reverse state, Blackhole blackhole) {
        state.alternate = !state.alternate;
        state.light.setPosition(state.alternate ? state.first : state.second);
        state.control.update(0);
        consume(state.spatial.getLocalTranslation(), blackhole);
    }

    private static void consume(Vector3f vector, Blackhole blackhole) {
        blackhole.consume(vector.x);
        blackhole.consume(vector.y);
        blackhole.consume(vector.z);
    }
}
