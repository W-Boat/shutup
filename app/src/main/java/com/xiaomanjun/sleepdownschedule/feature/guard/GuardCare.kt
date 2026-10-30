package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.xiaomanjun.sleepdownschedule.R
import com.xiaomanjun.sleepdownschedule.domain.guard.CareStyles
import com.xiaomanjun.sleepdownschedule.domain.guard.CareDelivery
import com.xiaomanjun.sleepdownschedule.domain.guard.CareDeliveryState
import com.xiaomanjun.sleepdownschedule.data.guard.GuardStore
import com.xiaomanjun.sleepdownschedule.data.guard.GuardDatabase
import com.xiaomanjun.sleepdownschedule.data.guard.GuardEvent
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
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
    private val monitor = Any()
    private val inFlight = mutableSetOf<String>()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun state(prefs: android.content.SharedPreferences, key: String): CareDeliveryState =
        when (val raw = prefs.all[key]) {
            true -> CareDeliveryState(delivered = true) // Old Boolean keys remain deduplicated.
            is String -> runCatching { json.decodeFromString<CareDeliveryState>(raw) }.getOrDefault(CareDeliveryState())
            else -> CareDeliveryState()
        }

    fun nextRetryAt(context: Context, now: Long): Long = synchronized(monitor) {
        val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        prefs.all.keys.map { state(prefs, it) }.filter { !it.delivered && it.attempts in 1..2 && it.retryAt > now }
            .minOfOrNull { it.retryAt } ?: Long.MAX_VALUE
    }

    private fun enqueue(context: Context, document: GuardDocument, course: String, phase: String,
        title: String, key: String, expires: Long, now: Long) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        synchronized(monitor) {
            if (key in inFlight || !CareDelivery.canAttempt(state(prefs, key), now, expires)) return
            inFlight += key
        }
        scope.launch {
            try {
                val failure = runCatching { post(app, document.care.style, document, course, phase, title, now, key, expires) }
                    .fold({ it }, { it.message ?: "通知投递失败" })
                val at = System.currentTimeMillis()
                synchronized(monitor) {
                    val next = CareDelivery.complete(state(prefs, key), failure == null, at)
                    check(prefs.edit().putString(key, json.encodeToString(next)).commit()) { "关怀投递状态保存失败" }
                }
                val dao = GuardDatabase.get(app).dao()
                dao.insert(GuardEvent(time = at, message = failure ?: "关怀通知已提交", title = "AI 关怀",
                    reason = "$course · $phase", error = failure.orEmpty()))
                dao.trimEvents()
            } finally {
                synchronized(monitor) { inFlight -= key }
                GuardEngine.reconcile(app)
            }
        }
    }

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
        val lead = care.safeLeadMinutes * 60_000L
        val upcoming = windows.filter { it.isCourse && it.classStart > now && it.classStart - now <= lead }.minByOrNull { it.classStart }
        if (upcoming != null && matches(care.onlyCourseNames, upcoming.reason)) {
            val key = "before:${upcoming.classKey}"
                val minutes = ((upcoming.classStart - now) / 60_000L).coerceAtLeast(1)
                enqueue(context, document, upcoming.reason, "before", "还有 $minutes 分钟上课", key, upcoming.classStart, now)
        }
        val inside = windows.filter { it.isCourse && it.inClass(now) }.maxByOrNull { it.classEnd }
        if (care.duringClass && inside != null && matches(care.onlyCourseNames, inside.reason)) {
            val every = care.safeEveryMinutes * 60_000L
            val bucket = (now - inside.classStart) / every
            if (bucket >= 1) {
                val key = "during:${inside.classKey}|$bucket"
                enqueue(context, document, inside.reason, "during", "${inside.reason} 进行中", key,
                    minOf(inside.classEnd, inside.classStart + (bucket + 1) * every), now)
            }
        }
        // 只保留最近 200 条去重键，避免偏好无限增长。
        val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        synchronized(monitor) { if (prefs.all.size > 200) {
            val keep = prefs.all.entries.sortedByDescending { it.key.substringAfter('|').substringBefore('|').toLongOrNull() ?: 0 }
                .take(120).associate { it.key to it.value }
            val editor = prefs.edit().clear()
            keep.forEach { (key, value) -> when (value) { is Boolean -> editor.putBoolean(key, value); is String -> editor.putString(key, value) } }
            editor.commit()
        } }
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
        now: Long,
        key: String,
        expires: Long
    ): String? {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return "系统通知服务不可用"
        blockedReason(manager)?.let { return it }
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
        if (System.currentTimeMillis() >= expires) return "提醒时段已结束，已取消投递"
        if (!GuardStore(context).document.first().care.enabled) return "AI 关怀已关闭，已取消投递"
        blockedReason(manager)?.let { return it }
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
        val id = key.hashCode()
        manager.notify(id, builder.build())
        return if (manager.activeNotifications.any { it.id == id }) null else "通知未出现在系统活动列表，将有限补发"
    }

    private fun blockedReason(manager: NotificationManager): String? = when {
        !manager.areNotificationsEnabled() -> "通知权限未开启，将在时段内最多尝试 3 次"
        manager.getNotificationChannel(ChannelId)?.importance == NotificationManager.IMPORTANCE_NONE -> "AI 关怀通知渠道已关闭"
        manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS -> "系统勿扰仅允许闹钟或禁止全部通知，等待补发"
        manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            manager.getNotificationChannel(ChannelId)?.canBypassDnd() != true -> "系统勿扰正在拦截关怀通知，等待补发"
        else -> null
    }
}
