package com.xiaomanjun.sleepdownschedule.feature.guard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.xiaomanjun.sleepdownschedule.ScheduleConfigEntity
import com.xiaomanjun.sleepdownschedule.core.ui.settings.*
import com.xiaomanjun.sleepdownschedule.domain.guard.CareStyles
import com.xiaomanjun.sleepdownschedule.domain.guard.GuardDocument
import top.yukonga.miuix.kmp.basic.Slider

internal fun LazyListScope.guardCareItems(
    document: GuardDocument?,
    courses: List<String>,
    backdrop: Backdrop?,
    visualConfig: ScheduleConfigEntity,
    mutate: ((GuardDocument) -> GuardDocument) -> Unit
) {
        item(key = "care-main") {
            document?.let { doc ->
                val care = doc.care
                GlassPreferenceSection("AI 主动关怀") {
                    SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                        SettingsToggleRow(
                            title = "课前与课中提醒",
                            subtitle = "按课程名与你写的吐槽生成一句小提醒；未配置 AI 时使用内置风格文案",
                            checked = care.enabled,
                            backdrop = backdrop,
                            onCheckedChange = { value -> mutate { it.copy(care = it.care.copy(enabled = value)) } }
                        )
                        SettingsValueRow("课前提醒", "提前 ${care.safeLeadMinutes} 分钟", compact = true)
                        Slider(
                            value = care.safeLeadMinutes.toFloat(),
                            onValueChange = { value -> mutate { it.copy(care = it.care.copy(leadMinutes = value.toInt().coerceIn(0, 60))) } },
                            valueRange = 0f..60f,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
                        )
                        SettingsToggleRow(
                            title = "课中也提醒",
                            subtitle = "每隔一段时间根据课程内容再提醒一次",
                            checked = care.duringClass,
                            backdrop = backdrop,
                            onCheckedChange = { value -> mutate { it.copy(care = it.care.copy(duringClass = value)) } }
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(15, 25, 40).forEach { minutes ->
                                SettingsActionButton(
                                    label = "$minutes 分钟",
                                    backdrop = backdrop,
                                    onClick = { mutate { it.copy(care = it.care.copy(duringClassEveryMinutes = minutes)) } },
                                    modifier = Modifier.weight(1f),
                                    monochrome = care.safeEveryMinutes != minutes
                                )
                            }
                        }
                        SettingsValueRow("文案风格", CareStyles.label(care.style), compact = true)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CareStyles.labels.forEach { (id, label) ->
                                SettingsActionButton(
                                    label = label,
                                    backdrop = backdrop,
                                    onClick = { mutate { it.copy(care = it.care.copy(style = id)) } },
                                    modifier = Modifier.weight(1f),
                                    monochrome = care.style != id
                                )
                            }
                        }
                        SettingsTextFieldRow(
                            title = "自定义提示词",
                            value = care.customPrompt,
                            onValueChange = { value -> mutate { it.copy(care = it.care.copy(customPrompt = value.take(300))) } },
                            placeholder = "例如：用我家猫的口吻提醒我"
                        )
                    }
                }
            }
        }
        item(key = "care-notes") {
            document?.let { doc ->
                GlassPreferenceSection("课程吐槽") {
                    SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                        SettingsInfoRow(
                            title = "给每门课写一句吐槽",
                            body = "AI 会把它写进提醒里，让通知更像你自己说话。例如「这老师点名超狠」。"
                        )
                        if (courses.isEmpty()) {
                            SettingsValueRow("暂无课程", "等课表生成保护时段后会自动出现在这里", compact = true)
                        }
                        courses.forEach { course ->
                            SettingsTextFieldRow(
                                title = course,
                                value = doc.care.notes[course].orEmpty(),
                                onValueChange = { value ->
                                    mutate { current ->
                                        current.copy(care = current.care.copy(notes = current.care.notes + (course to value.take(120))))
                                    }
                                },
                                placeholder = "写一句吐槽"
                            )
                        }
                    }
                }
            }
        }

}
