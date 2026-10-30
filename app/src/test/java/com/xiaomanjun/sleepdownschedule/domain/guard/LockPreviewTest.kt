package com.xiaomanjun.sleepdownschedule.domain.guard

import org.junit.Assert.*
import org.junit.Test

class LockPreviewTest {
    @Test fun testIsSixtySecondsAndNeverAcknowledgesEntryEarly() {
        val session = LockStateMachine.testSession(LockSession(), 1000)
        assertTrue(session.isTest); assertTrue(session.pendingEntry); assertFalse(session.decided)
        assertEquals(61_000L, session.endsAt)
    }
    @Test(expected = IllegalArgumentException::class) fun activeRealClassCannotBeOverwritten() {
        LockStateMachine.testSession(LockSession(active = true, endsAt = 5000), 1000)
    }
    @Test fun expiredSessionDoesNotBlockTesting() {
        assertTrue(LockStateMachine.testSession(LockSession(active = true, endsAt = 1000), 1000).isTest)
    }
    @Test fun testScreenClosesAtExactExpiry() {
        val session = LockStateMachine.testSession(LockSession(), 1000)
        assertFalse(LockStateMachine.shouldCloseScreen(session, session.windowKey, 60_999))
        assertTrue(LockStateMachine.shouldCloseScreen(session, session.windowKey, 61_000))
    }
}
