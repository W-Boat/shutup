package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.Context
import com.xiaomanjun.sleepdownschedule.data.guard.*
import com.xiaomanjun.sleepdownschedule.domain.guard.*
import com.xiaomanjun.sleepdownschedule.feature.importing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.UUID

@Serializable internal data class GuardChatMessage(val role: String, val content: String)
@Serializable internal data class RuleProposal(
    val name: String, val templateId: String, val trigger: Trigger,
    val weekdays: Set<Int> = (1..7).toSet(), val start: String = "08:00", val end: String = "12:00",
    val validFrom: String? = null, val validThrough: String? = null,
    val leadMinutes: Int = 2, val tailMinutes: Int = 1, val policy: SoundPolicy? = null
)
@Serializable internal data class AiRuleAnswer(val questions: List<String> = emptyList(), val rules: List<RuleProposal> = emptyList())
@Serializable internal data class PendingGuardDraft(
    val baseFingerprint: String, val rules: List<GuardRule>, val templates: List<GuardTemplate> = emptyList()
)

internal object GuardAi {
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private fun fingerprint(doc: GuardDocument): String = MessageDigest.getInstance("SHA-256")
        .digest((json.encodeToString(doc.rules) + json.encodeToString(doc.templates)).toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun parse(text: String): AiRuleAnswer {
        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        require(clean.length <= 64_000) { "AI 输出过长" }
        return json.decodeFromString<AiRuleAnswer>(clean).also {
            require(it.rules.size <= 20 && it.questions.size <= 3) { "单次最多生成 20 条规则或提出 3 个问题" }
            require(it.rules.isNotEmpty() || it.questions.isNotEmpty()) { "AI 未返回规则或澄清问题" }
        }
    }

    suspend fun propose(context: Context, document: GuardDocument, messages: List<GuardChatMessage>): Pair<AiRuleAnswer, PendingGuardDraft?> {
        val settings = AiImportSettingsStore.load(context)
        require(settings.apiKey.isNotBlank()) { "请先配置自己的 AI API 和模型" }
        val prompt = """
            你是雪豹闭嘴的声音规则助手。今天是 ${ZonedDateTime.now()}，使用用户当前时区。
            只输出严格 JSON：{"questions":[],"rules":[{"name":"规则名称","templateId":"strict","trigger":"WEEKLY","weekdays":[1,2,3,4,5],"start":"08:00","end":"12:00","validFrom":null,"validThrough":null,"leadMinutes":2,"tailMinutes":1,"policy":null}]}。
            trigger 仅 ALL_DAY 或 WEEKLY；日期 YYYY-MM-DD，时间 HH:mm，星期 1=周一，7=周日。明确全天用 ALL_DAY。
            可选模板：${document.templates.joinToString { "${it.id}=${it.name}" }}。
            可按明确要求提供 policy：{"mediaCap":0,"pausePlayback":false,"lockVolume":true,"ring":"SILENT","alarms":"KEEP","trustedOutputAllowed":true}。
            mediaCap 为 null 或0到100；ring为 KEEP/VIBRATE/SILENT；alarms为 KEEP/MUTE。
            若时间、周期、模板或意图不明确，questions 提出最多3个问题并令 rules=[]；不猜测。
            不能授权设备、操作系统、修改现有规则或执行脚本；指定耳机时提示用户在设备页选择具体设备。
            根据完整对话生成本轮新增规则。保护策略未指定时先询问，不能替用户选择模板。
        """.trimIndent()
        val text = request(settings, listOf(GuardChatMessage("system", prompt)) + messages.takeLast(12))
        currentCoroutineContext().ensureActive()
        val answer = parse(text)
        if (answer.questions.isNotEmpty()) return answer to null
        val templates = mutableListOf<GuardTemplate>()
        val rules = answer.rules.map { proposal ->
            require(proposal.trigger != Trigger.COURSES) { "跟随课表请在规则页面明确选择课表" }
            val template = proposal.policy?.let { policy ->
                GuardTemplate(UUID.randomUUID().toString(), "AI · ${proposal.name}", policy).also { templates += it }
            }
            GuardRule(UUID.randomUUID().toString(), proposal.name, template?.id ?: proposal.templateId,
                proposal.trigger, weekdays = proposal.weekdays, start = proposal.start, end = proposal.end,
                validFrom = proposal.validFrom, validThrough = proposal.validThrough,
                leadMinutes = proposal.leadMinutes, tailMinutes = proposal.tailMinutes).also { it.validate(document.templates + templates) }
        }
        val draft = PendingGuardDraft(fingerprint(document), rules, templates)
        GuardDatabase.get(context).dao().saveDraft(GuardDraft("ai-pending", System.currentTimeMillis(), json.encodeToString(draft)))
        return answer to draft
    }

    suspend fun confirm(context: Context, draft: PendingGuardDraft) {
        GuardStore(context).update { doc ->
            if (draft.rules.all { rule -> doc.rules.any { it == rule } }) doc
            else {
                require(fingerprint(doc) == draft.baseFingerprint) { "规则或模板已改变，请重新生成预览" }
                doc.copy(rules = doc.rules + draft.rules, customTemplates = doc.customTemplates + draft.templates)
            }
        }
        GuardDatabase.get(context).dao().deleteDraft("ai-pending")
    }

    suspend fun test(context: Context): String {
        val settings = AiImportSettingsStore.load(context)
        require(settings.apiKey.isNotBlank()) { "请先保存 API 配置" }
        request(settings, listOf(GuardChatMessage("user", "Reply with OK.")))
        return "文本连接成功；图片能力需用实际课表图片测试"
    }

    private suspend fun request(settings: AiImportSettings, messages: List<GuardChatMessage>): String = withContext(Dispatchers.IO) {
        val config = settings.toProviderConfig().normalizedForRequest()
        require(java.net.URI(config.baseUrl).scheme == "https") { "AI 接口必须使用 HTTPS" }
        val responses = config.endpointStyle == AiEndpointStyle.RESPONSES
        val body = buildJsonObject {
            put("model", config.model)
            val input = buildJsonArray { messages.forEach { m -> add(buildJsonObject { put("role", m.role); put("content", m.content) }) } }
            if (responses) { put("store", false); put("input", input); put("max_output_tokens", 4096); putResponsesReasoning(config) }
            else { put("messages", input); put("max_tokens", 4096); putChatSamplingAndReasoning(config) }
        }
        val response = postJson(config.resolveRequestEndpoint(), config.apiKey, body.toString(), config.authType, config.providerId)
        currentCoroutineContext().ensureActive()
        if (responses) parseResponsesTextResult(response).content else parseChatCompletionTextResult(response).content
    }
}
