package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kyant.backdrop.Backdrop
import com.xiaomanjun.sleepdownschedule.AppState
import com.xiaomanjun.sleepdownschedule.CourseScheduleApp
import com.xiaomanjun.sleepdownschedule.core.ui.chrome.DockScrollPadding
import com.xiaomanjun.sleepdownschedule.core.ui.designsystem.SleepDownSecondaryPageList
import com.xiaomanjun.sleepdownschedule.core.ui.scaffold.detailContentTopPadding
import com.xiaomanjun.sleepdownschedule.core.ui.scaffold.settingsVisualConfig
import com.xiaomanjun.sleepdownschedule.core.ui.settings.GlassPreferenceSection
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsActionButton
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsActionRow
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsGroup
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsInfoRow
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsNavigationRow
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsToggleRow
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsValueRow
import com.xiaomanjun.sleepdownschedule.data.guard.GuardDatabase
import com.xiaomanjun.sleepdownschedule.data.guard.GuardStore
import com.xiaomanjun.sleepdownschedule.domain.guard.GuardDocument
import com.xiaomanjun.sleepdownschedule.domain.guard.GuardRule
import com.xiaomanjun.sleepdownschedule.domain.guard.GuardTemplate
import com.xiaomanjun.sleepdownschedule.domain.guard.Trigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The single sound-protection page of ShutUp 课程表. It lives in the course-table navigation, so
 * protection and timetable share one process, one theme and one back stack.
 */
@Composable
fun GuardSettingsScreen(
    state: AppState,
    backdrop: Backdrop?,
    highlightAction: String? = null,
    onActionConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as CourseScheduleApp
    val scope = rememberCoroutineScope()
    val store = remember { GuardStore(context) }
    var document by remember { mutableStateOf<GuardDocument?>(null) }
    val status by GuardEngine.status.collectAsState()
    val events by GuardDatabase.get(context).dao().observeEvents().collectAsState(initial = emptyList())
    var message by remember { mutableStateOf<String?>(null) }
    var editingRule by remember { mutableStateOf<GuardRule?>(null) }
    var newRule by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<GuardTemplate?>(null) }
    var rootBusy by remember { mutableStateOf(false) }
    val visualConfig = settingsVisualConfig(state.config)

    fun run(action: suspend () -> Unit) = scope.launch {
        try { action() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; message = e.message ?: "操作失败" }
    }
    fun mutate(transform: (GuardDocument) -> GuardDocument) = run {
        store.update(transform)
        GuardEngine.start(context)
    }
    fun command(action: String) = run {
        GuardEngine.command(context, action)
        GuardEngine.start(context)
        GuardEngine.reconcile(context, true)
    }

    LaunchedEffect(Unit) {
        app.repository.ensureDefaults()
        try { store.document.collect { document = it } } catch (e: Exception) {
            message = "规则加载失败，已有数据未清除：${e.message}"
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        run {
            val doc = withContext(Dispatchers.IO) { store.document.first() }
            if (doc.rules.any { it.enabled } || doc.manualUntil > System.currentTimeMillis()) GuardEngine.start(context)
            else withContext(Dispatchers.IO) { GuardEngine.reconcile(context, true) }
        }
    }
    LaunchedEffect(highlightAction) {
        highlightAction?.let { action -> command(action); onActionConsumed() }
    }

    SleepDownSecondaryPageList(contentTopPadding = detailContentTopPadding(), contentBottomPadding = DockScrollPadding) {
        item(key = "guard-status") {
            val protecting = status.title == "正在保护" || status.title == "可信耳机放行"
            GlassPreferenceSection("今日保护") {
                SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                    SettingsInfoRow(
                        title = status.title,
                        body = buildString {
                            append(status.applied.ifBlank { status.reasons.joinToString("、").ifBlank { "尚未进入保护时段" } })
                            append("\n").append(status.route)
                            status.endAt?.let { append("\n本次结束 ").append(clock(it)) }
                            status.next?.let { append(" · 下次核对 ").append(clock(it)) }
                            status.errors.forEach { append("\n⚠ ").append(it) }
                        }
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsActionButton(
                            label = if (protecting) "结束保护" else "开始保护",
                            backdrop = backdrop,
                            onClick = { command(if (protecting) "end" else "protect") },
                            modifier = Modifier.weight(1f),
                            monochrome = !protecting
                        )
                        SettingsActionButton(
                            label = "放行 5 分钟",
                            backdrop = backdrop,
                            onClick = { command("bypass") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    SettingsValueRow("当前模板", status.templateName, compact = true)
                }
            }
        }
        item(key = "guard-reminder") {
            document?.let { doc ->
                GlassPreferenceSection("保护前提醒") {
                    SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                        SettingsToggleRow(
                            title = "提前提醒",
                            subtitle = "每次静音保护开始前，用 Toast 与通知弹窗提醒，可一键取消",
                            checked = doc.preReminderSeconds > 0,
                            backdrop = backdrop,
                            onCheckedChange = { enabled -> mutate { it.copy(preReminderSeconds = if (enabled) 10 else 0) } }
                        )
                        if (doc.preReminderSeconds > 0) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(5, 10, 30).forEach { seconds ->
                                    SettingsActionButton(
                                        label = "$seconds 秒",
                                        backdrop = backdrop,
                                        onClick = { mutate { it.copy(preReminderSeconds = seconds) } },
                                        modifier = Modifier.weight(1f),
                                        monochrome = doc.preReminderSeconds != seconds
                                    )
                                }
                            }
                            SettingsActionRow(
                                title = "试一次提醒",
                                subtitle = "立即弹出一条示例提醒，确认通知权限与声音策略",
                                buttonText = "预览",
                                iconRes = com.xiaomanjun.sleepdownschedule.R.drawable.ic_snow_guard,
                                backdrop = backdrop
                            ) { command("preview") }
                        }
                    }
                }
            }
        }
        item(key = "guard-switches") {
            document?.let { doc ->
                GlassPreferenceSection("保持运行") {
                    SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                        SettingsToggleRow(
                            title = "实况窗 / 超级岛",
                            subtitle = "保护开始与每次音量调整同步显示在实况活动；小米机型走焦点通知通道",
                            checked = doc.liveActivity,
                            backdrop = backdrop,
                            onCheckedChange = { value -> mutate { it.copy(liveActivity = value) } }
                        )
                        SettingsToggleRow(
                            title = "1px 悬浮窗保活",
                            subtitle = if (Settings.canDrawOverlays(context)) "保护期间维持一个不可见、不可触的 1×1 窗口，降低被系统冻结的概率"
                            else "需要“显示在其他应用上层”权限",
                            checked = doc.keepAliveOverlay,
                            backdrop = backdrop,
                            onCheckedChange = { value ->
                                if (value && !Settings.canDrawOverlays(context)) {
                                    openSettings(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) { message = "系统未提供悬浮窗权限入口" }
                                } else mutate { it.copy(keepAliveOverlay = value) }
                            }
                        )
                        SettingsActionRow(
                            title = "电池与自启动",
                            subtitle = "允许后台、忽略电池优化、允许自启动，可显著减少切到后台后保护失效",
                            buttonText = "去设置",
                            iconRes = com.xiaomanjun.sleepdownschedule.R.drawable.ic_snow_guard,
                            backdrop = backdrop
                        ) { openSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) { message = "系统未提供应用设置入口" } }
                        SettingsActionRow(
                            title = "修改系统设置",
                            subtitle = "亮度控制需要该权限；未授权时亮度上限不会生效",
                            buttonText = "授权",
                            iconRes = com.xiaomanjun.sleepdownschedule.R.drawable.ic_snow_guard,
                            backdrop = backdrop
                        ) { openSettings(context, Settings.ACTION_MANAGE_WRITE_SETTINGS, true) { message = "系统未提供该权限入口" } }
                    }
                }
            }
        }
        item(key = "guard-root") {
            document?.let { doc ->
                GlassPreferenceSection("高级控制") {
                    SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                        SettingsToggleRow(
                            title = "Root 模式",
                            subtitle = if (GuardRootBridge.isAuthorized(context)) "su 已授权：音量与亮度系统直写，自动加入电池白名单"
                            else "开启后请求 su；未授权时自动降级普通控制",
                            checked = doc.rootMode,
                            backdrop = backdrop,
                            onCheckedChange = { value ->
                                mutate { it.copy(rootMode = value) }
                                if (value) run {
                                    rootBusy = true
                                    val granted = withContext(Dispatchers.IO) { GuardRootBridge.requestAuthorization(context) }
                                    rootBusy = false
                                    message = if (granted) "Root 已授权" else "su 未授权，已保持普通控制"
                                    if (granted) {
                                        withContext(Dispatchers.IO) {
                                            GuardRootBridge.grantWriteSettings(context)
                                            GuardRootBridge.whitelistBattery(context)
                                        }
                                    }
                                }
                            }
                        )
                        if (rootBusy) SettingsValueRow("Root", "正在等待超级用户授权…", compact = true)
                        SettingsToggleRow(
                            title = "使用 Shizuku（可选）",
                            subtitle = "与 Root 二选一即可；用于增强路由核对与媒体音量写入",
                            checked = doc.enhanced,
                            backdrop = backdrop,
                            onCheckedChange = { value ->
                                mutate { it.copy(enhanced = value) }
                                runCatching { EnhancedAudioBridge.requestPermission() }.onFailure { message = it.message }
                            }
                        )
                    }
                }
            }
        }
        item(key = "guard-templates") {
            document?.let { doc ->
                GlassPreferenceSection("固定模板") {
                    doc.templates.forEach { template ->
                        SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                            SettingsInfoRow(
                                title = template.name + if (doc.selectedTemplateId == template.id) " · 已选择" else "",
                                body = policySummary(template.policy)
                            )
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsActionButton("选用", backdrop, { mutate { it.copy(selectedTemplateId = template.id) } }, Modifier.weight(1f), monochrome = doc.selectedTemplateId != template.id)
                                SettingsActionButton("编辑", backdrop, { editingTemplate = template }, Modifier.weight(1f), monochrome = true)
                            }
                            SettingsActionRow(
                                title = "安排时段",
                                subtitle = "固定时段、全天或跟随课表",
                                buttonText = "新建规则",
                                iconRes = com.xiaomanjun.sleepdownschedule.R.drawable.ic_snow_guard,
                                backdrop = backdrop
                            ) {
                                editingRule = GuardRule(UUID.randomUUID().toString(), template.name, template.id, Trigger.WEEKLY)
                                newRule = true
                            }
                        }
                    }
                }
            }
        }
        item(key = "guard-rules") {
            document?.let { doc ->
                GlassPreferenceSection("保护规则") {
                    SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                        if (doc.rules.isEmpty()) SettingsValueRow("暂无规则", "跟随课表或固定时段都从这里开始", compact = true)
                        doc.rules.forEach { rule ->
                            SettingsToggleRow(
                                title = rule.name,
                                subtitle = ruleSummary(rule, doc),
                                checked = rule.enabled,
                                backdrop = backdrop,
                                badgeText = if (rule.trigger == Trigger.COURSES) "课表" else null,
                                onCheckedChange = { enabled ->
                                    mutate { it.copy(rules = it.rules.map { r -> if (r.id == rule.id) r.copy(enabled = enabled) else r }) }
                                }
                            )
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsActionButton("编辑", backdrop, { newRule = false; editingRule = rule }, Modifier.weight(1f), monochrome = true)
                                SettingsActionButton("删除", backdrop, { mutate { it.copy(rules = it.rules.filterNot { r -> r.id == rule.id }) } }, Modifier.weight(1f), monochrome = true, destructive = true)
                            }
                        }
                    }
                }
            }
        }
        item(key = "guard-timeline") {
            GlassPreferenceSection("保护时间轴") {
                SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                    val upcoming = status.windows.filter { it.end > System.currentTimeMillis() }.take(8)
                    if (upcoming.isEmpty()) SettingsValueRow("暂无时段", "缺少开学日期或规则未启用时不会生成时段", compact = true)
                    upcoming.forEach { window ->
                        SettingsValueRow("${clock(window.start)} — ${clock(window.end)}", window.reason, compact = true)
                    }
                }
            }
        }
        item(key = "guard-health") {
            document?.let { doc ->
                GlassPreferenceSection("权限健康") {
                    SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                        AndroidAudioBackend(context, doc.enhanced, doc.rootMode).capabilities().items.forEach { capability ->
                            SettingsValueRow(
                                (if (capability.available) "● " else "○ ") + capability.title,
                                capability.detail,
                                compact = true
                            )
                        }
                        SettingsNavigationRow("授予勿扰访问权限", onClick = { openSettings(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) { message = "系统未提供勿扰入口" } })
                        SettingsNavigationRow("授予媒体暂停所需的通知访问权限", onClick = { openSettings(context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) { message = "系统未提供通知访问入口" } })
                        if (Build.VERSION.SDK_INT >= 31) {
                            SettingsNavigationRow("允许精确时段触发", onClick = { openSettings(context, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) { message = "系统未提供精确闹钟入口" } })
                        }
                    }
                }
            }
        }
        item(key = "guard-ai") {
            document?.let { doc ->
                GlassPreferenceSection("AI 规则助手") {
                    SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                        GuardAiPanel(doc, showConfig = { message = "请在 AI 设置中填写接口与密钥" }, onError = { message = it })
                    }
                }
            }
        }
        item(key = "guard-log") {
            if (message != null) {
                GlassPreferenceSection("操作提示") {
                    SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                        SettingsInfoRow("最近一次操作", message.orEmpty())
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                            SettingsActionButton("知道了", backdrop, { message = null }, Modifier.weight(1f), monochrome = true)
                        }
                    }
                }
            }
        }
        item(key = "guard-events") {
            GlassPreferenceSection("本地执行记录") {
                SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                    if (events.isEmpty()) SettingsValueRow("暂无记录", "保护开始时才会写入", compact = true)
                    events.take(12).forEach { event ->
                        SettingsValueRow(formatMoment(event.time), event.message, compact = true)
                    }
                }
            }
        }
    }

    document?.let { doc ->
        editingRule?.let { rule ->
            RuleEditor(rule, doc, state.schedules, onDismiss = { editingRule = null }, onSave = { edited ->
                run {
                    store.update { it.copy(rules = if (newRule) it.rules + edited else it.rules.map { r -> if (r.id == edited.id) edited else r }) }
                    GuardEngine.start(context); editingRule = null
                }
            })
        }
        editingTemplate?.let { template ->
            TemplateEditor(template, onDismiss = { editingTemplate = null }, onSave = { edited ->
                run {
                    store.update { it.copy(customTemplates = it.customTemplates.filterNot { t -> t.id == edited.id } + edited) }
                    GuardEngine.start(context); editingTemplate = null
                }
            })
        }
    }
}

private fun openSettings(context: android.content.Context, action: String, packageUri: Boolean = false, onFailure: () -> Unit) {
    runCatching {
        context.startActivity(
            Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (packageUri) data = Uri.parse("package:${context.packageName}")
            }
        )
    }.onFailure { onFailure() }
}

private fun clock(time: Long): String =
    Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

private fun formatMoment(time: Long): String =
    Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

internal fun policySummary(p: com.xiaomanjun.sleepdownschedule.domain.guard.SoundPolicy): String = listOf(
    "媒体${p.mediaCap?.let { "≤$it%" } ?: "保持"}",
    when (p.ring) {
        com.xiaomanjun.sleepdownschedule.domain.guard.RingPolicy.KEEP -> "铃声保持"
        com.xiaomanjun.sleepdownschedule.domain.guard.RingPolicy.VIBRATE -> "震动"
        com.xiaomanjun.sleepdownschedule.domain.guard.RingPolicy.SILENT -> "静音"
    },
    "闹钟${if (p.alarms == com.xiaomanjun.sleepdownschedule.domain.guard.AlarmPolicy.KEEP) "保持" else "静音"}",
    if (p.pausePlayback) "尝试暂停" else "不暂停播放",
    if (p.lockVolume) "自动纠正音量" else "不锁定音量",
    p.brightnessCap?.let { "亮度≤$it%" } ?: "亮度保持",
    if (p.focusTakeover) "抢占音频焦点" else "不抢占焦点",
    if (p.trustedOutputAllowed) "可信耳机可放行" else "耳机不豁免"
).joinToString(" · ")

internal fun ruleSummary(r: GuardRule, doc: GuardDocument): String =
    "${doc.templates.find { it.id == r.templateId }?.name ?: "模板缺失"} · " + when (r.trigger) {
        Trigger.ALL_DAY -> "全天（星期 ${r.weekdays.sorted().joinToString()}）"
        Trigger.WEEKLY -> "星期 ${r.weekdays.sorted().joinToString()} ${r.start}—${r.end}"
        Trigger.COURSES -> "跟随课表 ${r.scheduleId}"
    } + "\n提前 ${r.leadMinutes} 分钟，延后 ${r.tailMinutes} 分钟" +
        (r.validFrom?.let { " · 自 $it" } ?: "") + (r.validThrough?.let { " · 至 $it" } ?: "")
