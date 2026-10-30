package com.xiaomanjun.sleepdownschedule.feature.guard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.xiaomanjun.sleepdownschedule.AppState
import com.xiaomanjun.sleepdownschedule.ScheduleConfigEntity
import com.xiaomanjun.sleepdownschedule.core.ui.settings.*
import com.xiaomanjun.sleepdownschedule.domain.guard.*

internal fun LazyListScope.lockCourseScopeItems(
    document: GuardDocument?,
    schedules: AppState,
    expanded: Boolean,
    onExpand: () -> Unit,
    backdrop: Backdrop?,
    config: ScheduleConfigEntity,
    mutate: ((GuardDocument) -> GuardDocument) -> Unit
) {
    val doc = document ?: return
    val courses = schedules.allCourses.ifEmpty { schedules.courses }.filter { it.id != 0L }
        .distinctBy { LockCourseScope.key(it.scheduleId, it.id) }
        .sortedWith(compareBy({ it.scheduleId }, { it.weekday }, { it.periods.minOrNull() ?: 0 }, { it.name }))
    val available = courses.map { LockCourseScope.key(it.scheduleId, it.id) }.toSet()
    val missing = (doc.lock.courseAllowlist + doc.lock.courseDenylist).distinct().count { it !in available }
    item(key = "lock-course-scope") {
        GlassPreferenceSection("锁机课程范围") {
            SettingsGroup(backdrop, config, Modifier.fillMaxWidth()) {
                SettingsInfoRow("课程白名单 ${doc.lock.courseAllowlist.size} · 黑名单 ${doc.lock.courseDenylist.size}",
                    "白名单为空：全部课表课程参与锁机，但排除黑名单。白名单非空：只对选中的课程锁机，黑名单优先。" +
                        "\n独立读取所有课表，无需建立声音保护规则；不影响静音、AI 关怀、固定时段或手动保护。")
                if (missing > 0) SettingsInfoRow("有 $missing 项课程选择已失效",
                    "课表删除或覆盖导入可能生成新课程编号，请重新选择；失效白名单不会自动放宽成全部课程。")
                SettingsActionRow("从课表选择课程", "已读取 ${courses.size} 条课程，可区分同名课程与不同课表",
                    if (expanded) "收起" else "选择", backdrop = backdrop, onClick = onExpand)
                if (doc.lock.courseAllowlist.isNotEmpty()) SettingsActionRow("清空课程白名单", "恢复全部课表课程参与，仍排除黑名单",
                    "清空", backdrop = backdrop, neutral = true) { mutate { it.copy(lock = it.lock.copy(courseAllowlist = emptyList())) } }
                if (doc.lock.courseDenylist.isNotEmpty()) SettingsActionRow("清空课程黑名单", "取消这些课程的锁机排除项",
                    "清空", backdrop = backdrop, neutral = true) { mutate { it.copy(lock = it.lock.copy(courseDenylist = emptyList())) } }
                if (expanded && courses.isEmpty()) SettingsInfoRow("暂无课程", "先在课表中导入或创建课程，再回来选择。")
            }
        }
    }
    if (expanded) items(courses, key = { "lock-course-${it.scheduleId}:${it.id}" }) { course ->
        val key = LockCourseScope.key(course.scheduleId, course.id)
        val selection = when (key) {
            in doc.lock.courseDenylist -> "黑名单 · 不锁机"
            in doc.lock.courseAllowlist -> "白名单 · 参与锁机"
            else -> if (doc.lock.courseAllowlist.isEmpty()) "默认 · 参与锁机" else "默认 · 不在白名单"
        }
        SettingsGroup(backdrop, config, Modifier.fillMaxWidth()) {
            SettingsInfoRow(course.name, "${schedules.schedules.find { it.id == course.scheduleId }?.name ?: "课表 ${course.scheduleId}"}" +
                " · 星期 ${course.weekday} · 节次 ${course.periods.joinToString()}\n$selection")
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("默认", "白名单", "黑名单").forEachIndexed { index, label ->
                    val selected = when (index) {
                        1 -> key in doc.lock.courseAllowlist
                        2 -> key in doc.lock.courseDenylist
                        else -> key !in doc.lock.courseAllowlist && key !in doc.lock.courseDenylist
                    }
                    SettingsActionButton(label, backdrop, onClick = {
                        mutate { current -> current.copy(lock = current.lock.copy(
                            courseAllowlist = current.lock.courseAllowlist.filterNot { it == key } + if (index == 1) listOf(key) else emptyList(),
                            courseDenylist = current.lock.courseDenylist.filterNot { it == key } + if (index == 2) listOf(key) else emptyList()
                        )) }
                    }, modifier = Modifier.weight(1f), monochrome = !selected)
                }
            }
        }
    }
}
