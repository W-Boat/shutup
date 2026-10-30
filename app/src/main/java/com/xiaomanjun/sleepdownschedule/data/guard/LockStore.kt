package com.xiaomanjun.sleepdownschedule.data.guard

import android.content.Context
import com.xiaomanjun.sleepdownschedule.domain.guard.LockSession
import com.xiaomanjun.sleepdownschedule.domain.guard.LockStateMachine
import java.time.YearMonth

/** All instances share one monitor. No caller may write a previously read session back. */
class LockStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shutup_lock", Context.MODE_PRIVATE)
    companion object { private val monitor = Any() }

    fun session(): LockSession = synchronized(monitor) { read() }

    private fun read() = LockSession(
        active = prefs.getBoolean("active", false),
        windowKey = prefs.getString("window_key", "").orEmpty(),
        courseName = prefs.getString("course", "").orEmpty(),
        reason = prefs.getString("reason", "").orEmpty(),
        endsAt = prefs.getLong("ends_at", 0),
        startedAt = prefs.getLong("started_at", 0),
        graceEndsAt = prefs.getLong("grace_ends_at", 0),
        decided = prefs.getBoolean("decided", false),
        skipped = prefs.getBoolean("skipped", false),
        pendingEntry = prefs.getBoolean("pending_entry", false),
        isTest = prefs.getBoolean("is_test", false)
    )

    private fun write(session: LockSession, edit: android.content.SharedPreferences.Editor = prefs.edit()) {
        check(edit.putBoolean("active", session.active)
            .putString("window_key", session.windowKey).putString("course", session.courseName)
            .putString("reason", session.reason).putLong("ends_at", session.endsAt)
            .putLong("started_at", session.startedAt).putLong("grace_ends_at", session.graceEndsAt)
            .putBoolean("decided", session.decided).putBoolean("skipped", session.skipped)
            .putBoolean("pending_entry", session.pendingEntry).putBoolean("is_test", session.isTest).commit()) { "锁机会话保存失败" }
    }

    fun transition(change: (LockSession) -> LockSession): LockSession = synchronized(monitor) {
        val old = read()
        change(old).also { if (it != old) write(it) }
    }

    fun clear() { transition { it.copy(active = false, pendingEntry = false, decided = false, skipped = false, isTest = false) } }

    fun startTest(now: Long): LockSession = transition { LockStateMachine.testSession(it, now) }

    fun endTest(key: String, now: Long): Boolean = synchronized(monitor) {
        val old = read()
        if (!old.isTest || !LockStateMachine.valid(old, key, now)) return@synchronized false
        write(old.copy(active = false, pendingEntry = false, decided = false, skipped = true))
        true
    }

    private fun rollover() {
        val month = YearMonth.now().toString()
        if (prefs.getString("month", null) != month)
            check(prefs.edit().putString("month", month).putInt("exits_used", 0).commit())
    }

    fun exitsUsed(): Int = synchronized(monitor) { rollover(); prefs.getInt("exits_used", 0) }
    fun exitsLeft(quota: Int): Int = (quota - exitsUsed()).coerceAtLeast(0)

    /** Quota, session and suppression key commit together; a double tap cannot consume twice. */
    fun exit(key: String, now: Long, quota: Int): Boolean = synchronized(monitor) {
        rollover()
        val old = read()
        if (old.isTest) return@synchronized endTest(key, now)
        val used = prefs.getInt("exits_used", 0)
        val next = LockStateMachine.exit(old, key, now, used, quota)
        if (next == old) return@synchronized false
        write(next, prefs.edit().putInt("exits_used", used + 1).putLong("last_exit", now)
            .putStringSet("exited_windows", (exitedWindows() + key).toList().takeLast(200).toSet()))
        true
    }

    fun resetExits() = synchronized(monitor) {
        check(prefs.edit().putString("month", YearMonth.now().toString()).putInt("exits_used", 0).commit())
    }

    fun exitedWindows(): Set<String> = synchronized(monitor) {
        prefs.getStringSet("exited_windows", emptySet())?.toSet().orEmpty()
    }
}
