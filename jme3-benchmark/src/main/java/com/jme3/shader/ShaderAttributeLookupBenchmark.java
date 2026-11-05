/*
 * Copyright (c) 2009-2023 jMonkeyEngine
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
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Actual shader lookups, cold creation, and location reset; all setup is asserted. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ShaderAttributeLookupBenchmark {
    private static final VertexBuffer.Type[] TYPES={VertexBuffer.Type.Position,VertexBuffer.Type.Normal,
        VertexBuffer.Type.TexCoord,VertexBuffer.Type.Color,VertexBuffer.Type.Tangent,
        VertexBuffer.Type.Binormal,VertexBuffer.Type.BoneWeight,VertexBuffer.Type.BoneIndex};
    @State(Scope.Thread) public static class Lookups {
        @Param({"1","4","8"}) public int attributeCount;
        @Param({"1","16"}) public int shaderCount;
        Shader[] shaders;
        int sequence;
        @Setup public void setup() {
            shaders=new Shader[shaderCount];
            for(int s=0;s<shaderCount;s++) {
                Shader shader=shaders[s]=new Shader();
                for(int i=0;i<attributeCount;i++) {
                    Attribute attribute=shader.getAttribute(TYPES[i]);attribute.setLocation(i+s);
                    if(shader.getAttribute(TYPES[i])!=attribute || !attribute.getName().equals(TYPES[i].name()))throw new AssertionError();
                }
            }
        }
    }
    @State(Scope.Thread) public static class Creation {
        @Param({"0","1","8"}) public int attributeCount;
    }
    @State(Scope.Thread) public static class Reset {
        @Param({"1","8"}) public int attributeCount;
        Shader shader;
        @Setup public void setup() {
            shader=new Shader();for(int i=0;i<attributeCount;i++)shader.getAttribute(TYPES[i]).setLocation(i);
            shader.resetLocations();
            for(int i=0;i<attributeCount;i++)if(shader.getAttribute(TYPES[i]).getLocation()!=ShaderVariable.LOC_UNKNOWN)throw new AssertionError();
        }
    }
    @Benchmark @OperationsPerInvocation(32) public void lookupCreatedAttributes(Lookups state,Blackhole bh) {
        state.sequence=(state.sequence+1)&15;
        for(int draw=0;draw<32;draw++) {
            Shader shader=state.shaders[(draw+state.sequence)&(state.shaderCount-1)];
            for(int i=0;i<state.attributeCount;i++)bh.consume(shader.getAttribute(TYPES[i]));
        }
    }
    @Benchmark public Shader createAndPopulate(Creation state,Blackhole bh) {
        Shader shader=new Shader();
        for(int i=0;i<state.attributeCount;i++)bh.consume(shader.getAttribute(TYPES[i]));
        return shader;
    }
    @Benchmark public void resetAndResolve(Reset state,Blackhole bh) {
        state.shader.resetLocations();
        for(int i=0;i<state.attributeCount;i++) {
            Attribute attribute=state.shader.getAttribute(TYPES[i]);
            attribute.setLocation(i);bh.consume(attribute);
        }
    }
}
