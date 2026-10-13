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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises explicit native-object unregistration without a renderer or GL context. */
public class NativeObjectManagerUnregisterTest {

    @Test
    public void explicitDeleteAndReRegistrationLeavesOnlyTheCurrentIdRegistered() {
        NativeObjectManager manager = new NativeObjectManager();
        List<Integer> deletedIds = new ArrayList<>();
        FakeNativeObject image = new FakeNativeObject(11, manager, deletedIds);
        manager.registerObject(image);

        // This models a successful explicit GL delete followed by renderer reset.
        image.explicitDelete();
        image.setId(22);
        manager.registerObject(image);

        manager.deleteAllObjects(new Object());

        assertEquals(Arrays.asList(11, 22), deletedIds,
                "cleanup must not revisit the explicitly deleted id");
        assertEquals(NativeObject.INVALID_ID, image.getId());
    }

    @Test
    public void managerDeletionCallbackCanUnregisterAgainAndImageCanBeReRegistered() {
        NativeObjectManager manager = new NativeObjectManager();
        List<Integer> deletedIds = new ArrayList<>();
        FakeNativeObject image = new FakeNativeObject(31, manager, deletedIds);
        manager.registerObject(image);

        // deleteAllObjects removes the map entry before invoking the clone callback.
        manager.deleteAllObjects(new Object());
        assertEquals(Arrays.asList(31), deletedIds);
        assertEquals(NativeObject.INVALID_ID, image.getId());

        image.setId(32);
        manager.registerObject(image);
        manager.deleteAllObjects(new Object());

        assertEquals(Arrays.asList(31, 32), deletedIds);
        assertEquals(NativeObject.INVALID_ID, image.getId());
    }

    @Test
    public void unregisteringAnOlderObjectDoesNotRemoveAnotherObjectsSameKey() {
        NativeObjectManager manager = new NativeObjectManager();
        List<Integer> deletedIds = new ArrayList<>();
        FakeNativeObject older = new FakeNativeObject(41, manager, deletedIds);
        FakeNativeObject current = new FakeNativeObject(41, manager, deletedIds);
        manager.registerObject(older);
        manager.registerObject(current);

        manager.unregisterObject(older);
        manager.deleteAllObjects(new Object());

        assertEquals(Arrays.asList(41), deletedIds);
        assertEquals(NativeObject.INVALID_ID, current.getId());
        assertEquals(41, older.getId(), "unregistering does not reset another object or the caller");
    }

    @Test
    public void repeatedUnregistrationIsANoopAndDoesNotDeleteTheObject() {
        NativeObjectManager manager = new NativeObjectManager();
        List<Integer> deletedIds = new ArrayList<>();
        FakeNativeObject image = new FakeNativeObject(51, manager, deletedIds);
        manager.registerObject(image);

        manager.unregisterObject(image);
        manager.unregisterObject(image);

        assertEquals(51, image.getId());
        assertTrue(deletedIds.isEmpty());
        manager.deleteAllObjects(new Object());
        assertTrue(deletedIds.isEmpty());
    }

    private static final class FakeNativeObject extends NativeObject {

        private final NativeObjectManager manager;
        private final List<Integer> deletedIds;
        private final FakeNativeObject realObject;

        FakeNativeObject(int id, NativeObjectManager manager, List<Integer> deletedIds) {
            super();
            this.id = id;
            this.manager = manager;
            this.deletedIds = deletedIds;
            this.realObject = this;
        }

        private FakeNativeObject(int id, NativeObjectManager manager, List<Integer> deletedIds,
                                 FakeNativeObject realObject) {
            super(id);
            this.manager = manager;
            this.deletedIds = deletedIds;
            this.realObject = realObject;
        }

        @Override
        public void resetObject() {
            id = INVALID_ID;
            setUpdateNeeded();
        }

        @Override
        public void deleteObject(Object rendererObject) {
            manager.unregisterObject(realObject);
            deletedIds.add(id);
            resetObject();
        }

        void explicitDelete() {
            deletedIds.add(id);
            manager.unregisterObject(this);
            resetObject();
        }

        @Override
        public NativeObject createDestructableClone() {
            return new FakeNativeObject(id, manager, deletedIds, realObject);
        }

        @Override
        public long getUniqueId() {
            return ((long) OBJTYPE_TEXTURE << 32) | (0xffffffffL & (long) id);
        }
    }
}
