package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xiaomanjun.sleepdownschedule.SettingsDetailActivity
import com.xiaomanjun.sleepdownschedule.app.ui.SettingsDetailPageExtra
import com.xiaomanjun.sleepdownschedule.feature.settings.SettingsPage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Explain permissions once, after the first timetable frame is ready. No permissions are forced. */
@Composable
internal fun GuardFirstLaunchPrompt(ready: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("shutup_onboarding", Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(!prefs.getBoolean("permissions_introduced", false)) }
    fun dismiss() {
        prefs.edit().putBoolean("permissions_introduced", true).apply()
        show = false
    }
    if (ready && show) MiuixTheme {
        Dialog(onDismissRequest = ::dismiss) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("先了解保护权限", style = MiuixTheme.textStyles.title3)
                    Text("声音保护与上课锁机分别设置。通知、勿扰等权限由你逐项选择；Root、Shizuku 和无障碍为可选能力，未授权时会明确显示限制。")
                    TextButton("查看权限管理", onClick = {
                        dismiss()
                        context.startActivity(Intent(context, SettingsDetailActivity::class.java)
                            .putExtra(SettingsDetailPageExtra, SettingsPage.GuardPermissions.name))
                    }, modifier = Modifier.fillMaxWidth())
                    TextButton("稍后设置", onClick = ::dismiss, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
