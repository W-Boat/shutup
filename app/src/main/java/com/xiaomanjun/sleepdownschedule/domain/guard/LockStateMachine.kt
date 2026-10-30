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
    val pendingEntry: Boolean = false,
    val isTest: Boolean = false
)

/** Pure transitions; the store serializes read/transition/write as a single operation. */
object LockStateMachine {
    fun testSession(previous: LockSession, now: Long): LockSession {
        require(!previous.active || previous.endsAt <= now || previous.skipped) { "当前已有锁机会话，请结束后再测试" }
        return LockSession(active = true, windowKey = "lock-test|$now", courseName = "锁机测试",
            reason = "60 秒体验，不消耗退出额度", startedAt = now, endsAt = now + 60_000,
            graceEndsAt = now, pendingEntry = true, isTest = true)
    }

    fun valid(session: LockSession, key: String, now: Long): Boolean =
        session.active && key.isNotBlank() && session.windowKey == key && now < session.endsAt

    fun shouldCloseScreen(session: LockSession, screenKey: String, now: Long): Boolean =
        !valid(session, screenKey, now) || session.skipped

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
