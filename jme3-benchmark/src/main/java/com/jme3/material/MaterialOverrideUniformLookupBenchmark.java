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
package com.jme3.material;

import com.jme3.asset.AssetManager;
import com.jme3.light.LightList;
import com.jme3.material.Material.BindUnits;
import com.jme3.material.TechniqueDef.LightMode;
import com.jme3.material.logic.TechniqueDefLogic;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.shader.DefineList;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import com.jme3.system.NullRenderer;
import java.util.EnumSet;
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

/** Applies parameters through the actual material render path. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class MaterialOverrideUniformLookupBenchmark {
    @Param({"1", "4", "16", "64"})
    public int overrideCount;
    @Param({"world", "forced", "material"})
    public String source;

    private Shader shader;
    private Material material;
    private Geometry geometry;
    private LightList lights;
    private RenderManager renderManager;
    private ObservingRenderer renderer;

    @Setup
    public void setup() {
        renderer = new ObservingRenderer();
        renderManager = new RenderManager(renderer);
        renderManager.setPreferredLightMode(LightMode.Disable);
        shader = new Shader();
        MaterialDef definition = new MaterialDef(null, "benchmark");
        for (int i = 0; i < overrideCount; i++) {
            definition.addMaterialParam(VarType.Float, "Override" + i, null);
            shader.getUniform("m_Override" + i).setLocation(i);
        }
        TechniqueDef technique = new TechniqueDef(TechniqueDef.DEFAULT_TECHNIQUE_NAME, 1);
        technique.setLogic(new NoopLogic(shader));
        definition.addTechniqueDef(technique);
        material = new Material(definition);
        material.selectTechnique(TechniqueDef.DEFAULT_TECHNIQUE_NAME, renderManager);
        geometry = new Geometry("geometry", new Mesh());
        for (int i = 0; i < overrideCount; i++) {
            String name = "Override" + i;
            if ("material".equals(source)) {
                material.setFloat(name, i);
            } else {
                MatParamOverride override = new MatParamOverride(VarType.Float, name, (float) i);
                if ("world".equals(source)) {
                    geometry.addMatParamOverride(override);
                } else {
                    renderManager.addForcedMatParam(override);
                }
            }
        }
        geometry.updateGeometricState();
        lights = new LightList(geometry);
    }

    @Benchmark
    public void renderParameters(Blackhole blackhole) {
        material.render(geometry, lights, renderManager);
        blackhole.consume(shader);
        blackhole.consume(renderer.appliedState);
    }

    private static final class NoopLogic implements TechniqueDefLogic {
        private final Shader shader;

        NoopLogic(Shader shader) {
            this.shader = shader;
        }

        @Override
        public Shader makeCurrent(AssetManager manager, RenderManager manager2,
                EnumSet<Caps> caps, LightList list, DefineList defines) {
            return shader;
        }

        @Override
        public void render(RenderManager manager, Shader shader, Geometry geometry,
                LightList list, BindUnits units) {
        }
    }

    private static final class ObservingRenderer extends NullRenderer {
        private final EnumSet<Caps> caps = EnumSet.noneOf(Caps.class);
        private volatile RenderState appliedState;

        @Override
        public EnumSet<Caps> getCaps() {
            return caps;
        }

        @Override
        public void applyRenderState(RenderState state) {
            // Publish the complete state so copied fields remain observable.
            appliedState = state;
        }
    }
}
