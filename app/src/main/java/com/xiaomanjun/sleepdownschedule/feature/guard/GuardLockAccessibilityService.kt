package com.xiaomanjun.sleepdownschedule.feature.guard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.xiaomanjun.sleepdownschedule.data.guard.GuardStore
import com.xiaomanjun.sleepdownschedule.data.guard.LockStore
import com.xiaomanjun.sleepdownschedule.domain.guard.LockSettings
import kotlinx.coroutines.*

/**
 * 锁机期间把非白名单应用切回锁机页。
 *
 * 只订阅窗口切换事件，不读取页面内容（canRetrieveWindowContent=false），隐私面最小化。
 */
class GuardLockAccessibilityService : AccessibilityService() {
    companion object { @Volatile var connected = false; private set }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settings: LockSettings? = null
    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        scope.launch { GuardStore(this@GuardLockAccessibilityService).document.collect { settings = it.lock } }
    }

    override fun onDestroy() { connected = false; scope.cancel(); super.onDestroy() }
    override fun onUnbind(intent: Intent?): Boolean { connected = false; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return
        if (pkg == "com.android.systemui" || pkg == "android") return
        val session = LockStore(this).session()
        if (!session.active || !session.decided || session.skipped) return
        if (session.endsAt in 1 until System.currentTimeMillis()) return
        val lock = settings ?: return
        if (!lock.enabled && !session.isTest) return
        if (pkg in lock.allowedPackages) return
        runCatching {
            startActivity(
                GuardLockActivity.intent(this, session.windowKey)
            )
        }
    }

    override fun onInterrupt() = Unit
}
