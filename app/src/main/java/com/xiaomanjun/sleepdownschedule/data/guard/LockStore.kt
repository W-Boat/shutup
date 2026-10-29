package com.xiaomanjun.sleepdownschedule.data.guard

import android.content.Context
import java.time.YearMonth

/**
 * 锁机会话与每月退出额度的持久化。
 *
 * 额度按自然月滚动；进程被杀、应用被强制停止都不会重置额度，避免「杀一次进程就多一次退出」。
 */
class LockStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shutup_lock", Context.MODE_PRIVATE)

    data class Session(
        val active: Boolean = false,
        val windowKey: String = "",
        val courseName: String = "",
        val reason: String = "",
        val endsAt: Long = 0,
        val startedAt: Long = 0,
        val graceEndsAt: Long = 0,
        val decided: Boolean = false,
        val skipped: Boolean = false
    )

    fun session(): Session = Session(
        active = prefs.getBoolean("active", false),
        windowKey = prefs.getString("window_key", "").orEmpty(),
        courseName = prefs.getString("course", "").orEmpty(),
        reason = prefs.getString("reason", "").orEmpty(),
        endsAt = prefs.getLong("ends_at", 0),
        startedAt = prefs.getLong("started_at", 0),
        graceEndsAt = prefs.getLong("grace_ends_at", 0),
        decided = prefs.getBoolean("decided", false),
        skipped = prefs.getBoolean("skipped", false)
    )

    fun save(session: Session) {
        prefs.edit()
            .putBoolean("active", session.active)
            .putString("window_key", session.windowKey)
            .putString("course", session.courseName)
            .putString("reason", session.reason)
            .putLong("ends_at", session.endsAt)
            .putLong("started_at", session.startedAt)
            .putLong("grace_ends_at", session.graceEndsAt)
            .putBoolean("decided", session.decided)
            .putBoolean("skipped", session.skipped)
            .apply()
    }

    fun clear() {
        prefs.edit().putBoolean("active", false).putBoolean("decided", false).putBoolean("skipped", false).apply()
    }

    private fun currentMonth(): String = YearMonth.now().toString()

    fun exitsUsed(): Int {
        rollover()
        return prefs.getInt("exits_used", 0)
    }

    fun exitsLeft(quota: Int): Int = (quota - exitsUsed()).coerceAtLeast(0)

    /** Returns true when a direct exit was actually consumed. */
    fun consumeExit(quota: Int): Boolean {
        rollover()
        val used = prefs.getInt("exits_used", 0)
        if (used >= quota) return false
        prefs.edit().putInt("exits_used", used + 1).putLong("last_exit", System.currentTimeMillis()).apply()
        return true
    }

    fun resetExits() {
        prefs.edit().putString("month", currentMonth()).putInt("exits_used", 0).apply()
    }

    /** 已经用退出机会结束过的窗口；同一节课不会再被拉回锁机。 */
    fun exitedWindows(): Set<String> =
        prefs.getStringSet("exited_windows", emptySet())?.toSet().orEmpty()

    fun markExited(windowKey: String) {
        val next = (exitedWindows() + windowKey).toList().takeLast(40).toSet()
        prefs.edit().putStringSet("exited_windows", next).apply()
    }

    private fun rollover() {
        val month = currentMonth()
        if (prefs.getString("month", null) != month) {
            prefs.edit().putString("month", month).putInt("exits_used", 0).apply()
        }
    }
}
