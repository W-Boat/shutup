package com.xiaomanjun.sleepdownschedule.domain.guard

import org.junit.Assert.*
import org.junit.Test

class LockStateMachineTest {
    private val course = LockSession(active = true, windowKey = "math|1000", startedAt = 1000,
        graceEndsAt = 2000, endsAt = 5000)

    @Test fun newClassStartsUndecided() {
        assertEquals(course, LockStateMachine.begin(LockSession(), course, emptySet()))
        assertFalse(course.decided)
    }
    @Test fun timeoutWaitsForActualEntry() {
        val pending = LockStateMachine.timeout(course, 2000, true)
        assertTrue(pending.pendingEntry)
        assertFalse(pending.decided)
        assertTrue(LockStateMachine.entered(pending, course.windowKey, 2100).decided)
    }
    @Test fun staleAndExpiredActionsCannotTouchNewClass() {
        assertEquals(course, LockStateMachine.choose(course, "old", 1100, false))
        assertEquals(course, LockStateMachine.choose(course, course.windowKey, 5000, true))
        assertEquals(course, LockStateMachine.entered(course, "old", 1100))
    }
    @Test fun skippedClassCannotBeReentered() {
        val skipped = LockStateMachine.choose(course, course.windowKey, 1100, false)
        assertEquals(skipped, LockStateMachine.entered(skipped, course.windowKey, 1200))
        assertEquals(skipped, LockStateMachine.begin(skipped, course, emptySet()))
    }
    @Test fun expiredClassEndsAtExactBoundary() {
        assertFalse(LockStateMachine.timeout(course.copy(decided = true), 5000, true).active)
    }
    @Test fun exhaustedQuotaAndDoubleExitDoNotConsumeAgain() {
        val locked = course.copy(decided = true)
        assertEquals(locked, LockStateMachine.exit(locked, course.windowKey, 3000, 3, 3))
        val ended = LockStateMachine.exit(locked, course.windowKey, 3000, 2, 3)
        assertFalse(ended.active)
        assertEquals(ended, LockStateMachine.exit(ended, course.windowKey, 3000, 2, 3))
        assertFalse(LockStateMachine.begin(ended, course, setOf(course.windowKey)).active)
    }
    @Test fun disabledAutomaticLockSkipsAtDeadline() {
        val next = LockStateMachine.timeout(course, 2000, false)
        assertTrue(next.skipped)
        assertFalse(next.pendingEntry)
    }
    @Test fun courseIdentityUpgradeHonorsPreviouslyConsumedExit() {
        val candidate = course.copy(windowKey = "course:1:10|1000")
        assertFalse(LockStateMachine.begin(LockSession(), candidate, setOf("math|1000"), setOf("math|1000")).active)
        assertTrue(LockStateMachine.begin(LockSession(), candidate, setOf("math|999"), setOf("math|1000")).active)
    }
}
