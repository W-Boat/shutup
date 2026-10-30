package com.xiaomanjun.sleepdownschedule.domain.guard

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Serializable enum class Trigger { ALL_DAY, WEEKLY, COURSES }
@Serializable enum class RingPolicy { KEEP, VIBRATE, SILENT }
@Serializable enum class AlarmPolicy { KEEP, MUTE }

@Serializable
data class SoundPolicy(
    val mediaCap: Int? = 0,
    val pausePlayback: Boolean = false,
    val lockVolume: Boolean = true,
    val ring: RingPolicy = RingPolicy.SILENT,
    val alarms: AlarmPolicy = AlarmPolicy.KEEP,
    val trustedOutputAllowed: Boolean = true,
    /** Screen brightness cap in percent of the device maximum; null keeps the user's level. */
    val brightnessCap: Int? = null,
    val lockBrightness: Boolean = false,
    /** Preempt other apps' audio focus while the protection window is active. */
    val focusTakeover: Boolean = true
) {
    fun validate() {
        require(mediaCap == null || mediaCap in 0..100) { "媒体上限必须在 0—100% 之间" }
        require(brightnessCap == null || brightnessCap in 5..100) { "亮度上限必须在 5—100% 之间" }
    }
}

@Serializable
data class GuardTemplate(val id: String, val name: String, val policy: SoundPolicy, val builtIn: Boolean = false)

object GuardTemplates {
    val builtIns = listOf(
        GuardTemplate("strict", "课堂严格静音", SoundPolicy(), true),
        GuardTemplate("pause", "课堂暂停优先", SoundPolicy(pausePlayback = true), true),
        GuardTemplate("cap10", "课堂限音 10%", SoundPolicy(mediaCap = 10, ring = RingPolicy.VIBRATE), true),
        GuardTemplate("cap20", "课堂限音 20%", SoundPolicy(mediaCap = 20, ring = RingPolicy.VIBRATE), true),
        GuardTemplate("library", "图书馆", SoundPolicy(), true),
        GuardTemplate("meeting", "会议", SoundPolicy(ring = RingPolicy.VIBRATE, lockVolume = false), true)
    )
}

@Serializable
data class GuardRule(
    val id: String,
    val name: String,
    val templateId: String,
    val trigger: Trigger,
    val enabled: Boolean = true,
    val weekdays: Set<Int> = (1..5).toSet(),
    val start: String = "08:00",
    val end: String = "12:00",
    val scheduleId: Int? = null,
    val leadMinutes: Int = 2,
    val tailMinutes: Int = 1,
    val validFrom: String? = null,
    val validThrough: String? = null
) {
    fun validate(templates: List<GuardTemplate>) {
        require(id.isNotBlank() && name.isNotBlank() && name.length <= 80) { "请填写规则名称（最多 80 字）" }
        require(templates.any { it.id == templateId }) { "请先选择保护模板" }
        require(weekdays.isNotEmpty() && weekdays.all { it in 1..7 }) { "请选择有效的重复日期" }
        require(leadMinutes in 0..30 && tailMinutes in 0..30) { "提前／延后时间应为 0—30 分钟" }
        if (trigger == Trigger.WEEKLY) require(LocalTime.parse(start) != LocalTime.parse(end)) { "起止时间不能相同；全天请使用全天规则" }
        if (trigger == Trigger.COURSES) require(scheduleId != null) { "请选择要保护的课表" }
        val from = validFrom?.let(LocalDate::parse)
        val through = validThrough?.let(LocalDate::parse)
        require(from == null || through == null || !through.isBefore(from)) { "结束日期早于开始日期" }
    }
}

@Serializable data class TrustedDevice(val address: String, val label: String)

@Serializable
data class GuardDocument(
    val schemaVersion: Int = 1,
    val persona: String? = null,
    val rules: List<GuardRule> = emptyList(),
    val customTemplates: List<GuardTemplate> = emptyList(),
    val trustedDevices: List<TrustedDevice> = emptyList(),
    val trustWired: Boolean = false,
    val trustUsb: Boolean = false,
    val enhanced: Boolean = false,
    val selectedTemplateId: String? = null,
    val manualUntil: Long = 0,
    val bypassUntil: Long = 0,
    val skipUntil: Long = 0,
    /** Optional su activation: hard volume/brightness writes and battery whitelist. */
    val rootMode: Boolean = false,
    /** Explicit opt-in to a separate root watchdog; never inferred from root audio mode. */
    val rootKeepAlive: Boolean = false,
    val rootAccessibility: Boolean = false,
    /** 1px transparent overlay window that keeps the protection process out of the freezer. */
    val keepAliveOverlay: Boolean = false,
    /** Publish guard state to Live Updates / Xiaomi focus island. */
    val liveActivity: Boolean = true,
    /** Seconds between the pre-protection reminder and the actual mute; 0 disables it. */
    val preReminderSeconds: Int = 10,
    /** 上课锁机 */
    val lock: LockSettings = LockSettings(),
    /** AI 主动关怀通知 */
    val care: CareSettings = CareSettings()
) {
    val templates: List<GuardTemplate> get() = GuardTemplates.builtIns + customTemplates
    fun validate() {
        require(schemaVersion == 1) { "不支持的规则版本" }
        require(rules.size <= 200 && customTemplates.size <= 100) { "规则或模板数量超过上限" }
        require(customTemplates.none { it.builtIn || it.id in GuardTemplates.builtIns.map { t -> t.id } }) { "固定模板不可覆盖" }
        require(templates.map { it.id }.distinct().size == templates.size) { "模板编号重复" }
        require(rules.map { it.id }.distinct().size == rules.size) { "规则编号重复" }
        require(preReminderSeconds in 0..120) { "保护前提醒应在 0—120 秒之间" }
        lock.validate()
        care.validate()
        templates.forEach { it.policy.validate() }
        rules.forEach { it.validate(templates) }
        require(selectedTemplateId == null || templates.any { it.id == selectedTemplateId }) { "所选模板不存在" }
    }
}

data class LessonOccurrence(val scheduleId: Int, val name: String, val start: Long, val end: Long, val courseId: Long? = null)
data class ProtectionWindow(val ruleId: String, val reason: String, val templateId: String, val start: Long, val end: Long,
    val classStart: Long = start, val classEnd: Long = end, val isCourse: Boolean = false,
    val courseKey: String? = null) {
    fun contains(now: Long) = now >= start && now < end
    fun inClass(now: Long) = now >= classStart && now < classEnd
    val classKey: String get() = if (isCourse && courseKey != null) "course:$courseKey|$classStart" else "$ruleId|$classStart"
}

/** Compile wall-clock schedules in the current zone; never use fixed 24h arithmetic across DST. */
object RuleCompiler {
    fun compile(document: GuardDocument, now: Long, zone: ZoneId, lessons: List<LessonOccurrence>): List<ProtectionWindow> {
        document.validate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dates = (-2L..9L).map(today::plusDays)
        return document.rules.filter { it.enabled }.flatMap { rule ->
            fun valid(date: LocalDate) = (rule.validFrom == null || date >= LocalDate.parse(rule.validFrom)) &&
                (rule.validThrough == null || date <= LocalDate.parse(rule.validThrough))
            if (rule.trigger == Trigger.COURSES) {
                lessons.filter { it.scheduleId == rule.scheduleId && valid(Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate()) }
                    .map { ProtectionWindow(rule.id, it.name, rule.templateId,
                        it.start - rule.leadMinutes * 60_000L, it.end + rule.tailMinutes * 60_000L,
                        it.start, it.end, true, it.courseId?.let { courseId -> LockCourseScope.key(it.scheduleId, courseId) }) }
            } else dates.filter { it.dayOfWeek.value in rule.weekdays && valid(it) }.map { date ->
                val allDay = rule.trigger == Trigger.ALL_DAY
                val start = if (allDay) date.atStartOfDay(zone) else date.atTime(LocalTime.parse(rule.start)).atZone(zone)
                val end = if (allDay) date.plusDays(1).atStartOfDay(zone) else {
                    val endTime = LocalTime.parse(rule.end)
                    val endDate = if (endTime <= LocalTime.parse(rule.start)) date.plusDays(1) else date
                    endDate.atTime(endTime).atZone(zone)
                }
                ProtectionWindow(rule.id, rule.name, rule.templateId,
                    start.toInstant().toEpochMilli() - if (allDay) 0 else rule.leadMinutes * 60_000L,
                    end.toInstant().toEpochMilli() + if (allDay) 0 else rule.tailMinutes * 60_000L,
                    start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            }
        }.filter { it.end > it.start }.sortedBy { it.start }
    }
}

data class RouteEvidence(val identity: String, val exclusive: Boolean, val trusted: Boolean, val description: String)
data class ResolvedPolicy(val policy: SoundPolicy?, val reasons: List<String>, val trustedRelease: Boolean, val nextChange: Long?)

object PolicyResolver {
    fun resolve(document: GuardDocument, windows: List<ProtectionWindow>, now: Long, route: RouteEvidence): ResolvedPolicy {
        val boundaries = windows.flatMap { listOf(it.start, it.end) } +
            listOf(document.manualUntil, document.bypassUntil, document.skipUntil)
        val next = boundaries.filter { it > now }.minOrNull()
        if (document.bypassUntil > now || document.skipUntil > now) return ResolvedPolicy(null, listOf("临时放行"), false, next)
        val active = windows.filter { it.contains(now) }
        val policies = active.mapNotNull { window -> document.templates.find { it.id == window.templateId }?.policy }.toMutableList()
        if (document.manualUntil > now) document.templates.find { it.id == document.selectedTemplateId }?.let { policies += it.policy }
        if (policies.isEmpty()) return ResolvedPolicy(null, emptyList(), false, next)
        val combined = SoundPolicy(
            mediaCap = policies.mapNotNull { it.mediaCap }.minOrNull(),
            pausePlayback = policies.any { it.pausePlayback }, lockVolume = policies.any { it.lockVolume },
            ring = policies.maxBy { it.ring.ordinal }.ring,
            alarms = if (policies.any { it.alarms == AlarmPolicy.MUTE }) AlarmPolicy.MUTE else AlarmPolicy.KEEP,
            trustedOutputAllowed = policies.all { it.trustedOutputAllowed },
            brightnessCap = policies.mapNotNull { it.brightnessCap }.minOrNull(),
            lockBrightness = policies.any { it.lockBrightness },
            focusTakeover = policies.any { it.focusTakeover }
        )
        val release = combined.trustedOutputAllowed && route.exclusive && route.trusted
        return ResolvedPolicy(combined, active.map { it.reason }.distinct() +
            if (document.manualUntil > now) listOf("手动保护") else emptyList(), release, next)
    }

    /** Floor to a supported index; a cap must never round up or raise existing volume. */
    fun cappedVolume(current: Int, maximum: Int, percent: Int): Int = minOf(current, maximum * percent.coerceIn(0, 100) / 100)

    fun endOfCurrentSession(windows: List<ProtectionWindow>, now: Long, manualUntil: Long): Long {
        var end = maxOf(now, manualUntil)
        var changed: Boolean
        do {
            val next = windows.filter { (it.start < end || (end == now && it.start == now)) && it.end > now }.maxOfOrNull { it.end } ?: end
            changed = next > end
            end = maxOf(end, next)
        } while (changed)
        return if (end == now) now else end
    }
}

@Serializable data class OwnedVolume(val original: Int, val applied: Int, val confirmed: Boolean = false)
@Serializable data class GuardRuntime(
    val media: Map<String, OwnedVolume> = emptyMap(),
    val ring: OwnedVolume? = null,
    val alarm: OwnedVolume? = null,
    val brightness: OwnedVolume? = null,
    val brightnessMode: OwnedVolume? = null,
    val previousRoute: String? = null,
    val previousTrusted: Boolean = false,
    val policyKey: String? = null
)

data class Capability(val title: String, val available: Boolean, val detail: String)
data class CapabilityReport(val items: List<Capability>)

