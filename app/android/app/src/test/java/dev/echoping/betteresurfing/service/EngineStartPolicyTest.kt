package dev.echoping.betteresurfing.service

import org.junit.Assert.*
import org.junit.Test

class EngineStartPolicyTest {
    @Test fun staleOnlineStateMustNotPreventRestartAfterDisconnect() {
        assertTrue(EngineStartPolicy.shouldStart(force = false, running = false))
        assertFalse(EngineStartPolicy.shouldStart(force = false, running = true))
        assertTrue(EngineStartPolicy.shouldStart(force = true, running = true))
    }

    @Test fun queuedReconnectRunsAfterPendingStop() {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val stopEntered = java.util.concurrent.CountDownLatch(1)
        val releaseStop = java.util.concurrent.CountDownLatch(1)
        val startDone = java.util.concurrent.CountDownLatch(1)
        dev.echoping.betteresurfing.engine.EngineHolder.enqueue {
            stopEntered.countDown()
            check(releaseStop.await(3, java.util.concurrent.TimeUnit.SECONDS))
            running.set(false)
        }
        try {
            assertTrue(stopEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            // Simulates a reconnect or replacement service arriving while cleanup is pending.
            dev.echoping.betteresurfing.engine.EngineHolder.enqueue {
                if (EngineStartPolicy.shouldStart(force = false, running = running.get())) {
                    running.set(true)
                    startDone.countDown()
                }
            }
            releaseStop.countDown()
            assertTrue(startDone.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(running.get())
        } finally { releaseStop.countDown() }
    }
}
