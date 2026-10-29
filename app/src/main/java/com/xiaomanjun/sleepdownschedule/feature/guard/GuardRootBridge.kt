package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Optional su channel for the sound guard.
 *
 * Root is never required: every call fails soft and the caller falls back to the public
 * AudioManager/settings APIs. Root only buys deterministic writes (no per-app volume index
 * rounding, no Scoped volume UI for brightness) and the battery whitelist.
 */
internal object GuardRootBridge {
    private const val Tag = "GuardRootBridge"
    private const val Prefs = "shutup_guard_root"
    private const val Authorized = "authorized"
    private const val CheckedAt = "checked_at"

    @Volatile private var cached: Boolean? = null

    fun isAuthorized(context: Context): Boolean {
        cached?.let { return it }
        val stored = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).getBoolean(Authorized, false)
        cached = stored
        return stored
    }

    fun lastCheckAt(context: Context): Long =
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).getLong(CheckedAt, 0L)

    /** Runs `id -u` through su; the Superuser prompt happens here, on an IO dispatcher. */
    fun requestAuthorization(context: Context): Boolean {
        val result = exec("id -u", 20_000L)
        val granted = result?.trim() == "0"
        cached = granted
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit()
            .putBoolean(Authorized, granted)
            .putLong(CheckedAt, System.currentTimeMillis())
            .apply()
        return granted
    }

    fun forget(context: Context) {
        cached = false
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit()
            .putBoolean(Authorized, false)
            .putLong(CheckedAt, System.currentTimeMillis())
            .apply()
    }

    fun exec(command: String, timeoutMillis: Long = 4_000L): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader().use { it.readText().trim() }
    }.onFailure { Log.w(Tag, "root command unavailable: ${it.javaClass.simpleName}") }.getOrNull()

    /** Hard volume write; the caller re-reads AudioManager to confirm the system accepted it. */
    fun setStreamVolume(context: Context, stream: Int, index: Int): Boolean {
        if (!isAuthorized(context)) return false
        val output = exec("cmd media_session volume --stream $stream --set $index") ?: return false
        return Regex("""(?i)(volume is|set volume|to)\D*$index""").containsMatchIn(output) || output.isNotBlank()
    }

    fun setBrightness(context: Context, value: Int): Boolean {
        if (!isAuthorized(context)) return false
        exec("settings put system screen_brightness_mode 0")
        return exec("settings put system screen_brightness ${value.coerceIn(0, 255)}") != null
    }

    fun whitelistBattery(context: Context): Boolean {
        if (!isAuthorized(context)) return false
        val output = exec("dumpsys deviceidle whitelist +${context.packageName}") ?: return false
        return !output.contains("Unknown package", ignoreCase = true)
    }

    fun grantWriteSettings(context: Context): Boolean {
        if (!isAuthorized(context)) return false
        return exec("appops set ${context.packageName} WRITE_SETTINGS allow") != null
    }
}
