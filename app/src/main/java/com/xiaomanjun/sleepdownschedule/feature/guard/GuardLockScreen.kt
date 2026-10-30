package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.xiaomanjun.sleepdownschedule.AppState
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
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsTextFieldRow
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsToggleRow
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsValueRow
import com.xiaomanjun.sleepdownschedule.data.guard.GuardStore
import com.xiaomanjun.sleepdownschedule.data.guard.LockStore
import com.xiaomanjun.sleepdownschedule.domain.guard.CareStyles
import com.xiaomanjun.sleepdownschedule.domain.guard.GuardDocument
import com.xiaomanjun.sleepdownschedule.domain.guard.LockQuotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Slider

/**
 * 上课锁机设置页。控件全部来自 Miuix 设计系统（Slider 直接使用 Miuix 实现，
 * 其余开关/行/输入框复用仓库既有 Miuix 封装）。
 */
@Composable
fun GuardLockScreen(state: AppState, backdrop: Backdrop?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GuardStore(context) }
    val lockStore = remember { LockStore(context) }
    val visualConfig = settingsVisualConfig(state.config)
    var document by remember { mutableStateOf<GuardDocument?>(null) }
    val status by GuardEngine.status.collectAsState()
    val courseSchedules by (context.applicationContext as com.xiaomanjun.sleepdownschedule.CourseScheduleApp)
        .repository.allSchedulesState.collectAsState(initial = state)
    var showCourseScope by remember { mutableStateOf(false) }
    val session by androidx.compose.runtime.produceState(com.xiaomanjun.sleepdownschedule.domain.guard.LockSession()) {
        while (true) {
            value = lockStore.session().let {
                if (it.endsAt <= System.currentTimeMillis()) it.copy(active = false) else it
            }
            kotlinx.coroutines.delay(1_000)
        }
    }
    var message by remember { mutableStateOf<String?>(null) }
    var showAllApps by remember { mutableStateOf(false) }
    var noteCourse by remember { mutableStateOf("") }

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
    androidx.compose.runtime.LaunchedEffect(Unit) {
        store.document.collect { document = it }
    }

    SleepDownSecondaryPageList(contentTopPadding = detailContentTopPadding(), contentBottomPadding = DockScrollPadding) {
        item(key = "lock-main") {
            val doc = document
            GlassPreferenceSection("上课锁机") {
                SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                    SettingsInfoRow("当前锁机状态", when {
                        doc?.lock?.enabled != true -> "未启用"
                        status.errors.isNotEmpty() && !session.active -> status.errors.joinToString("\n")
                        !session.active && status.lockWindows.any { it.classStart > System.currentTimeMillis() } -> {
                            val next = status.lockWindows.first { it.classStart > System.currentTimeMillis() }
                            "已启用 · 下次 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(next.classStart))} ${next.reason}，上课时提醒"
                        }
                        !session.active && status.lockWindows.isEmpty() -> "暂无可锁机课程，请检查开学日期、课程时间和课程黑白名单；无需创建声音规则"
                        !session.active -> "未进入锁机时段"
                        session.skipped -> "本次不锁机"
                        session.decided -> "已锁定 · 页面已进入且系统锁任务已确认"
                        session.pendingEntry -> "等待进入 · 系统尚未确认锁定，请从通知进入"
                        else -> "等待决定 · ${session.courseName}"
                    })
                    if (doc?.lock?.enabled == true) GuardLockController.notificationProblem(context)?.let {
                        SettingsInfoRow("无法显示锁机提醒", it)
                    }
                    if (session.active && !session.skipped && !session.decided) {
                        SettingsActionRow("进入本次锁机", "通知关闭或后台启动受限时，可从这里进入并确认系统锁任务",
                            "进入", backdrop = backdrop) { GuardLockController.launch(context) }
                    }
                    if (doc == null) {
                        SettingsValueRow("读取中", "正在载入锁机配置…", compact = true)
                    } else {
                        val lock = doc.lock
                        SettingsToggleRow(
                            title = "启用锁机",
                            subtitle = "上课后先给一段决定时间，超时自动进入锁机页面",
                            checked = lock.enabled,
                            backdrop = backdrop,
                            onCheckedChange = { value -> mutate { it.copy(lock = it.lock.copy(enabled = value)) } }
                        )
                        SettingsValueRow(
                            "决定时间",
                            "${lock.safeGraceSeconds} 秒（${lock.safeGraceSeconds / 60} 分 ${lock.safeGraceSeconds % 60} 秒）",
                            compact = true
                        )
                        Slider(
                            value = lock.safeGraceSeconds.toFloat(),
                            onValueChange = { value ->
                                mutate { it.copy(lock = it.lock.copy(graceSeconds = value.toInt().coerceIn(30, 900))) }
                            },
                            valueRange = 30f..300f,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(60, 120, 180, 300).forEach { seconds ->
                                SettingsActionButton(
                                    label = if (seconds >= 60) "${seconds / 60} 分钟" else "$seconds 秒",
                                    backdrop = backdrop,
                                    onClick = { mutate { it.copy(lock = it.lock.copy(graceSeconds = seconds)) } },
                                    modifier = Modifier.weight(1f),
                                    monochrome = lock.safeGraceSeconds != seconds
                                )
                            }
                        }
                        SettingsToggleRow(
                            title = "超时自动锁机",
                            subtitle = "关闭后必须在决定时间内手动点「开始锁机」",
                            checked = lock.autoLockOnTimeout,
                            backdrop = backdrop,
                            onCheckedChange = { value -> mutate { it.copy(lock = it.lock.copy(autoLockOnTimeout = value)) } }
                        )
                        SettingsValueRow(
                            "本月退出机会",
                            "剩余 ${lockStore.exitsLeft(lock.safeQuota)} / ${lock.safeQuota} 次",
                            compact = true
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(2, 3, 5).forEach { quota ->
                                SettingsActionButton(
                                    label = "每月 $quota 次",
                                    backdrop = backdrop,
                                    onClick = { mutate { it.copy(lock = it.lock.copy(monthlyExitQuota = quota)) } },
                                    modifier = Modifier.weight(1f),
                                    monochrome = lock.safeQuota != quota
                                )
                            }
                        }
                        SettingsActionRow(
                            title = "重置本月额度",
                            subtitle = "仅重置已使用次数，不改变每月额度设置",
                            buttonText = "重置",
                            backdrop = backdrop
                        ) { run { withContext(Dispatchers.IO) { lockStore.resetExits() }; message = "已重置本月退出次数" } }
                        SettingsActionRow(
                            title = "锁机权限",
                            subtitle = "无障碍、使用情况、悬浮窗、精确闹钟等，逐项授权",
                            buttonText = "去授权",
                            backdrop = backdrop
                        ) {
                            context.startActivity(android.content.Intent(context, com.xiaomanjun.sleepdownschedule.SettingsDetailActivity::class.java)
                                .putExtra(com.xiaomanjun.sleepdownschedule.app.ui.SettingsDetailPageExtra,
                                    com.xiaomanjun.sleepdownschedule.feature.settings.SettingsPage.GuardPermissions.name))
                        }
                    }
                }
            }
        }
        lockCourseScopeItems(document, courseSchedules, showCourseScope, { showCourseScope = !showCourseScope },
            backdrop, visualConfig) { change -> mutate(change) }
        item(key = "lock-quotes") {
            document?.let { doc ->
                GlassPreferenceSection("静心句子") {
                    SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                        SettingsInfoRow("锁机页显示一句督促学习的话", "选一个语料包，或写一句自己的话。")
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LockQuotes.packs.forEach { (id, label) ->
                                SettingsActionButton(
                                    label = label,
                                    backdrop = backdrop,
                                    onClick = { mutate { it.copy(lock = it.lock.copy(quotePack = id)) } },
                                    modifier = Modifier.weight(1f),
                                    monochrome = doc.lock.quotePack != id
                                )
                            }
                        }
                        SettingsTextFieldRow(
                            title = "自定义句子",
                            value = doc.lock.customQuote,
                            onValueChange = { value -> mutate { it.copy(lock = it.lock.copy(customQuote = value.take(200))) } },
                            placeholder = "留空则使用语料包"
                        )
                    }
                }
            }
        }
        item(key = "lock-whitelist") {
            document?.let { doc ->
                GlassPreferenceSection("白名单应用") {
                    SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = Modifier.fillMaxWidth()) {
                        SettingsInfoRow(
                            title = "锁机时仍然可以打开的应用",
                            body = "建议只放学习类应用（词典、笔记、网课）。微信、短视频等不放进来才有效果。",
                            badgeText = "${doc.lock.allowedPackages.size} 个"
                        )
                        SettingsActionRow(
                            title = if (showAllApps) "收起应用列表" else "选择应用",
                            subtitle = "读取已安装且可启动的应用",
                            buttonText = if (showAllApps) "收起" else "展开",
                            backdrop = backdrop
                        ) { showAllApps = !showAllApps }
                        if (showAllApps) {
                            val apps = remember(showAllApps) { launchableApps(context) }
                            apps.forEach { (pkg, label) ->
                                SettingsToggleRow(
                                    title = label,
                                    subtitle = pkg,
                                    checked = pkg in doc.lock.allowedPackages,
                                    backdrop = backdrop,
                                    onCheckedChange = { value ->
                                        mutate { current ->
                                            val next = if (value) current.lock.allowedPackages + pkg
                                            else current.lock.allowedPackages - pkg
                                            current.copy(lock = current.lock.copy(allowedPackages = next.distinct().take(60)))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "lock-message") {
            if (message != null) {
                GlassPreferenceSection("提示") {
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
    }
}

private fun launchableApps(context: android.content.Context): List<Pair<String, String>> {
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    return runCatching {
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                pkg to info.loadLabel(context.packageManager).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }.getOrDefault(emptyList())
}
