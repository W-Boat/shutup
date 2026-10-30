package com.xiaomanjun.sleepdownschedule.domain.guard

import kotlinx.serialization.Serializable

@Serializable
data class CareDeliveryState(val delivered: Boolean = false, val attempts: Int = 0, val retryAt: Long = 0)

object CareDelivery {
    const val RetryDelay = 60_000L
    fun canAttempt(state: CareDeliveryState, now: Long, expires: Long): Boolean =
        !state.delivered && state.attempts < 3 && now >= state.retryAt && now < expires

    fun complete(state: CareDeliveryState, success: Boolean, now: Long): CareDeliveryState =
        if (state.delivered) state else CareDeliveryState(success, state.attempts + 1, if (success) 0 else now + RetryDelay)
}
