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

    internal data class RootResult(val exitCode: Int, val output: String)

    /** 保留退出码：su 失败时输出可能仍非空，不能只看文本。 */
    fun run(command: String, timeoutMillis: Long = 4_000L): RootResult? = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        RootResult(process.exitValue(), process.inputStream.bufferedReader().use { it.readText().trim() })
    }.onFailure { Log.w(Tag, "root command unavailable: ${it.javaClass.simpleName}") }.getOrNull()

    fun exec(command: String, timeoutMillis: Long = 4_000L): String? =
        run(command, timeoutMillis)?.takeIf { it.exitCode == 0 }?.output

    /** Hard volume write; the caller re-reads AudioManager to confirm the system accepted it. */
    fun setStreamVolume(context: Context, stream: Int, index: Int): Boolean {
        if (!isAuthorized(context)) return false
        val result = run("cmd media_session volume --stream $stream --set $index") ?: return false
        return result.exitCode == 0
    }

    fun setBrightness(context: Context, value: Int): Boolean {
        if (!isAuthorized(context)) return false
        return run("settings put system screen_brightness ${value.coerceIn(0, 255)}")?.exitCode == 0
    }

    fun setBrightnessMode(context: Context, value: Int): Boolean {
        if (!isAuthorized(context) || value !in 0..1) return false
        return run("settings put system screen_brightness_mode $value")?.exitCode == 0
    }

    fun whitelistBattery(context: Context): Boolean {
        if (!isAuthorized(context)) return false
        val result = run("dumpsys deviceidle whitelist +${context.packageName}") ?: return false
        return result.exitCode == 0 && !result.output.contains("Unknown package", ignoreCase = true)
    }

    fun grantWriteSettings(context: Context): Boolean {
        if (!isAuthorized(context)) return false
        return run("appops set ${context.packageName} WRITE_SETTINGS allow")?.exitCode == 0
    }
}
