package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.xiaomanjun.sleepdownschedule.R
import com.xiaomanjun.sleepdownschedule.domain.guard.ProtectionWindow

/**
 * Pre-protection reminder: a toast for "I am looking at the phone" plus a heads-up notification
 * for "the phone is in my pocket". Both fire before the volume is touched so the user can cancel.
 */
internal object GuardReminder {
    const val ChannelId = "shutup_guard_alert"
    const val NotificationId = 7319

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "保护前提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "静音保护开始前的倒计时提醒"
                setSound(null, null)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                if (manager.isNotificationPolicyAccessGranted) setBypassDnd(true)
            }
        )
    }

    fun show(context: Context, window: ProtectionWindow, seconds: Int, templateName: String) {
        val text = if (seconds > 0) "${seconds} 秒后开始静音保护 · $templateName" else "即将开始静音保护 · $templateName"
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show() }
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 7313, Intent(context, SnowLeopardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_snow_guard)
            .setContentTitle("ShutUp 课程表 · 保护即将开始")
            .setContentText("${window.reason}｜$templateName")
            .setStyle(Notification.BigTextStyle().bigText(
                "$text\n原因：${window.reason}\n模板：$templateName\n音量、亮度将按模板限制；可以在通知里取消。"
            ))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter((seconds.coerceIn(0, 120) + 25) * 1000L)
        builder.addAction(action(context, "bypass", "取消 5 分钟", 7350))
        builder.addAction(action(context, "protect", "立即开始", 7351))
        runCatching { manager.notify(NotificationId, builder.build()) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NotificationId) }
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
}
