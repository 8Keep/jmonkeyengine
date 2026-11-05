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
package com.jme3.math;

import com.jme3.shader.Uniform;
import com.jme3.shader.VarType;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Public equality and real uniform-set calls, with bounded input mutation in timing. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class Vector3fEqualsBenchmark {
    @Param({"equal","firstMismatch","lastMismatch","nan","signedZero","infinity"}) public String scenario;
    private final Vector3f[] left=new Vector3f[32],right=new Vector3f[32];
    private final Uniform[] uniforms=new Uniform[32];
    private int sequence;
    private float mismatch;
    @Setup public void setup() {
        mismatch=scenario.equals("firstMismatch") ? 1 : 0;
        for(int i=0;i<left.length;i++) {
            Vector3f a=new Vector3f(i,.5f,.25f),b=a.clone();
            if(scenario.equals("lastMismatch")) b.z=2;
            if(scenario.equals("nan")) {a.y=Float.intBitsToFloat(0x7fc00001);b.y=Float.intBitsToFloat(0x7fc00002);}
            if(scenario.equals("signedZero")) {a.z=-0f;b.z=0f;}
            if(scenario.equals("infinity")) a.y=b.y=Float.POSITIVE_INFINITY;
            left[i]=a;right[i]=b;
            Uniform uniform=new Uniform();uniform.setLocation(0);uniform.setValue(VarType.Vector3,b);uniform.clearUpdateNeeded();uniforms[i]=uniform;
        }
    }
    @Benchmark @OperationsPerInvocation(32) public void equalsBatch(Blackhole bh) {
        sequence=(sequence+1)&7;
        for(int i=0;i<left.length;i++) {
            float r=i+sequence*.125f;left[i].x=r;right[i].x=r+mismatch;
            bh.consume(left[i].equals(right[i]));
        }
    }
    @Benchmark @OperationsPerInvocation(32) public void uniformSet(Blackhole bh) {
        sequence=(sequence+1)&7;
        for(int i=0;i<left.length;i++) {
            float r=i+sequence*.125f;left[i].x=right[i].x=r;
            // Reset the stored value so the equality path depends on the chosen scenario.
            ((Vector3f)uniforms[i].getValue()).set(right[i]);
            if(mismatch!=0) ((Vector3f)uniforms[i].getValue()).x+=mismatch;
            uniforms[i].clearUpdateNeeded();uniforms[i].setValue(VarType.Vector3,left[i]);
            bh.consume(uniforms[i].getValue());bh.consume(uniforms[i].isUpdateNeeded());
        }
    }
}
