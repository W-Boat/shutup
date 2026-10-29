package com.xiaomanjun.sleepdownschedule.feature.guard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.xiaomanjun.sleepdownschedule.data.guard.*
import com.xiaomanjun.sleepdownschedule.domain.guard.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString

@Composable internal fun GuardAiPanel(document: GuardDocument, showConfig: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { GuardDatabase.get(context).dao() }
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<GuardChatMessage>>(emptyList()) }
    var pending by remember { mutableStateOf<PendingGuardDraft?>(null) }
    var working by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var editing by remember { mutableStateOf<GuardRule?>(null) }
    LaunchedEffect(Unit) {
        try {
            dao.draft("ai-conversation")?.let { messages = GuardAi.json.decodeFromString(it.payload) }
            dao.draft("ai-pending")?.let { pending = GuardAi.json.decodeFromString(it.payload) }
        } catch (e: Exception) { onError("AI 草稿读取失败：${e.message}") }
    }
    GuardCard("用一句话安排安静") {
        Text("例如：每周一到周五 08:00—12:00，用课堂严格静音模板。")
        Text("只发送本页对话、当前日期和可选模板。既有规则离线执行；AI 结果必须由你确认。")
        OutlinedButton(onClick = showConfig) { Text("配置／测试自己的 AI 接口") }
        messages.takeLast(12).forEach { m -> Text("${if (m.role == "user") "你" else "雪豹"}：${m.content}") }
        OutlinedTextField(input, { input = it.take(6000) }, label = { Text("描述时段、重复方式和保护策略") }, modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = !working)
        Button(enabled = !working && input.isNotBlank(), onClick = {
            val next = (messages + GuardChatMessage("user", input.trim())).takeLast(12)
            messages = next; input = ""; pending = null; working = true
            job = scope.launch {
                try {
                    dao.deleteDraft("ai-pending")
                    dao.saveDraft(GuardDraft("ai-conversation", System.currentTimeMillis(), GuardAi.json.encodeToString(next)))
                    val (answer, draft) = GuardAi.propose(context, document, next)
                    pending = draft
                    val reply = if (answer.questions.isNotEmpty()) answer.questions.joinToString("\n") else "已生成 ${draft?.rules?.size ?: 0} 条规则，请检查下方时间与策略。"
                    messages = (next + GuardChatMessage("assistant", reply)).takeLast(12)
                    dao.saveDraft(GuardDraft("ai-conversation", System.currentTimeMillis(), GuardAi.json.encodeToString(messages)))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    onError(e.message ?: "AI 请求失败，已有规则未改变")
                } finally { working = false }
            }
        }) { Text(if (working) "正在解析…" else "生成规则预览") }
        if (working) TextButton(onClick = { job?.cancel(); working = false }) { Text("停止") }
        TextButton(enabled = !working, onClick = { scope.launch {
            dao.deleteDraft("ai-conversation"); dao.deleteDraft("ai-pending"); messages = emptyList(); pending = null
        } }) { Text("清空本页对话与草稿") }
        pending?.let { draft ->
            HorizontalDivider()
            Text("待确认草稿（尚未生效）", style = MaterialTheme.typography.titleMedium)
            val previewDoc = document.copy(customTemplates = document.customTemplates + draft.templates)
            draft.rules.forEach { rule ->
                Text(rule.name, style = MaterialTheme.typography.titleSmall)
                Text(ruleSummary(rule, previewDoc))
                previewDoc.templates.find { it.id == rule.templateId }?.let { Text(policySummary(it.policy)) }
                TextButton(onClick = { editing = rule }) { Text("编辑这条规则") }
            }
            Button(enabled = !working, onClick = {
                working = true
                job = scope.launch {
                    try { GuardAi.confirm(context, draft); pending = null; GuardEngine.start(context) }
                    catch (e: Exception) { if (e is CancellationException) throw e; onError(e.message ?: "保存失败，草稿已保留") }
                    finally { working = false }
                }
            }) { Text("确认保存并启用以上规则") }
            TextButton(enabled = !working, onClick = { scope.launch { dao.deleteDraft("ai-pending"); pending = null } }) { Text("放弃草稿") }
        }
    }
    val draft = pending
    if (draft != null) editing?.let { rule ->
        RuleEditor(rule, document.copy(customTemplates = document.customTemplates + draft.templates), emptyList(), onDismiss = { editing = null }, onSave = { edited ->
            scope.launch {
                val updated = draft.copy(rules = draft.rules.map { if (it.id == edited.id) edited else it })
                try { dao.saveDraft(GuardDraft("ai-pending", System.currentTimeMillis(), GuardAi.json.encodeToString(updated))); pending = updated; editing = null }
                catch (e: Exception) { onError(e.message ?: "草稿保存失败") }
            }
        })
    }
}
