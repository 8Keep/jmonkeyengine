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
package com.jme3.light;

import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
@State(Scope.Thread)
public class ProcessedLightFilterBenchmark {

    @Param({"0", "1", "8", "64", "256"})
    public int lightCount;

    private Camera camera;
    private DefaultLightFilter filter;
    private Geometry geometry;
    private LightList filteredLights;

    @Setup
    public void setupTrial() {
        camera = new Camera(800, 600);
        camera.setFrustumPerspective(45f, 4f / 3f, 1f, 1000f);
        camera.setLocation(Vector3f.ZERO);
        camera.lookAtDirection(Vector3f.UNIT_Z, Vector3f.UNIT_Y);

        geometry = new Geometry("geometry", new Box(1f, 1f, 1f));
        geometry.setLocalTranslation(0f, 0f, 10f);
        for (int i = 0; i < lightCount; i++) {
            PointLight light = new PointLight(new Vector3f(i % 16 - 8, i / 16 - 8, 10f));
            light.setRadius(20f);
            geometry.addLight(light);
        }
        geometry.updateGeometricState();

        filter = new DefaultLightFilter();
        filteredLights = new LightList(geometry);
        filter.setCamera(camera);
        filter.filterLights(geometry, filteredLights);
    }

    @Benchmark
    public void filterAfterCameraReset(Blackhole blackhole) {
        filteredLights.clear();
        filter.setCamera(camera);
        filter.filterLights(geometry, filteredLights);
        blackhole.consume(filteredLights);
    }

    @Benchmark
    public void filterSteady(Blackhole blackhole) {
        filteredLights.clear();
        filter.filterLights(geometry, filteredLights);
        blackhole.consume(filteredLights);
    }
}
