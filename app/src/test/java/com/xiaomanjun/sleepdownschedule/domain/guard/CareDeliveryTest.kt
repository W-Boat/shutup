package com.xiaomanjun.sleepdownschedule.domain.guard

import org.junit.Assert.*
import org.junit.Test

class CareDeliveryTest {
    @Test fun successIsDeduplicatedOnlyAfterCompletion() {
        val initial = CareDeliveryState()
        assertTrue(CareDelivery.canAttempt(initial, 100, 1000))
        val sent = CareDelivery.complete(initial, true, 100)
        assertFalse(CareDelivery.canAttempt(sent, 101, 1000))
        assertEquals(sent, CareDelivery.complete(sent, false, 101))
    }
    @Test fun failureAllowsDelayedBoundedRetry() {
        var state = CareDelivery.complete(CareDeliveryState(), false, 100)
        assertFalse(state.delivered)
        assertFalse(CareDelivery.canAttempt(state, 101, 999999))
        assertTrue(CareDelivery.canAttempt(state, 60100, 999999))
        state = CareDelivery.complete(state, false, 60100)
        state = CareDelivery.complete(state, false, 120100)
        assertFalse(CareDelivery.canAttempt(state, 180100, 999999))
    }
    @Test fun expiredReminderIsNeverRetried() {
        assertFalse(CareDelivery.canAttempt(CareDeliveryState(), 1000, 1000))
    }
}
