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
import com.jme3.material.logic.TechniqueDefLogic;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Geometry;
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

/** Measures actual Technique define reset after a prior shader-logic value. */
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class TechniqueDefineCopyBenchmark {
    @Param({"0", "1", "8", "64", "256"})
    public int defineCount;
    @Param({"empty", "sparse", "dense"})
    public String density;

    private Technique technique;
    private RenderManager manager;
    private LightList lights;
    private final EnumSet<Caps> caps = EnumSet.noneOf(Caps.class);

    @Setup
    public void setup() {
        manager = new RenderManager(new NullRenderer());
        MaterialDef materialDef = new MaterialDef(null, "benchmark");
        TechniqueDef definition = new TechniqueDef(TechniqueDef.DEFAULT_TECHNIQUE_NAME, 1);
        definition.setLogic(new NoopLogic(new Shader()));
        for (int i = 0; i < defineCount; i++) {
            materialDef.addMaterialParam(VarType.Int, "Value" + i, null);
            definition.addShaderParamDefine("Value" + i, VarType.Int, "D" + i);
        }
        materialDef.addTechniqueDef(definition);
        technique = new Technique(new Material(materialDef), definition);
        if (!"empty".equals(density)) {
            int stride = "dense".equals(density) ? 1 : 8;
            for (int i = 0; i < defineCount; i += stride) {
                technique.notifyParamChanged("Value" + i, VarType.Int, i + 1);
            }
        }
        lights = new LightList(null);
    }

    @Benchmark
    public void makeCurrentWithDefineReset(Blackhole blackhole) {
        if (defineCount != 0) {
            technique.getDynamicDefines().set(defineCount - 1, -7);
        }
        blackhole.consume(technique.makeCurrent(manager, null, null, lights, caps));
        blackhole.consume(technique.getDynamicDefines());
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

}
