package com.xiaomanjun.sleepdownschedule.feature.guard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.xiaomanjun.sleepdownschedule.data.guard.GuardStore
import com.xiaomanjun.sleepdownschedule.data.guard.LockStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 锁机期间把非白名单应用切回锁机页。
 *
 * 只订阅窗口切换事件，不读取页面内容（canRetrieveWindowContent=false），隐私面最小化。
 */
class GuardLockAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return
        if (pkg == "com.android.systemui" || pkg == "android") return
        val session = LockStore(this).session()
        if (!session.active || !session.decided || session.skipped) return
        if (session.endsAt in 1 until System.currentTimeMillis()) return
        val document = runCatching {
            runBlocking { GuardStore(this@GuardLockAccessibilityService).document.first() }
        }.getOrNull() ?: return
        if (!document.lock.enabled) return
        if (pkg in document.lock.allowedPackages) return
        runCatching {
            startActivity(
                Intent(this, GuardLockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    override fun onInterrupt() = Unit
}
