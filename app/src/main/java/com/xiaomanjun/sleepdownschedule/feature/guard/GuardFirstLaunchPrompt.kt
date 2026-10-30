package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xiaomanjun.sleepdownschedule.ScheduleConfigEntity
import com.xiaomanjun.sleepdownschedule.SettingsDetailActivity
import com.xiaomanjun.sleepdownschedule.app.ui.SettingsDetailPageExtra
import com.xiaomanjun.sleepdownschedule.feature.settings.SettingsPage
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.window.WindowDialog
import com.xiaomanjun.sleepdownschedule.glass.ui.appUsesDarkTheme

/** Explain permissions once, after the first timetable frame is ready. No permissions are forced. */
@Composable
internal fun GuardFirstLaunchPrompt(ready: Boolean, config: ScheduleConfigEntity) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("shutup_onboarding", Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(!prefs.getBoolean("permissions_introduced", false)) }
    var openPermissionsAfterDismiss by remember { mutableStateOf(false) }
    fun dismiss(openPermissions: Boolean = false) {
        if (!show) return
        openPermissionsAfterDismiss = openPermissions
        prefs.edit().putBoolean("permissions_introduced", true).apply()
        show = false
    }
    if (ready) MiuixTheme(colors = if (appUsesDarkTheme(config)) darkColorScheme() else lightColorScheme()) {
        WindowDialog(
            show = show,
            title = "先了解保护权限",
            summary = "声音保护与上课锁机分别设置。通知、勿扰等权限由你逐项选择；Root、Shizuku 和无障碍为可选能力，未授权时会明确显示限制。",
            onDismissRequest = { dismiss() },
            onDismissFinished = {
                if (openPermissionsAfterDismiss) {
                    openPermissionsAfterDismiss = false
                    context.startActivity(Intent(context, SettingsDetailActivity::class.java)
                        .putExtra(SettingsDetailPageExtra, SettingsPage.GuardPermissions.name))
                }
            }
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton("稍后设置", onClick = { dismiss() }, modifier = Modifier.weight(1f))
                TextButton("权限管理", onClick = { dismiss(true) }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}
