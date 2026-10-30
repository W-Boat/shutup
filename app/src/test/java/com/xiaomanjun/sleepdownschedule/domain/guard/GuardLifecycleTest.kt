package com.xiaomanjun.sleepdownschedule.domain.guard

import org.junit.Assert.*
import org.junit.Test

class GuardLifecycleTest {
    @Test fun allDisabledDoesNotStartBackgroundService() {
        assertFalse(GuardLifecycle.needsService(GuardDocument(), LockSession(), 1000))
    }
    @Test fun lockAndRootEachKeepStandaloneServiceAlive() {
        assertTrue(GuardLifecycle.needsService(GuardDocument(lock = LockSettings(enabled = true)), LockSession(), 1000))
        assertTrue(GuardLifecycle.needsService(GuardDocument(rootKeepAlive = true), LockSession(), 1000))
    }
    @Test fun configuredAudioRuleSurvivesOutsideItsActiveWindow() {
        assertTrue(GuardLifecycle.needsService(GuardDocument(rules = listOf(GuardRule("r", "r", "strict", Trigger.WEEKLY))), LockSession(), 1000))
    }
    @Test fun liveTestKeepsServiceButExpiryAndCancellationDoNot() {
        val session = LockStateMachine.testSession(LockSession(), 1000)
        assertTrue(GuardLifecycle.needsService(GuardDocument(), session, 1000))
        assertFalse(GuardLifecycle.needsService(GuardDocument(), session, 61_000))
        assertFalse(GuardLifecycle.needsService(GuardDocument(), session.copy(active = false), 1000))
    }
    @Test fun oldJsonDoesNotEnableRootWatchdog() {
        assertFalse(kotlinx.serialization.json.Json.decodeFromString<GuardDocument>("{}").rootKeepAlive)
    }
}
