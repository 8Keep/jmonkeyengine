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
package com.jme3.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Iterator;
import org.junit.jupiter.api.Test;

public class SafeArrayListTest {

    @Test
    public void clearPreservesPublishedSnapshotsAndClone() {
        SafeArrayList<String> list = new SafeArrayList<>(String.class);
        list.addAll(Arrays.asList("a", "b"));
        String[] snapshot = list.getArray();
        final Iterator<String> iterator = list.iterator();
        final SafeArrayList<String> clone = list.clone();

        list.clear();
        assertTrue(list.isEmpty());
        assertArrayEquals(new String[]{"a", "b"}, snapshot);
        assertEquals("a", iterator.next());
        assertEquals("b", iterator.next());
        assertFalse(iterator.hasNext());
        assertEquals(Arrays.asList("a", "b"), clone);
        list.add("c");
        assertEquals(Arrays.asList("c"), list);
        assertEquals(Arrays.asList("a", "b"), clone);

        String[] cloneSnapshot = clone.getArray();
        clone.clear();
        assertArrayEquals(new String[]{"a", "b"}, cloneSnapshot);
        assertEquals(Arrays.asList("c"), list);
    }

    @Test
    public void clearHandlesBufferAndRetainsEmptySnapshot() {
        SafeArrayList<String> list = new SafeArrayList<>(String.class);
        list.add("a");
        final SafeArrayList<String> clone = list.clone();
        list.clear();
        assertEquals(0, list.size());
        assertEquals(Arrays.asList("a"), clone);
        list.add("b");
        assertEquals("b", list.get(0));
        list.clear();
        String[] empty = list.getArray();
        list.clear();
        list.clear();
        assertSame(empty, list.getArray());
        list.add("c");
        assertArrayEquals(new String[0], empty);
        assertEquals(Arrays.asList("c"), list);
    }

}
