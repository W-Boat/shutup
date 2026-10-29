package com.xiaomanjun.sleepdownschedule.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.xiaomanjun.sleepdownschedule.core.ui.settings.GlassPreferenceSection
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsActionButton
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsGroup
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsInfoRow
import com.xiaomanjun.sleepdownschedule.model.ScheduleConfigEntity
import com.xiaomanjun.sleepdownschedule.feature.guard.SnowLeopardActivity

@Composable
internal fun CourseQuietSettingsGroup(backdrop: Backdrop?, config: ScheduleConfigEntity) {
    val context = LocalContext.current
    GlassPreferenceSection("雪豹闭嘴 · 自动保护") {
        SettingsGroup(backdrop, config, Modifier.fillMaxWidth()) {
            SettingsInfoRow("统一声音保护", "选择固定模板，并在规则中绑定此课表。课表需要有效的开学日期；铃声与通知通常联动。")
            Row(Modifier.fillMaxWidth().padding(14.dp)) {
                SettingsActionButton("打开保护首页", backdrop, onClick = {
                    context.startActivity(Intent(context, SnowLeopardActivity::class.java))
                }, modifier = Modifier.fillMaxWidth(), monochrome = true)
            }
        }
    }
}
