package com.xiaomanjun.sleepdownschedule.feature.guard

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kyant.backdrop.Backdrop
import com.xiaomanjun.sleepdownschedule.AppState
import com.xiaomanjun.sleepdownschedule.R
import com.xiaomanjun.sleepdownschedule.core.ui.chrome.DockScrollPadding
import com.xiaomanjun.sleepdownschedule.core.ui.designsystem.SleepDownSecondaryPageList
import com.xiaomanjun.sleepdownschedule.core.ui.scaffold.detailContentTopPadding
import com.xiaomanjun.sleepdownschedule.core.ui.scaffold.settingsVisualConfig
import com.xiaomanjun.sleepdownschedule.core.ui.settings.GlassPreferenceSection
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsActionRow
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsGroup
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsInfoRow

/**
 * 一条权限：和系统「应用权限」页一样，显示状态 + 一键去授权。
 */
internal data class GuardPermission(
    val id: String,
    val title: String,
    val detail: String,
    val granted: Boolean,
    val actionLabel: String,
    val action: () -> Unit
)

internal object GuardPermissions {
    const val AccessibilityServiceClass = "com.xiaomanjun.sleepdownschedule.feature.guard.GuardLockAccessibilityService"

    fun items(context: Context, requestBluetooth: () -> Unit): List<GuardPermission> {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val appOps = context.getSystemService(AppOpsManager::class.java)
        fun open(action: String, packageUri: Boolean = false) {
            runCatching {
                context.startActivity(
                    Intent(action).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (packageUri) data = Uri.parse("package:${context.packageName}")
                    }
                )
            }
        }
        fun usageGranted(): Boolean = runCatching {
            val mode = appOps?.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            ) ?: AppOpsManager.MODE_DEFAULT
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        fun accessibilityGranted(): Boolean = runCatching {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            enabled.split(':').any { it.equals("${context.packageName}/$AccessibilityServiceClass", true) }
        }.getOrDefault(false)
        return listOf(
            GuardPermission(
                id = "notification",
                title = "通知",
                detail = "常驻状态、保护前提醒、AI 关怀通知都依赖通知权限",
                granted = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                actionLabel = "去开启"
            ) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            },
            GuardPermission(
                id = "dnd",
                title = "勿扰访问",
                detail = "用于本应用的静音规则与铃声／震动切换",
                granted = notificationManager?.isNotificationPolicyAccessGranted == true,
                actionLabel = "去授权"
            ) { open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
            GuardPermission(
                id = "listener",
                title = "通知使用权",
                detail = "暂停第三方播放器、读取媒体会话时使用",
                granted = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
                actionLabel = "去授权"
            ) { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            GuardPermission(
                id = "exact_alarm",
                title = "精确闹钟",
                detail = "让保护时段与锁机决定时间准点触发",
                granted = Build.VERSION.SDK_INT < 31 ||
                    context.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == true,
                actionLabel = "去授权"
            ) { open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) },
            GuardPermission(
                id = "write_settings",
                title = "修改系统设置",
                detail = "亮度上限与亮度锁定需要该权限",
                granted = Settings.System.canWrite(context),
                actionLabel = "去授权"
            ) { open(Settings.ACTION_MANAGE_WRITE_SETTINGS, true) },
            GuardPermission(
                id = "overlay",
                title = "显示在其他应用上层",
                detail = "1px 保活窗口与锁机防切出需要悬浮窗权限",
                granted = Settings.canDrawOverlays(context),
                actionLabel = "去授权"
            ) { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) },
            GuardPermission(
                id = "battery",
                title = "忽略电池优化",
                detail = "避免保护与锁机在后台被系统冻结",
                granted = power?.isIgnoringBatteryOptimizations(context.packageName) == true,
                actionLabel = "去授权"
            ) { open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true) },
            GuardPermission(
                id = "autostart",
                title = "自启动／后台运行",
                detail = "各厂商的自启动开关位置不同，请在本应用信息页里允许后台运行（系统无法查询，标为需确认）",
                granted = false,
                actionLabel = "去设置"
            ) { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) },
            GuardPermission(
                id = "accessibility",
                title = "无障碍服务（上课锁机）",
                detail = "锁机时把页面拉回前台、阻止切到未授权应用",
                granted = accessibilityGranted(),
                actionLabel = "去授权"
            ) { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            GuardPermission(
                id = "usage",
                title = "使用情况访问（上课锁机）",
                detail = "判断当前前台应用是否在白名单内",
                granted = usageGranted(),
                actionLabel = "去授权"
            ) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
            GuardPermission(
                id = "bluetooth",
                title = "附近的设备",
                detail = "读取已配对耳机，用于可信设备放行",
                granted = Build.VERSION.SDK_INT < 31 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
                actionLabel = "去授权"
            ) { requestBluetooth() },
            GuardPermission(
                id = "root",
                title = "Root",
                detail = if (GuardRootBridge.isAuthorized(context)) "已授权，音量与亮度使用系统直写" else "可选；未授权时自动降级普通控制",
                granted = GuardRootBridge.isAuthorized(context),
                actionLabel = "请求"
            ) { },
            GuardPermission(
                id = "shizuku",
                title = "Shizuku",
                detail = "可选；用于增强路由核对与媒体音量写入",
                granted = EnhancedAudioBridge.authorized(),
                actionLabel = "请求"
            ) { runCatching { EnhancedAudioBridge.requestPermission() } }
        )
    }

    fun summaryBadge(context: Context): String {
        val items = items(context) { }
        val granted = items.count { it.granted }
        return "$granted/${items.size} 已就绪"
    }
}

@Composable
fun GuardPermissionsScreen(state: AppState, backdrop: Backdrop?) {
    val context = LocalContext.current
    val visualConfig = settingsVisualConfig(state.config)
    // 从系统设置页返回后重新计算状态，避免「授权完了还显示未授权」。
    var refreshTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        refreshTick++
    }
    val bluetoothLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }
    SleepDownSecondaryPageList(contentTopPadding = detailContentTopPadding(), contentBottomPadding = DockScrollPadding) {
        item(key = "guard-permissions") {
            val items = androidx.compose.runtime.remember(refreshTick) {
                GuardPermissions.items(context) {
                    if (Build.VERSION.SDK_INT >= 31) bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            GlassPreferenceSection("权限清单") {
                SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                    SettingsInfoRow(
                        title = "已就绪 ${items.count { it.granted }}/${items.size}",
                        body = "逐项授权即可。带「可选」的能力缺失时功能会自动降级，不会影响其他保护逻辑。",
                        badgeText = "逐项授权"
                    )
                    items.forEach { permission ->
                        SettingsActionRow(
                            title = (if (permission.granted) "● " else "○ ") + permission.title,
                            subtitle = permission.detail,
                            buttonText = if (permission.granted) "已授权" else permission.actionLabel,
                            iconRes = R.drawable.ic_snow_guard,
                            backdrop = backdrop,
                            badgeText = if (permission.granted) null else "待处理"
                        ) {
                            when (permission.id) {
                                "root" -> {
                                    Thread {
                                        GuardRootBridge.requestAuthorization(context)
                                        GuardRootBridge.whitelistBattery(context)
                                    }.start()
                                }
                                else -> permission.action()
                            }
                        }
                    }
                }
            }
        }
    }
}
