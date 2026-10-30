package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.xiaomanjun.sleepdownschedule.R
import com.xiaomanjun.sleepdownschedule.domain.guard.CareStyles
import com.xiaomanjun.sleepdownschedule.domain.guard.GuardDocument
import com.xiaomanjun.sleepdownschedule.domain.guard.ProtectionWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AI 主动关怀：课前与课中推一条有风格的小通知。
 *
 * 文案优先走用户自己的 AI 接口（`GuardAi.composeCareNote`），失败或未配置时使用本地风格库，
 * 因此离线也有提醒。同一节课的同一阶段只推一次（按规则 + 开始时间 + 课中档位去重）。
 */
internal object GuardCare {
    const val ChannelId = "shutup_care"
    private const val Prefs = "shutup_care_state"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "AI 学习关怀", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "课前与课中的学习提醒，文案由 AI 按风格生成"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
                if (manager.isNotificationPolicyAccessGranted) setBypassDnd(true)
            }
        )
    }

    /** 不阻塞保护引擎：网络生成在独立协程里跑，失败立刻用离线文案兜底。 */
    fun tick(context: Context, document: GuardDocument, windows: List<ProtectionWindow>, now: Long) {
        val care = document.care
        if (!care.enabled) return
        val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        val lead = care.safeLeadMinutes * 60_000L
        val upcoming = windows.filter { it.isCourse && it.classStart > now && it.classStart - now <= lead }.minByOrNull { it.classStart }
        if (upcoming != null && matches(care.onlyCourseNames, upcoming.reason)) {
            val key = "before:${upcoming.classKey}"
            if (!prefs.getBoolean(key, false)) {
                prefs.edit().putBoolean(key, true).apply()
                val minutes = ((upcoming.classStart - now) / 60_000L).coerceAtLeast(1)
                scope.launch { post(context, care.style, document, upcoming.reason, "before", "还有 $minutes 分钟上课", now) }
            }
        }
        val inside = windows.filter { it.isCourse && it.inClass(now) }.maxByOrNull { it.classEnd }
        if (care.duringClass && inside != null && matches(care.onlyCourseNames, inside.reason)) {
            val every = care.safeEveryMinutes * 60_000L
            val bucket = (now - inside.classStart) / every
            if (bucket >= 1) {
                val key = "during:${inside.classKey}|$bucket"
                if (!prefs.getBoolean(key, false)) {
                    prefs.edit().putBoolean(key, true).apply()
                    scope.launch { post(context, care.style, document, inside.reason, "during", "${inside.reason} 进行中", now) }
                }
            }
        }
        // 只保留最近 200 条去重键，避免偏好无限增长。
        if (prefs.all.size > 200) {
            val keep = prefs.all.entries.sortedByDescending { it.key }.take(120).associate { it.key to it.value }
            val editor = prefs.edit().clear()
            keep.forEach { (key, value) -> editor.putBoolean(key, value as Boolean) }
            editor.apply()
        }
    }

    private fun matches(only: List<String>, course: String): Boolean =
        only.isEmpty() || only.any { it.equals(course, ignoreCase = true) }

    private suspend fun post(
        context: Context,
        style: String,
        document: GuardDocument,
        course: String,
        phase: String,
        title: String,
        now: Long
    ) {
        ensureChannel(context)
        val note = document.care.notes[course].orEmpty()
        val prompt = CareStyles.prompt(style, course, note, phase)
        val ai = withTimeoutOrNull(8_000L) {
            if (document.care.customPrompt.isNotBlank()) {
                GuardAi.composeCareNote(context, "${document.care.customPrompt.take(300)}\n$prompt")
            } else {
                GuardAi.composeCareNote(context, prompt)
            }
        }
        val text = ai?.takeIf { it.isNotBlank() } ?: CareStyles.fallback(style, course, phase, now)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            context, 7340, Intent(context, SnowLeopardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = when (style) {
            "gentle" -> "🌿"
            "strict" -> "📌"
            "meme" -> "🤡"
            else -> "🐱"
        }
        val builder = Notification.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_snow_guard)
            .setContentTitle("$icon $title")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        runCatching { manager.notify((now % 100000L).toInt() + 7400, builder.build()) }
    }
}
