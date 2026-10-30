package com.xiaomanjun.sleepdownschedule.feature.guard

import com.xiaomanjun.sleepdownschedule.CourseScheduleApp
import com.xiaomanjun.sleepdownschedule.data.guard.GuardStore
import com.xiaomanjun.sleepdownschedule.data.guard.LockStore
import com.xiaomanjun.sleepdownschedule.domain.guard.GuardLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal object GuardRecovery {
    fun lastExit(context: android.content.Context): String = runCatching {
        val exit = context.getSystemService(android.app.ActivityManager::class.java)
            ?.getHistoricalProcessExitReasons(context.packageName, 0, 1)?.firstOrNull()
            ?: return "系统暂无可读取的进程退出记录"
        val reason = when (exit.reason) {
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足"
            android.app.ApplicationExitInfo.REASON_CRASH -> "应用崩溃"
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生进程崩溃"
            android.app.ApplicationExitInfo.REASON_ANR -> "应用无响应"
            android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "用户或系统停止（不能单凭此项区分强停与清理）"
            else -> "系统原因 ${exit.reason}"
        }
        "${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(exit.timestamp))} · $reason"
    }.getOrDefault("系统未允许读取进程退出记录")

    fun onForeground(app: CourseScheduleApp) {
        app.applicationScope.launch(Dispatchers.IO) {
            val document = GuardStore(app).document.first()
            if (document.rootAccessibility && GuardRootBridge.isAuthorized(app) && !GuardLockAccessibilityService.connected)
                runCatching { GuardRootBridge.enableLockAccessibility(app) }
            if (document.rootKeepAlive && GuardRootBridge.isAuthorized(app)) runCatching { GuardRootKeeper.start(app) }
                .onFailure { GuardEngine.status.value = GuardEngine.status.value.copy(errors = listOf(it.message ?: "Root 守护恢复失败")) }
            if (GuardLifecycle.needsService(document, LockStore(app).session(), System.currentTimeMillis()))
                GuardEngine.start(app)
        }
    }
}
