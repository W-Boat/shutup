package com.xiaomanjun.sleepdownschedule.domain.guard

/** One definition shared by foreground recovery, service shutdown, task removal and alarms. */
object GuardLifecycle {
    fun needsService(document: GuardDocument, session: LockSession, now: Long): Boolean =
        document.rootKeepAlive || document.lock.enabled || document.rules.any { it.enabled } || document.manualUntil > now ||
            (session.isTest && session.active && session.endsAt > now && !session.skipped)
}
