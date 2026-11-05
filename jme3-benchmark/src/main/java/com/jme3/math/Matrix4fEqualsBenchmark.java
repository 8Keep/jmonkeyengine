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
package com.jme3.math;

import com.jme3.shader.Uniform;
import com.jme3.shader.VarType;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Public equality and actual uniform calls; bounded mutation and caller reset are timed. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class Matrix4fEqualsBenchmark {
    @Param({"equal","firstMismatch","lastMismatch","nan","signedZero","infinity"}) public String scenario;
    private final Matrix4f[] left=new Matrix4f[32],right=new Matrix4f[32];
    private final Uniform[] uniforms=new Uniform[32];
    private int sequence;
    private float mismatch;
    @Setup public void setup() {
        mismatch=scenario.equals("firstMismatch") ? 1 : 0;
        for(int i=0;i<left.length;i++) {
            Matrix4f a=new Matrix4f(),b=a.clone();
            if(scenario.equals("lastMismatch")) b.m33=2;
            if(scenario.equals("nan")) {a.m12=Float.intBitsToFloat(0x7fc00001);b.m12=Float.intBitsToFloat(0x7fc00002);}
            if(scenario.equals("signedZero")) {a.m31=-0f;b.m31=0f;}
            if(scenario.equals("infinity")) a.m23=b.m23=Float.POSITIVE_INFINITY;
            left[i]=a;right[i]=b;
            Uniform u=new Uniform();u.setLocation(0);u.setValue(VarType.Matrix4,b);u.clearUpdateNeeded();uniforms[i]=u;
            boolean expected=!scenario.equals("firstMismatch") && !scenario.equals("lastMismatch") && !scenario.equals("signedZero");
            a.m00=0;b.m00=mismatch;
            if(a.equals(b)!=expected) throw new AssertionError(scenario);
        }
    }
    @Benchmark @OperationsPerInvocation(32) public void equalsBatch(Blackhole bh) {
        sequence=(sequence+1)&7;
        for(int i=0;i<left.length;i++) {
            float value=i+sequence*.125f;left[i].m00=value;right[i].m00=value+mismatch;
            bh.consume(left[i].equals(right[i]));
        }
    }
    @Benchmark @OperationsPerInvocation(32) public void uniformSet(Blackhole bh) {
        sequence=(sequence+1)&7;
        for(int i=0;i<left.length;i++) {
            float value=i+sequence*.125f;left[i].m00=right[i].m00=value;
            ((Matrix4f)uniforms[i].getValue()).copy(right[i]);
            if(mismatch!=0) ((Matrix4f)uniforms[i].getValue()).m00+=mismatch;
            uniforms[i].clearUpdateNeeded();uniforms[i].setValue(VarType.Matrix4,left[i]);
            bh.consume(uniforms[i].getValue());bh.consume(uniforms[i].isUpdateNeeded());
        }
    }
}
