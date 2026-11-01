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
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.app;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppTaskTest {

    @Test
    void completedNullCanBeReadWithZeroTimeout() throws Exception {
        AppTask<Object> task = new AppTask<>(() -> null);
        task.invoke();

        assertNull(task.get(0, TimeUnit.NANOSECONDS));
    }

    @Test
    void completedValueCanBeReadWithZeroTimeout() throws Exception {
        Object value = new Object();
        AppTask<Object> task = new AppTask<>(() -> value);
        task.invoke();

        assertSame(value, task.get(0, TimeUnit.NANOSECONDS));
    }

    @Test
    void unfinishedTaskTimesOutImmediately() {
        AppTask<Object> task = new AppTask<>(() -> null);

        assertThrows(TimeoutException.class, () -> task.get(0, TimeUnit.NANOSECONDS));
    }

    @Test
    void delayedNullCompletionWakesTimedWaiter() throws Exception {
        CountDownLatch callableStarted = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        CountDownLatch getterStarted = new CountDownLatch(1);
        AppTask<Object> task = new AppTask<>(() -> {
            callableStarted.countDown();
            allowCompletion.await();
            return null;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> invocation = null;
        Future<Object> getter = null;
        try {
            invocation = executor.submit(task::invoke);
            assertTrue(callableStarted.await(1, TimeUnit.SECONDS));
            getter = executor.submit(() -> {
                getterStarted.countDown();
                return task.get(2, TimeUnit.SECONDS);
            });
            assertTrue(getterStarted.await(1, TimeUnit.SECONDS));
            allowCompletion.countDown();

            assertNull(getter.get(2, TimeUnit.SECONDS));
            invocation.get(2, TimeUnit.SECONDS);
        } finally {
            allowCompletion.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void unfinishedTimedWaitPropagatesInterruption() {
        AppTask<Object> task = new AppTask<>(() -> null);
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> task.get(1, TimeUnit.SECONDS));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void timedGetPreservesCallableExceptionCause() {
        IllegalStateException cause = new IllegalStateException("expected");
        AppTask<Object> task = new AppTask<>(() -> {
            throw cause;
        });
        task.invoke();

        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> task.get(0, TimeUnit.NANOSECONDS));
        assertSame(cause, exception.getCause());
    }
}
