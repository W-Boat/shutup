package com.xiaomanjun.sleepdownschedule.domain.guard

data class LockSession(
    val active: Boolean = false,
    val windowKey: String = "",
    val courseName: String = "",
    val reason: String = "",
    val endsAt: Long = 0,
    val startedAt: Long = 0,
    val graceEndsAt: Long = 0,
    val decided: Boolean = false,
    val skipped: Boolean = false,
    val pendingEntry: Boolean = false
)

/** Pure transitions; the store serializes read/transition/write as a single operation. */
object LockStateMachine {
    fun valid(session: LockSession, key: String, now: Long): Boolean =
        session.active && key.isNotBlank() && session.windowKey == key && now < session.endsAt

    fun choose(session: LockSession, key: String, now: Long, lock: Boolean): LockSession =
        if (!valid(session, key, now) || session.decided || session.skipped) session
        else session.copy(pendingEntry = lock, skipped = !lock)

    fun entered(session: LockSession, key: String, now: Long): LockSession =
        if (!valid(session, key, now) || session.skipped) session
        else session.copy(decided = true, pendingEntry = false)

    fun timeout(session: LockSession, now: Long, autoLock: Boolean): LockSession = when {
        session.active && now >= session.endsAt -> session.copy(active = false, pendingEntry = false)
        session.active && !session.decided && !session.skipped && now >= session.graceEndsAt ->
            session.copy(pendingEntry = autoLock, skipped = !autoLock)
        else -> session
    }

    fun begin(previous: LockSession, candidate: LockSession, exited: Set<String>, legacyKeys: Set<String> = emptySet()): LockSession = when {
        candidate.windowKey in exited || legacyKeys.any { it in exited } -> previous.copy(active = false, pendingEntry = false)
        previous.active && previous.windowKey == candidate.windowKey -> previous
        else -> candidate
    }

    fun exit(session: LockSession, key: String, now: Long, used: Int, quota: Int): LockSession =
        if (!valid(session, key, now) || !session.decided || used >= quota) session
        else session.copy(active = false, skipped = true, pendingEntry = false)
}
