package com.xiaomanjun.sleepdownschedule.feature.guard

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.xiaomanjun.sleepdownschedule.domain.guard.*
import com.xiaomanjun.sleepdownschedule.feature.importing.*
import com.xiaomanjun.sleepdownschedule.model.ScheduleProfileEntity
import kotlinx.coroutines.*
import java.net.URI
import java.util.UUID

@Composable private fun EditorDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MiuixTheme.textStyles.title3)
                content()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RuleEditor(initial: GuardRule, doc: GuardDocument, schedules: List<ScheduleProfileEntity>,
    onDismiss: () -> Unit, onSave: (GuardRule) -> Unit) {
    var rule by remember(initial.id) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var lead by remember { mutableStateOf(initial.leadMinutes.toString()) }
    var tail by remember { mutableStateOf(initial.tailMinutes.toString()) }
    EditorDialog("安排保护时段", onDismiss) {
        TextField(rule.name, { rule = rule.copy(name = it) }, label = "规则名称", singleLine = true)
        Text("选择保护模板")
        doc.templates.forEach { t -> Row {
            RadioButton(rule.templateId == t.id, { rule = rule.copy(templateId = t.id) }); Button(onClick = { rule = rule.copy(templateId = t.id) }) { Text(t.name) }
        } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Trigger.ALL_DAY to "全天", Trigger.WEEKLY to "固定时段", Trigger.COURSES to "跟随课表").forEach { pair ->
                Button(onClick = { rule = rule.copy(trigger = pair.first) }) { Text((if (rule.trigger == pair.first) "✓ " else "") + pair.second) }
            }
        }
        if (rule.trigger == Trigger.COURSES) {
            if (schedules.isEmpty()) Text("请先在课表页面创建或导入课表。")
            schedules.forEach { s -> Row {
                RadioButton(rule.scheduleId == s.id, { rule = rule.copy(scheduleId = s.id) })
                Button(onClick = { rule = rule.copy(scheduleId = s.id) }) { Text(s.name) }
            } }
            Text("有效周次、单双周、停课和调课按课表执行；必须设置开学日期。")
        } else {
            Text("重复日期（全天模式也按所选日期执行）")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label -> val day = index + 1
                    Button(onClick = { rule = rule.copy(weekdays = if (day in rule.weekdays) rule.weekdays - day else rule.weekdays + day) }) { Text((if (day in rule.weekdays) "✓ " else "") + label) }
                }
            }
            if (rule.trigger == Trigger.WEEKLY) {
                TextField(rule.start, { rule = rule.copy(start = it) }, label = "开始 HH:mm", singleLine = true)
                TextField(rule.end, { rule = rule.copy(end = it) }, label = "结束 HH:mm（可跨午夜）", singleLine = true)
            }
        }
        if (rule.trigger != Trigger.ALL_DAY) {
            TextField(lead, { lead = it }, label = "提前分钟 0—30", singleLine = true)
            TextField(tail, { tail = it }, label = "延后分钟 0—30", singleLine = true)
        }
        TextField(rule.validFrom.orEmpty(), { rule = rule.copy(validFrom = it.ifBlank { null }) }, label = "生效日期 YYYY-MM-DD（可空）", singleLine = true)
        TextField(rule.validThrough.orEmpty(), { rule = rule.copy(validThrough = it.ifBlank { null }) }, label = "结束日期 YYYY-MM-DD（可空）", singleLine = true)
        error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
        Button(onClick = {
            runCatching {
                val edited = rule.copy(leadMinutes = lead.toIntOrNull() ?: throw IllegalArgumentException("提前分钟无效"), tailMinutes = tail.toIntOrNull() ?: throw IllegalArgumentException("延后分钟无效"))
                edited.validate(doc.templates); onSave(edited)
            }.onFailure { error = it.message }
        }) { Text("保存规则") }
        Button(onClick = onDismiss) { Text("取消") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TemplateEditor(initial: GuardTemplate, onDismiss: () -> Unit, onSave: (GuardTemplate) -> Unit) {
    var name by remember { mutableStateOf(if (initial.builtIn) "${initial.name} · 自定义" else initial.name) }
    var policy by remember { mutableStateOf(initial.policy) }
    var error by remember { mutableStateOf<String?>(null) }
    EditorDialog(if (initial.builtIn) "复制固定模板" else "编辑自定义模板", onDismiss) {
        TextField(name, { name = it }, label = "模板名称", singleLine = true)
        BasicComponent(title = "限制媒体音量", endActions = { Switch(checked = policy.mediaCap != null, onCheckedChange = { policy = policy.copy(mediaCap = if (it) 0 else null) }) })
        policy.mediaCap?.let { percent ->
            Text("媒体上限 $percent%（只降不升）")
            Slider(percent.toFloat(), { policy = policy.copy(mediaCap = it.toInt()) }, valueRange = 0f..100f, steps = 99)
            FlowRow { listOf(0, 10, 20).forEach { cap -> Button(onClick = { policy = policy.copy(mediaCap = cap) }) { Text("$cap%") } } }
        }
        BasicComponent(title = "检测播放后尝试暂停", endActions = { Switch(checked = policy.pausePlayback, onCheckedChange = { policy = policy.copy(pausePlayback = it) }) })
        BasicComponent(title = "锁定音量（自动纠正）", endActions = { Switch(checked = policy.lockVolume, onCheckedChange = { policy = policy.copy(lockVolume = it) }) })
        BasicComponent(title = "可信耳机可放行媒体", endActions = { Switch(checked = policy.trustedOutputAllowed, onCheckedChange = { policy = policy.copy(trustedOutputAllowed = it) }) })
        Text("铃声／通知通常联动，震动跟随铃声模式")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(RingPolicy.KEEP to "保持", RingPolicy.VIBRATE to "震动", RingPolicy.SILENT to "静音").forEach { pair ->
                Button(onClick = { policy = policy.copy(ring = pair.first) }) { Text((if (policy.ring == pair.first) "✓ " else "") + pair.second) }
            }
        }
        BasicComponent(title = "闹钟也静音（会影响提醒）", endActions = { Switch(checked = policy.alarms == AlarmPolicy.MUTE, onCheckedChange = { policy = policy.copy(alarms = if (it) AlarmPolicy.MUTE else AlarmPolicy.KEEP) }) })
        BasicComponent(title = "抢占音频焦点", summary = "获准后播放零 PCM 静音音频维持活动；可能增加耗电，不能阻止忽略焦点的播放器", endActions = { Switch(checked = policy.focusTakeover, onCheckedChange = { policy = policy.copy(focusTakeover = it) }) })
        BasicComponent(title = "限制屏幕亮度", endActions = { Switch(checked = policy.brightnessCap != null, onCheckedChange = { policy = policy.copy(brightnessCap = if (it) 40 else null) }) })
        policy.brightnessCap?.let { percent ->
            Text("亮度上限 $percent%（5—100%，只降不升）")
            Slider(percent.toFloat(), { policy = policy.copy(brightnessCap = it.toInt()) }, valueRange = 5f..100f, steps = 94)
            FlowRow { listOf(20, 40, 70).forEach { cap -> Button(onClick = { policy = policy.copy(brightnessCap = cap) }) { Text("$cap%") } } }
        }
        BasicComponent(title = "锁定亮度（持续纠正）", endActions = { Switch(checked = policy.lockBrightness, onCheckedChange = { policy = policy.copy(lockBrightness = it) }) })
        Text("通话音量保持原设置。暂停存在兼容性限制，不保证绝对零漏音。")
        error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
        Button(onClick = {
            runCatching {
                require(name.isNotBlank() && name.length <= 80) { "模板名称应为 1—80 字" }; policy.validate()
                onSave(GuardTemplate(if (initial.builtIn) UUID.randomUUID().toString() else initial.id, name.trim(), policy))
            }.onFailure { error = it.message }
        }) { Text("保存自定义模板") }
        Button(onClick = onDismiss) { Text("取消") }
    }
}

@Composable internal fun TrustedDevicesPanel(doc: GuardDocument, onChange: ((GuardDocument) -> GuardDocument) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    var paired by remember { mutableStateOf<List<TrustedDevice>>(emptyList()) }
    fun refresh() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            paired = context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .map { TrustedDevice(it.address, it.name ?: "未命名设备") }.sortedBy { it.label }
        }.onFailure { onError(it.message ?: "无法读取已配对设备") }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("可信耳机", style = MiuixTheme.textStyles.title3)
        Text("手动选择具体设备。蓝牙已连接不代表正在输出；路由不明时继续保护。")
        Button(onClick = ::refresh) { Text("刷新已配对设备") }
        if (paired.isEmpty()) Text("暂无可读取的设备；请先在系统配对并授予蓝牙权限。")
        (paired + doc.trustedDevices).distinctBy { it.address }.forEach { device ->
            BasicComponent(title = "${device.label} · ${device.address.takeLast(8)}", endActions = { Switch(checked = doc.trustedDevices.any { it.address == device.address }, onCheckedChange = { trusted ->
                onChange { current -> current.copy(trustedDevices = current.trustedDevices.filterNot { it.address == device.address } + if (trusted) listOf(device) else emptyList()) }
            }) })
        }
        BasicComponent(title = "信任有线耳机", endActions = { Switch(checked = doc.trustWired, onCheckedChange = { value -> onChange { it.copy(trustWired = value) } }) })
        BasicComponent(title = "信任 USB 耳机", endActions = { Switch(checked = doc.trustUsb, onCheckedChange = { value -> onChange { it.copy(trustUsb = value) } }) })
    }
}

@Composable internal fun ApiEditor(onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { AiImportSettingsStore.load(context) }
    var base by remember { mutableStateOf(saved.profile.baseUrl) }
    var model by remember { mutableStateOf(saved.profile.defaultModel) }
    var secret by remember { mutableStateOf(saved.apiKey) }
    var responses by remember { mutableStateOf(saved.profile.endpointStyle == AiEndpointStyle.RESPONSES) }
    var vision by remember { mutableStateOf(saved.profile.supportsVision) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    EditorDialog("自己的 AI 接口", onDismiss) {
        Text("此配置与课表图片／PDF 导入共用。密钥由 Android Keystore 加密保存，不进入规则备份。")
        TextField(base, { base = it }, label = "HTTPS API 基础地址（含 /v1 等前缀）", singleLine = true)
        TextField(model, { model = it }, label = "模型 ID", singleLine = true)
        TextField(secret, { secret = it }, label = "API Key", singleLine = true, visualTransformation = PasswordVisualTransformation())
        BasicComponent(title = "使用 Responses 协议", endActions = { Switch(checked = responses, onCheckedChange = { responses = it }) })
        BasicComponent(title = "此模型支持图片（由用户声明）", endActions = { Switch(checked = vision, onCheckedChange = { vision = it }) })
        Text("文本连接成功不代表图片可用；请在课表导入中选择一张图片完成实际验证。文件上传前会先确认。")
        error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
        fun save(): Boolean = runCatching {
            val uri = URI(base.trim())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "请输入不含凭据／查询参数的 HTTPS 基础地址" }
            require(model.isNotBlank() && secret.isNotBlank()) { "请填写模型和密钥" }
            AiImportSettingsStore.save(context, AiImportSettings(AiProviderProfile(
                id = "custom:snow-user", displayName = "我的 AI 接口", providerType = if (responses) AiProviderType.OpenAIResponses else AiProviderType.OpenAIChatCompatible,
                baseUrl = base.trim().trimEnd('/'), defaultModel = model.trim(),
                capabilities = AiProviderCapabilities(supportsImageInput = vision, supportsResponses = responses), supportsVision = vision,
                structuredOutputMode = StructuredOutputMode.PROMPT_ONLY
            ), secret.trim()))
            true
        }.getOrElse { error = it.message; false }
        Button(enabled = !busy, onClick = { if (save()) { onMessage("AI 配置已保存"); onDismiss() } }) { Text("保存配置") }
        Button(enabled = !busy, onClick = { if (save()) scope.launch {
            busy = true
            try { error = GuardAi.test(context) } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
            finally { busy = false }
        } }) { Text(if (busy) "测试中…" else "保存并测试文本连接") }
        Button(onClick = onDismiss) { Text("返回") }
    }
}
