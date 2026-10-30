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
import kotlinx.coroutines.flow.first

/**
 * 上课锁机的状态机：决定窗口 → 限时窗内给用户选择 → 到期锁机 → 结束解锁。
 * 由 GuardService 的每次 reconcile 驱动，没有额外轮询。
 */
internal object GuardLockController {
    const val ChannelId = "shutup_lock"
    private const val DecisionNotificationId = 7325

    fun tick(context: Context, document: GuardDocument, windows: List<ProtectionWindow>, now: Long) {
        val store = LockStore(context)
        val session = store.session()
        if (!document.lock.enabled) {
            if (session.active) store.clear()
            return
        }
        val active = windows.filter { it.contains(now) }.maxByOrNull { it.end }
        if (active == null) {
            if (session.active && now > session.endsAt) store.clear()
            return
        }
        val key = "${active.ruleId}|${active.start}"
        if (key in store.exitedWindows()) {
            if (session.active) store.clear()
            return
        }
        if (!session.active || session.windowKey != key) {
            store.save(
                LockStore.Session(
                    active = true,
                    windowKey = key,
                    courseName = active.reason,
                    reason = active.reason,
                    endsAt = active.end,
                    startedAt = now,
                    graceEndsAt = now + document.lock.safeGraceSeconds * 1000L,
                    decided = false,
                    skipped = false
                )
            )
            notifyDecision(context, active, document.lock.safeGraceSeconds)
            return
        }
        if (now > session.endsAt) {
            store.clear()
            return
        }
        if (!session.decided && !session.skipped && now >= session.graceEndsAt && document.lock.autoLockOnTimeout) {
            store.save(session.copy(pendingEntry = true))
            launch(context)
        }
    }

    fun decide(context: Context, lock: Boolean) {
        val store = LockStore(context)
        val session = store.session()
        if (!session.active) return
        store.save(session.copy(decided = false, skipped = !lock, pendingEntry = lock))
        if (!lock) runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(DecisionNotificationId) }
        if (lock) launch(context)
    }

    fun isLocked(context: Context): Boolean {
        val session = LockStore(context).session()
        return session.active && session.decided && !session.skipped &&
            session.endsAt > System.currentTimeMillis()
    }

    /** Only a resumed Activity can acknowledge that the lock screen is actually visible. */
    fun entered(context: Context): Boolean {
        val store = LockStore(context)
        val session = store.session()
        if (!session.active || session.skipped || session.endsAt <= System.currentTimeMillis()) return false
        store.save(session.copy(decided = true, pendingEntry = false))
        return true
    }

    fun launch(context: Context) {
        runCatching { context.startActivity(GuardLockActivity.intent(context)) }
    }

    fun finishSession(context: Context) {
        LockStore(context).clear()
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(DecisionNotificationId) }
    }

    private fun notifyDecision(context: Context, window: ProtectionWindow, graceSeconds: Int) {
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
            context, 7326, GuardLockActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_snow_guard)
            .setContentTitle("${window.reason} 已开始")
            .setContentText("${graceSeconds / 60} 分钟内可以决定是否锁机，超时自动进入锁机")
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
        builder.addAction(action(context, "lock_start", "开始锁机", 7327))
        builder.addAction(action(context, "lock_skip", "本次不锁", 7328))
        runCatching { manager.notify(DecisionNotificationId, builder.build()) }
    }

    private fun action(context: Context, action: String, title: String, request: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_snow_guard),
            title,
            PendingIntent.getService(
                context, request,
                Intent(context, GuardService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()

    /** 读取当前文档的锁机配置；供页面与磁贴使用。 */
    suspend fun currentLock(context: Context) = GuardStore(context).document.first().lock
}
