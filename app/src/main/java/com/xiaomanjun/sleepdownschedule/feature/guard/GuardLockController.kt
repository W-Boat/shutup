package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.xiaomanjun.sleepdownschedule.R
import com.xiaomanjun.sleepdownschedule.data.guard.GuardStore
import com.xiaomanjun.sleepdownschedule.data.guard.LockStore
import com.xiaomanjun.sleepdownschedule.domain.guard.GuardDocument
import com.xiaomanjun.sleepdownschedule.domain.guard.ProtectionWindow
import com.xiaomanjun.sleepdownschedule.domain.guard.LockSession
import com.xiaomanjun.sleepdownschedule.domain.guard.LockStateMachine
import com.xiaomanjun.sleepdownschedule.domain.guard.LockCourseScope
import kotlinx.coroutines.flow.first

/**
 * 上课锁机的状态机：决定窗口 → 限时窗内给用户选择 → 到期锁机 → 结束解锁。
 * 由 GuardService 的每次 reconcile 驱动，没有额外轮询。
 */
internal object GuardLockController {
    const val ChannelId = "shutup_lock"
    private const val DecisionNotificationId = 7325
    const val WindowKey = "lock_window_key"
    const val ExpiresAt = "lock_expires_at"
    private var lastLaunch = 0L

    fun tick(context: Context, document: GuardDocument, windows: List<ProtectionWindow>, now: Long) {
        val store = LockStore(context)
        if (!document.lock.enabled || document.bypassUntil > now || document.skipUntil > now) {
            finishSession(context)
            return
        }
        val manual = if (document.lock.lockOnManualProtect && document.manualUntil > now)
            ProtectionWindow("manual", "手动保护", document.selectedTemplateId.orEmpty(),
                document.manualUntil - 60 * 60_000L, document.manualUntil) else null
        val active = (windows + listOfNotNull(manual)).filter { it.inClass(now) && LockCourseScope.allows(document.lock, it) }
            .maxByOrNull { it.classEnd }
        if (active == null) {
            finishSession(context)
            return
        }
        val key = active.classKey
        var created = false
        var requestEntry = false
        val session = store.transition { old ->
            val next = LockStateMachine.begin(old,
                LockSession(
                    active = true,
                    windowKey = key,
                    courseName = active.reason,
                    reason = active.reason,
                    endsAt = active.classEnd,
                    startedAt = now,
                    graceEndsAt = minOf(active.classEnd, active.classStart + document.lock.safeGraceSeconds * 1000L),
                    decided = false,
                    skipped = false
                ), store.exitedWindows())
            created = next.windowKey != old.windowKey || (!old.active && next.active)
            val checked = if (next.decided && context.getSystemService(android.app.ActivityManager::class.java)?.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE)
                next.copy(decided = false, pendingEntry = true) else next
            LockStateMachine.timeout(checked, now, document.lock.autoLockOnTimeout).also {
                requestEntry = it.pendingEntry && !old.pendingEntry
            }
        }
        if (created || requestEntry) {
            notifyDecision(context, active, document.lock.safeGraceSeconds, session.pendingEntry)
        }
        if (session.active && requestEntry && now - lastLaunch >= 30_000L) {
            lastLaunch = now
            launch(context)
        }
    }

    fun decide(context: Context, lock: Boolean, key: String, expires: Long) {
        val store = LockStore(context)
        val now = System.currentTimeMillis()
        if (expires <= now) return
        var accepted = false
        store.transition { old ->
            accepted = LockStateMachine.valid(old, key, now) && old.endsAt == expires && !old.decided && !old.skipped
            if (accepted) LockStateMachine.choose(old, key, now, lock) else old
        }
        if (!accepted) return
        if (!lock) runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(DecisionNotificationId) }
        if (lock) launch(context)
    }

    fun isLocked(context: Context): Boolean {
        val session = LockStore(context).session()
        return session.active && session.decided && !session.skipped &&
            session.endsAt > System.currentTimeMillis()
    }

    /** Only a resumed Activity can acknowledge that the lock screen is actually visible. */
    fun entered(context: Context, key: String): Boolean {
        val store = LockStore(context)
        val now = System.currentTimeMillis()
        val next = store.transition { LockStateMachine.entered(it, key, now) }
        return LockStateMachine.valid(next, key, now) && next.decided && !next.skipped
    }

    fun launch(context: Context) {
        val session = LockStore(context).session()
        if (session.active && !session.skipped && session.endsAt > System.currentTimeMillis())
            runCatching { context.startActivity(GuardLockActivity.intent(context, session.windowKey)) }
    }

    fun finishSession(context: Context) {
        LockStore(context).clear()
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(DecisionNotificationId) }
    }

    private fun notifyDecision(context: Context, window: ProtectionWindow, graceSeconds: Int, pending: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "上课锁机", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "上课后决定是否锁机的提醒"
                setSound(null, null)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                if (manager.isNotificationPolicyAccessGranted) setBypassDnd(true)
            }
        )
        val open = PendingIntent.getActivity(
            context, 7326, GuardLockActivity.intent(context, window.classKey),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_snow_guard)
            .setContentTitle("${window.reason} · ${if (pending) "等待进入锁机" else "决定是否锁机"}")
            .setContentText(if (pending) "尚未确认锁定，点此进入；后台拉起可能受系统限制" else "${graceSeconds} 秒内可以决定是否锁机")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "${window.reason} 已经开始。\n现在进入锁机可以把注意力留在这一节；\n" +
                        "${graceSeconds} 秒内点「开始锁机」立即进入，点「本次不锁」跳过本节。"
                )
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        builder.addAction(action(context, "lock_start", "开始锁机", 7327, window))
        builder.addAction(action(context, "lock_skip", "本次不锁", 7328, window))
        runCatching { manager.notify(DecisionNotificationId, builder.build()) }
    }

    private fun action(context: Context, action: String, title: String, request: Int, window: ProtectionWindow): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_snow_guard),
            title,
            PendingIntent.getService(
                context, request,
                Intent(context, GuardService::class.java).setAction(action)
                    .setData(android.net.Uri.parse("shutup://lock/action/${android.net.Uri.encode(window.classKey)}/$action"))
                    .putExtra(WindowKey, window.classKey).putExtra(ExpiresAt, window.classEnd),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()

    /** 读取当前文档的锁机配置；供页面与磁贴使用。 */
    suspend fun currentLock(context: Context) = GuardStore(context).document.first().lock
}
