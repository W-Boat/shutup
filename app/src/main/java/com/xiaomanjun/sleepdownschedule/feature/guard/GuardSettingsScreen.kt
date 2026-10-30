package com.xiaomanjun.sleepdownschedule.feature.guard

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kyant.backdrop.Backdrop
import com.xiaomanjun.sleepdownschedule.AppState
import com.xiaomanjun.sleepdownschedule.CourseScheduleApp
import com.xiaomanjun.sleepdownschedule.R
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
import com.xiaomanjun.sleepdownschedule.domain.guard.AlarmPolicy
import com.xiaomanjun.sleepdownschedule.domain.guard.GuardDocument
import com.xiaomanjun.sleepdownschedule.domain.guard.GuardRule
import com.xiaomanjun.sleepdownschedule.domain.guard.GuardTemplate
import com.xiaomanjun.sleepdownschedule.domain.guard.RingPolicy
import com.xiaomanjun.sleepdownschedule.domain.guard.SoundPolicy
import com.xiaomanjun.sleepdownschedule.domain.guard.Trigger
import com.xiaomanjun.sleepdownschedule.domain.guard.TrustedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The single sound-protection page of ShutUp 课程表.
 *
 * Every row here comes from the shared Miuix-backed settings design system
 * (`GlassPreferenceSection` / `SettingsGroup` / `SettingsToggleRow` / `SettingsActionRow` /
 * `SettingsNavigationRow`); no bespoke card or switch is defined on this page.
 */
@Composable
fun GuardSettingsScreen(
    state: AppState,
    backdrop: Backdrop?,
    highlightAction: String? = null,
    onActionConsumed: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    onOpenLock: () -> Unit = {},
    onOpenDevices: () -> Unit = {}
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
        try { action() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            message = e.message ?: "操作失败"
        }
    }
    fun mutate(transform: (GuardDocument) -> GuardDocument) = run {
        withContext(Dispatchers.IO) { store.update(transform) }
        GuardEngine.start(context)
    }
    fun command(action: String) = run {
        withContext(Dispatchers.IO) { GuardEngine.command(context, action) }
        GuardEngine.start(context)
        withContext(Dispatchers.IO) { GuardEngine.reconcile(context, true) }
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
            withContext(Dispatchers.IO) { GuardEngine.reconcile(context, true) }
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
                        },
                        badgeText = status.templateName.takeIf { it.isNotBlank() }
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
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
                    SettingsValueRow("最近一次核对", if (status.updated == 0L) "尚未执行" else clock(status.updated), compact = true)
                }
            }
        }
        item(key = "guard-shortcuts") {
            GlassPreferenceSection("快捷入口") {
                SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                    SettingsNavigationRow(
                        "权限管理",
                        subtitle = "通知、勿扰、精确闹钟、悬浮窗、无障碍、使用情况、Root／Shizuku",
                        badgeText = GuardPermissions.summaryBadge(context),
                        onClick = onOpenPermissions
                    )
                    SettingsNavigationRow(
                        "上课锁机",
                        subtitle = "上课后限时决定是否锁机，白名单放行，每月 3 次直接退出",
                        onClick = onOpenLock
                    )
                    SettingsNavigationRow(
                        "可信蓝牙设备",
                        subtitle = "可信耳机输出时允许放行媒体音量",
                        onClick = onOpenDevices
                    )
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
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
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
                            subtitle = if (Settings.canDrawOverlays(context))
                                "保护期间维持一个不可见、不可触的 1×1 窗口，降低被系统冻结的概率"
                            else "需要“显示在其他应用上层”权限",
                            checked = doc.keepAliveOverlay,
                            backdrop = backdrop,
                            onCheckedChange = { value ->
                                if (value && !Settings.canDrawOverlays(context)) {
                                    openSettings(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) {
                                        message = "系统未提供悬浮窗权限入口"
                                    }
                                } else mutate { it.copy(keepAliveOverlay = value) }
                            }
                        )
                        SettingsActionRow(
                            title = "电池与自启动",
                            subtitle = "允许后台、忽略电池优化、允许自启动，可显著减少切到后台后保护失效",
                            buttonText = "去设置",
                            backdrop = backdrop
                        ) {
                            openSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) {
                                message = "系统未提供应用设置入口"
                            }
                        }
                        SettingsActionRow(
                            title = "修改系统设置",
                            subtitle = "亮度控制需要该权限；未授权时亮度上限不会生效",
                            buttonText = "授权",
                            backdrop = backdrop
                        ) {
                            openSettings(context, Settings.ACTION_MANAGE_WRITE_SETTINGS, true) {
                                message = "系统未提供该权限入口"
                            }
                        }
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
                            subtitle = if (GuardRootBridge.isAuthorized(context))
                                "su 已授权：音量与亮度系统直写，自动加入电池白名单"
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
                                    if (granted) withContext(Dispatchers.IO) {
                                        GuardRootBridge.grantWriteSettings(context)
                                        GuardRootBridge.whitelistBattery(context)
                                    }
                                }
                            }
                        )
                        if (rootBusy) SettingsValueRow("Root", "正在等待超级用户授权…", compact = true)
                        if (GuardRootBridge.isAuthorized(context)) {
                            SettingsActionRow(
                                title = "Root 已授权",
                                subtitle = "重新写入电池白名单并允许修改系统设置",
                                buttonText = "重新同步",
                                backdrop = backdrop
                            ) {
                                run {
                                    val ok = withContext(Dispatchers.IO) {
                                        GuardRootBridge.whitelistBattery(context) || GuardRootBridge.grantWriteSettings(context)
                                    }
                                    message = if (ok) "已同步 Root 权限" else "su 调用失败，请检查授权"
                                }
                            }
                        }
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
                        SettingsGroup(
                            backdrop = backdrop,
                            config = visualConfig,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        ) {
                            SettingsInfoRow(
                                title = template.name + if (doc.selectedTemplateId == template.id) " · 已选择" else "",
                                body = policySummary(template.policy)
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SettingsActionButton(
                                    label = "选用",
                                    backdrop = backdrop,
                                    onClick = { mutate { it.copy(selectedTemplateId = template.id) } },
                                    modifier = Modifier.weight(1f),
                                    monochrome = doc.selectedTemplateId != template.id
                                )
                                SettingsActionButton(
                                    label = if (template.builtIn) "复制" else "编辑",
                                    backdrop = backdrop,
                                    onClick = { editingTemplate = template },
                                    modifier = Modifier.weight(1f),
                                    monochrome = true
                                )
                            }
                            SettingsActionRow(
                                title = "安排时段",
                                subtitle = "固定时段、全天或跟随课表",
                                buttonText = "新建规则",
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
                        if (doc.rules.isEmpty()) {
                            SettingsValueRow("暂无规则", "跟随课表或固定时段都从这里开始", compact = true)
                        }
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
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SettingsActionButton(
                                    label = "编辑",
                                    backdrop = backdrop,
                                    onClick = { newRule = false; editingRule = rule },
                                    modifier = Modifier.weight(1f),
                                    monochrome = true
                                )
                                SettingsActionButton(
                                    label = "删除",
                                    backdrop = backdrop,
                                    onClick = { mutate { it.copy(rules = it.rules.filterNot { r -> r.id == rule.id }) } },
                                    modifier = Modifier.weight(1f),
                                    monochrome = true,
                                    destructive = true
                                )
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
                    if (upcoming.isEmpty()) {
                        SettingsValueRow("暂无时段", "缺少开学日期或规则未启用时不会生成时段", compact = true)
                    }
                    upcoming.forEach { window ->
                        SettingsValueRow("${clock(window.start)} — ${clock(window.end)}", window.reason, compact = true)
                    }
                }
            }
        }
        item(key = "guard-conflicts") {
            GlassPreferenceSection("冲突预览与课表回滚") {
                SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                    val windows = status.windows.filter { it.end > System.currentTimeMillis() }
                    val overlaps = windows.flatMapIndexed { index, a ->
                        windows.drop(index + 1)
                            .filter { b -> a.ruleId != b.ruleId && a.start < b.end && b.start < a.end }
                            .map { b -> "${a.reason} × ${b.reason}：${clock(maxOf(a.start, b.start))}" }
                    }.distinct()
                    if (overlaps.isEmpty()) {
                        SettingsValueRow("无跨规则冲突", "重叠时会采用更严格的限制", compact = true)
                    }
                    overlaps.take(10).forEach { SettingsValueRow("重叠", it, compact = true) }
                    SettingsActionRow(
                        title = "恢复导入前课表",
                        subtitle = "撤销最近一次覆盖导入，回到导入前的独立课表",
                        buttonText = "撤销",
                        backdrop = backdrop
                    ) {
                        run {
                            withContext(Dispatchers.IO) { app.repository.undoLastGuardImport() }
                            message = "已撤销最近一次覆盖导入，恢复为导入前的课表"
                        }
                    }
                }
            }
        }
        item(key = "guard-health") {
            document?.let { doc ->
                GlassPreferenceSection("能力检查") {
                    SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                        AndroidAudioBackend(context, doc.enhanced, doc.rootMode).capabilities().items.forEach { capability ->
                            SettingsValueRow(
                                capability.title,
                                "${if (capability.available) "可用" else "待授权或受限"} · ${capability.detail}",
                                compact = true
                            )
                        }
                        SettingsNavigationRow(
                            "查看完整权限管理",
                            subtitle = "逐项授权，和系统设置一样的列表",
                            onClick = onOpenPermissions
                        )
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
                            SettingsActionButton(
                                label = "知道了",
                                backdrop = backdrop,
                                onClick = { message = null },
                                modifier = Modifier.weight(1f),
                                monochrome = true
                            )
                        }
                    }
                }
            }
        }
        item(key = "guard-events") {
            GlassPreferenceSection("执行记录") {
                SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                    if (events.isEmpty()) {
                        SettingsValueRow("暂无记录", "保护开始时才会写入", compact = true)
                    }
                    events.take(20).forEach { event ->
                        val parsed = parseGuardEvent(event.message)
                        SettingsInfoRow(
                            title = "${clock(event.time)} · ${event.title.ifBlank { parsed.title }}",
                            body = if (event.title.isNotBlank()) listOf(event.reason, event.error).filter { it.isNotBlank() }.joinToString("\n") else parsed.detail,
                            badgeText = parsed.badge
                        )
                    }
                }
            }
        }
    }

    document?.let { doc ->
        editingRule?.let { rule ->
            RuleEditor(rule, doc, state.schedules, onDismiss = { editingRule = null }, onSave = { edited ->
                run {
                    withContext(Dispatchers.IO) {
                        store.update { it.copy(rules = if (newRule) it.rules + edited else it.rules.map { r -> if (r.id == edited.id) edited else r }) }
                    }
                    GuardEngine.start(context); editingRule = null
                }
            })
        }
        editingTemplate?.let { template ->
            TemplateEditor(template, onDismiss = { editingTemplate = null }, onSave = { edited ->
                run {
                    withContext(Dispatchers.IO) {
                        store.update { it.copy(customTemplates = it.customTemplates.filterNot { t -> t.id == edited.id } + edited) }
                    }
                    GuardEngine.start(context); editingTemplate = null
                }
            })
        }
    }
}

internal data class GuardEventView(val title: String, val detail: String, val badge: String?)

/** Turns the stored "标题｜原因｜错误" audit string into readable rows instead of one long line. */
internal fun parseGuardEvent(raw: String): GuardEventView {
    val parts = raw.split("｜")
    val title = parts.getOrNull(0)?.ifBlank { null } ?: "保护状态"
    val reasons = parts.getOrNull(1).orEmpty().trim()
    val errors = parts.getOrNull(2).orEmpty().trim()
    val detail = buildString {
        if (reasons.isNotBlank()) append(reasons)
        if (errors.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append("⚠ ").append(errors)
        }
        if (isEmpty()) append("无附加信息")
    }
    return GuardEventView(title, detail, if (errors.isNotBlank()) "需处理" else null)
}

@Composable
internal fun TrustedDevicesSection(
    document: GuardDocument,
    backdrop: Backdrop?,
    visualConfig: com.xiaomanjun.sleepdownschedule.ScheduleConfigEntity,
    onChange: ((GuardDocument) -> GuardDocument) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    var paired by remember { mutableStateOf<List<TrustedDevice>>(emptyList()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onError("已返回授权结果，请下拉刷新设备列表")
    }
    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun refresh() {
        if (!hasBluetoothPermission()) return
        runCatching {
            paired = context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .map { TrustedDevice(it.address, it.name ?: "未命名设备") }
                .sortedBy { it.label }
        }.onFailure { onError(it.message ?: "无法读取已配对设备") }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    GlassPreferenceSection("可信设备") {
        SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
            SettingsInfoRow(
                title = "可信输出放行",
                body = "只有已勾选的具体设备在确认占满全部播放路由时，才会解除媒体音量限制；路由不明时继续保护。"
            )
            if (!hasBluetoothPermission() && Build.VERSION.SDK_INT >= 31) {
                SettingsActionRow(
                    title = "蓝牙权限",
                    subtitle = "读取已配对设备需要“附近的设备”权限",
                    buttonText = "授权",
                    backdrop = backdrop
                ) { permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }
            }
            SettingsActionRow(
                title = "已配对设备",
                subtitle = if (paired.isEmpty()) "暂无可读取设备，先到系统蓝牙里配对" else "共 ${paired.size} 台，点按勾选可信输出",
                buttonText = "刷新",
                backdrop = backdrop
            ) { refresh() }
            (paired + document.trustedDevices).distinctBy { it.address }.forEach { device ->
                val trusted = document.trustedDevices.any { it.address == device.address }
                SettingsToggleRow(
                    title = device.label,
                    subtitle = device.address.takeLast(11),
                    checked = trusted,
                    backdrop = backdrop,
                    onCheckedChange = { value ->
                        onChange { current ->
                            current.copy(
                                trustedDevices = current.trustedDevices.filterNot { it.address == device.address } +
                                    if (value) listOf(device) else emptyList()
                            )
                        }
                    }
                )
            }
            SettingsToggleRow(
                title = "信任有线耳机",
                subtitle = "3.5mm 耳机/耳麦视为可信输出",
                checked = document.trustWired,
                backdrop = backdrop,
                onCheckedChange = { value -> onChange { it.copy(trustWired = value) } }
            )
            SettingsToggleRow(
                title = "信任 USB 耳机",
                subtitle = "USB-C 耳机视为可信输出",
                checked = document.trustUsb,
                backdrop = backdrop,
                onCheckedChange = { value -> onChange { it.copy(trustUsb = value) } }
            )
        }
    }
}

private fun openSettings(
    context: android.content.Context,
    action: String,
    packageUri: Boolean = false,
    onFailure: () -> Unit
) {
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

internal fun policySummary(p: SoundPolicy): String = listOf(
    "媒体${p.mediaCap?.let { "≤$it%" } ?: "保持"}",
    when (p.ring) {
        RingPolicy.KEEP -> "铃声保持"
        RingPolicy.VIBRATE -> "震动"
        RingPolicy.SILENT -> "静音"
    },
    "闹钟${if (p.alarms == AlarmPolicy.KEEP) "保持" else "静音"}",
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
