package com.xiaomanjun.sleepdownschedule.feature.guard

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.xiaomanjun.sleepdownschedule.CourseScheduleApp
import com.xiaomanjun.sleepdownschedule.MainActivity
import com.xiaomanjun.sleepdownschedule.BuildConfig
import com.xiaomanjun.sleepdownschedule.data.guard.*
import com.xiaomanjun.sleepdownschedule.domain.guard.*
import com.xiaomanjun.sleepdownschedule.feature.importing.*
import com.xiaomanjun.sleepdownschedule.model.AppState
import com.xiaomanjun.sleepdownschedule.model.ScheduleProfileEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

class SnowLeopardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF83D6C4))
                else lightColorScheme(primary = Color(0xFF126B5B), surface = Color(0xFFF7FAF8), background = Color(0xFFF7FAF8))
            MaterialTheme(colorScheme = colors) { SnowLeopardHome(intent.getStringExtra("guard_action")) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun SnowLeopardHome(initialAction: String?) {
    val context = LocalContext.current
    val app = context.applicationContext as CourseScheduleApp
    val scope = rememberCoroutineScope()
    val store = remember { GuardStore(context) }
    var document by remember { mutableStateOf<GuardDocument?>(null) }
    val schedules by app.repository.allSchedulesState.collectAsState(initial = AppState())
    val status by GuardEngine.status.collectAsState()
    val events by GuardDatabase.get(context).dao().observeEvents().collectAsState(initial = emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var editingRule by remember { mutableStateOf<GuardRule?>(null) }
    var newRule by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<GuardTemplate?>(null) }
    var showApi by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    fun launchWork(action: suspend () -> Unit) { scope.launch {
        try { action() } catch (e: Exception) { if (e is CancellationException) throw e; message = e.message ?: "操作失败" }
    } }
    fun mutate(transform: (GuardDocument) -> GuardDocument) = launchWork {
        store.update(transform)
        GuardEngine.start(context)
    }
    fun command(action: String) = launchWork {
        GuardEngine.command(context, action); GuardEngine.start(context)
    }
    fun openSettings(action: String, packageUri: Boolean = false) {
        runCatching { context.startActivity(Intent(action).apply { if (packageUri) data = Uri.parse("package:${context.packageName}") }) }
            .onFailure { message = "系统未提供此设置入口" }
    }
    LaunchedEffect(Unit) {
        app.repository.ensureDefaults()
        try { store.document.collect { document = it } }
        catch (e: Exception) { message = "规则加载失败，已有数据未清除：${e.message}" }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        launchWork {
            val doc = withContext(Dispatchers.IO) { store.document.first() }
            if (doc.rules.any { it.enabled } || doc.manualUntil > System.currentTimeMillis()) GuardEngine.start(context)
            else withContext(Dispatchers.IO) { GuardEngine.reconcile(context, true) }
        }
    }
    LaunchedEffect(initialAction) { if (initialAction == "protect") command("protect") }
    Scaffold(topBar = { TopAppBar(title = { Column {
        Text("雪豹闭嘴", fontWeight = FontWeight.Bold)
        Text("把安静安排好", style = MaterialTheme.typography.labelMedium)
    } }) }, bottomBar = {
        NavigationBar { listOf("保护", "规则", "AI", "设备").forEachIndexed { index, title ->
            NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("◉", "▤", "✦", "◇")[index]) }, label = { Text(title) })
        } }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (message != null) item { GuardCard("操作提示") { Text(message.orEmpty()); TextButton(onClick = { message = null }) { Text("知道了") } } }
            val doc = document
            if (doc == null) item { Text("正在读取本机规则…") }
            else when (tab) {
                0 -> {
                    if (doc.persona == null) item { GuardCard("选择使用场景") {
                        Text("只影响设置引导，所有功能都能使用。首次保护请先选择下方模板。")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("中学生", "大学生", "其他用户").forEach { name -> OutlinedButton(onClick = { mutate { it.copy(persona = name) } }) { Text(name) } }
                        }
                    } }
                    item { GuardCard(if (!status.serviceRunning && doc.rules.any { it.enabled }) "后台保护未运行" else status.title) {
                        Text(status.reasons.joinToString("、").ifBlank { "尚未进入保护时段" }, style = MaterialTheme.typography.titleMedium)
                        Text(status.route)
                        status.next?.let { Text("下次核对／切换：${formatMoment(it)}") }
                        status.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (status.updated != 0L) Text("最近检查 ${formatMoment(status.updated)}", style = MaterialTheme.typography.labelMedium)
                        Text("当前模板：${doc.templates.find { it.id == doc.selectedTemplateId }?.name ?: "尚未选择"}")
                        Button(onClick = { command("protect") }, enabled = doc.selectedTemplateId != null) { Text("立即保护 1 小时") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { command("bypass") }) { Text("放行 5 分钟") }
                            OutlinedButton(onClick = { command("end") }) { Text("结束本次保护") }
                        }
                    } }
                    item { Text("固定模板", style = MaterialTheme.typography.headlineSmall) }
                    items(doc.templates.size) { index -> val template = doc.templates[index]
                        GuardCard(template.name + if (doc.selectedTemplateId == template.id) " · 已选择" else "") {
                            Text(policySummary(template.policy))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { mutate { it.copy(selectedTemplateId = template.id) } }) { Text("选用") }
                                TextButton(onClick = { editingTemplate = template }) { Text(if (template.builtIn) "复制并自定义" else "编辑模板") }
                                TextButton(onClick = { editingRule = GuardRule(UUID.randomUUID().toString(), template.name, template.id, Trigger.WEEKLY); newRule = true }) { Text("安排时段") }
                            }
                        }
                    }
                    item { GuardCard("课表与保护时间轴") {
                        Text(if (doc.persona == "大学生") "导入课表后，在规则页选择跟随课表并绑定模板。" else "课程、固定时段和全天规则可以同时使用。")
                        Button(onClick = { context.startActivity(Intent(context, MainActivity::class.java)) }) { Text("课表 · 导入／编辑／日周视图") }
                        Text("课表页面返回后回到声音保护首页。")
                        status.windows.filter { it.end > System.currentTimeMillis() }.take(12).forEach { window ->
                            Text("${formatMoment(window.start)}—${formatMoment(window.end)}\n${window.reason}")
                        }
                    } }
                }
                1 -> {
                    item { GuardCard("保护规则") {
                        Text("模板决定保护方式，规则决定生效时间。重叠时采用更严格的限制；限音只降低超限音量。")
                        Button(onClick = { newRule = true; editingRule = GuardRule(UUID.randomUUID().toString(), "", doc.selectedTemplateId.orEmpty(), Trigger.WEEKLY) }) { Text("添加规则") }
                        Button(onClick = { context.startActivity(Intent(context, MainActivity::class.java)) }) { Text("管理课表／导入资料") }
                        OutlinedButton(onClick = { launchWork {
                            withContext(Dispatchers.IO) { app.repository.undoLastGuardImport() }
                            message = "已撤销最近一次覆盖导入，恢复为独立课表"
                        } }) { Text("恢复导入前课表") }
                    } }
                    items(doc.rules.size) { index -> val rule = doc.rules[index]
                        GuardCard(rule.name) {
                            Text(ruleSummary(rule, doc))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Switch(checked = rule.enabled, onCheckedChange = { enabled -> mutate { it.copy(rules = it.rules.map { r -> if (r.id == rule.id) r.copy(enabled = enabled) else r }) } })
                                TextButton(onClick = { newRule = false; editingRule = rule }) { Text("编辑") }
                                TextButton(onClick = { mutate { it.copy(rules = it.rules.filterNot { r -> r.id == rule.id }) } }) { Text("删除") }
                            }
                        }
                    }
                    item { GuardCard("冲突预览") {
                        val windows = status.windows.filter { it.end > System.currentTimeMillis() }
                        val overlaps = windows.flatMapIndexed { index, a -> windows.drop(index + 1).filter { b -> a.ruleId != b.ruleId && a.start < b.end && b.start < a.end }.map { b -> "${a.reason} × ${b.reason}：${formatMoment(maxOf(a.start, b.start))}" } }.distinct()
                        if (overlaps.isEmpty()) Text("当前已编译时段没有跨规则冲突。") else overlaps.take(15).forEach { Text(it) }
                        Text("缺少开学日期的课表不会生成保护时段；请在课表设置中补全。")
                    } }
                }
                2 -> item { GuardAiPanel(doc, showConfig = { showApi = true }, onError = { message = it }) }
                3 -> {
                    item { GuardCard("权限健康检查") {
                        AndroidAudioBackend(context, doc.enhanced).capabilities().items.forEach { capability ->
                            Text("${if (capability.available) "●" else "○"} ${capability.title}", fontWeight = FontWeight.SemiBold)
                            Text(capability.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }) { Text("授予勿扰访问权限") }
                        TextButton(onClick = { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) { Text("授予媒体暂停所需的通知访问权限") }
                        if (Build.VERSION.SDK_INT >= 31) TextButton(onClick = { openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) }) { Text("允许精确时段触发") }
                        TextButton(onClick = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("后台运行／电池／通知设置") }
                        TextButton(onClick = { permissions.launch(buildList {
                            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.toTypedArray()) }) { Text("请求蓝牙／通知权限") }
                        Text("未申请麦克风权限。被系统强制停止后，请重新打开应用恢复保护。")
                    } }
                    item { TrustedDevicesPanel(doc, onChange = ::mutate, onError = { message = it }) }
                    item { GuardCard("增强模式") {
                        Toggle("使用 Shizuku（可选）", doc.enhanced) { enabled -> mutate { it.copy(enhanced = enabled) } }
                        TextButton(onClick = { runCatching { EnhancedAudioBridge.requestPermission() }.onFailure { message = it.message } }) { Text("请求 Shizuku 授权") }
                        Text("当前为自动纠正音量。增强路由接口不可用时保持保护，不自动放行耳机。尚未进行真机硬锁认证。")
                    } }
                    item { GuardCard("本地执行记录") { if (events.isEmpty()) Text("暂无记录")
                        events.take(30).forEach { Text("${formatMoment(it.time)} · ${it.message}", style = MaterialTheme.typography.bodySmall) }
                    } }
                    item { GuardCard("关于雪豹闭嘴 ${BuildConfig.VERSION_NAME}") {
                        Text("本项目基于 SleepDown课程表 修改；原作者：xiaomanjun233。非官方非商业衍生版。主要新增声音保护首页、固定模板、规则引擎、可信设备及自然语言规则。")
                        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xiaomanjun233/SleepDown-Schedule"))) }) { Text("原项目／许可／署名") }
                        Text("源码与构建脚本随本应用发布仓库公开提供。课表展示与导入保留上游署名和第三方声明。")
                    } }
                }
            }
        }
    }
    document?.let { doc ->
        editingRule?.let { rule -> RuleEditor(rule, doc, schedules.schedules, onDismiss = { editingRule = null }, onSave = { edited ->
            launchWork {
                store.update { it.copy(rules = if (newRule) it.rules + edited else it.rules.map { r -> if (r.id == edited.id) edited else r }) }
                GuardEngine.start(context); editingRule = null
            }
        }) }
        editingTemplate?.let { template -> TemplateEditor(template, onDismiss = { editingTemplate = null }, onSave = { edited ->
            launchWork {
                store.update { it.copy(customTemplates = it.customTemplates.filterNot { t -> t.id == edited.id } + edited) }
                GuardEngine.start(context); editingTemplate = null
            }
        }) }
    }
    if (showApi) ApiEditor(onDismiss = { showApi = false }, onMessage = { message = it })
}

@Composable internal fun GuardCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable internal fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp, top = 12.dp)); Switch(checked, onCheckedChange = onChange)
    }
}

internal fun formatMoment(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
internal fun policySummary(p: SoundPolicy): String = "媒体${p.mediaCap?.let { "≤$it%" } ?: "保持"} · ${when (p.ring) { RingPolicy.KEEP -> "铃声保持"; RingPolicy.VIBRATE -> "震动"; RingPolicy.SILENT -> "静音" }} · 闹钟${if (p.alarms == AlarmPolicy.KEEP) "保持" else "静音"}" +
    if (p.pausePlayback) " · 尝试暂停播放" else ""
internal fun ruleSummary(r: GuardRule, doc: GuardDocument): String = "${doc.templates.find { it.id == r.templateId }?.name ?: "模板缺失"} · " + when (r.trigger) {
    Trigger.ALL_DAY -> "全天（星期 ${r.weekdays.sorted().joinToString()}）"
    Trigger.WEEKLY -> "星期 ${r.weekdays.sorted().joinToString()} ${r.start}—${r.end}"
    Trigger.COURSES -> "跟随课表 ${r.scheduleId}"
} + "\n提前 ${r.leadMinutes} 分钟，延后 ${r.tailMinutes} 分钟" + (r.validFrom?.let { " · 自 $it" } ?: "") + (r.validThrough?.let { " · 至 $it" } ?: "")
