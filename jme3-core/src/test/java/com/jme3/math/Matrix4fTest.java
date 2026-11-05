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

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class Matrix4fTest {
    @Test public void testEqualsAllComponentsAndFloatValues() {
        int[] bits={0,0x80000000,0x3f800000,0xbf800000,1,0x80000001,0x7f800000,0xff800000,0x7fc00001,0x7fc00002,0xffc00003};
        for(int component=0;component<16;component++) for(int a:bits) for(int b:bits) {
            Matrix4f first=new Matrix4f(),second=new Matrix4f();
            float x=Float.intBitsToFloat(a),y=Float.intBitsToFloat(b);
            first.set(component/4,component%4,x);second.set(component/4,component%4,y);
            boolean expected=Float.compare(x,y)==0;
            assertEquals(expected,first.equals(second),"component "+component);
            assertEquals(expected,second.equals(first));
            assertTrue(first.equals(first));
            if(expected) {assertEquals(first.hashCode(),second.hashCode());Map<Matrix4f,Integer> map=new HashMap<>();map.put(first,7);assertEquals(Integer.valueOf(7),map.get(second));}
        }
        assertFalse(new Matrix4f().equals(null));assertFalse(new Matrix4f().equals(new Matrix3f()));
    }
    @Test public void testEqualsRawBitPairs() {
        Random random=new Random(61019);
        for(int i=0;i<2000;i++) {
            Matrix4f first=new Matrix4f(),second=new Matrix4f();
            int component=i%16;float a=Float.intBitsToFloat(random.nextInt()),b=Float.intBitsToFloat(random.nextInt());
            first.set(component/4,component%4,a);second.set(component/4,component%4,b);
            assertEquals(Float.compare(a,b)==0,first.equals(second));
            assertEquals(first,first.clone());
        }
    }
}
