package com.xiaomanjun.sleepdownschedule.domain.guard

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class GuardEngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun time(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val now = time("2026-10-26T09:00")
    private val speaker = RouteEvidence("speaker", false, false, "speaker")
    private fun doc(rule: GuardRule = GuardRule("r", "课堂", "strict", Trigger.WEEKLY)) = GuardDocument(rules = listOf(rule))

    @Test fun nothingArmedByDefault() { assertNull(PolicyResolver.resolve(GuardDocument(), emptyList(), now, speaker).policy) }
    @Test fun fixedTemplatesCannotBeOverwritten() {
        assertThrows(IllegalArgumentException::class.java) { GuardDocument(customTemplates = listOf(GuardTemplates.builtIns.first())).validate() }
    }
    @Test fun overnightBelongsToStartDay() {
        val d = doc(GuardRule("r", "晚自习", "strict", Trigger.WEEKLY, weekdays = setOf(1), start = "22:00", end = "01:00"))
        val windows = RuleCompiler.compile(d, now, zone, emptyList())
        assertTrue(windows.any { it.contains(time("2026-10-27T00:30")) })
        assertFalse(windows.any { it.contains(time("2026-10-28T00:30")) })
    }
    @Test fun allDayIgnoresLeadAndTail() {
        val d = doc(GuardRule("r", "全天", "strict", Trigger.ALL_DAY, weekdays = setOf(1)))
        val window = RuleCompiler.compile(d, now, zone, emptyList()).first { it.contains(now) }
        assertEquals(time("2026-10-26T00:00"), window.start)
        assertEquals(time("2026-10-27T00:00"), window.end)
    }
    @Test fun daylightSavingDayIsNotAlways24Hours() {
        val ny = ZoneId.of("America/New_York")
        val instant = Instant.parse("2026-03-08T12:00:00Z").toEpochMilli()
        val d = doc(GuardRule("r", "全天", "strict", Trigger.ALL_DAY, weekdays = setOf(7)))
        val window = RuleCompiler.compile(d, instant, ny, emptyList()).first { it.contains(instant) }
        assertEquals(23 * 60 * 60_000L, window.end - window.start)
    }
    @Test fun dateBoundsAreRespected() {
        val d = doc(GuardRule("r", "一次", "strict", Trigger.ALL_DAY, weekdays = setOf(1), validFrom = "2026-10-27"))
        assertFalse(RuleCompiler.compile(d, now, zone, emptyList()).any { it.contains(now) })
    }
    @Test fun courseRulesOnlyUseBoundSchedule() {
        val d = doc(GuardRule("r", "课表", "strict", Trigger.COURSES, scheduleId = 2))
        val windows = RuleCompiler.compile(d, now, zone, listOf(LessonOccurrence(1, "错误课表", now - 100, now + 100)))
        assertTrue(windows.isEmpty())
    }
    @Test fun exclusiveEndAndAdvance() {
        val d = doc()
        val windows = RuleCompiler.compile(d, now, zone, emptyList())
        assertTrue(windows.any { it.contains(time("2026-10-26T07:58")) })
        assertFalse(windows.any { it.contains(time("2026-10-26T12:01")) })
    }
    @Test fun conflictingRulesTakeStricterPolicy() {
        val a = GuardRule("a", "限音", "cap20", Trigger.ALL_DAY)
        val b = GuardRule("b", "暂停", "pause", Trigger.ALL_DAY)
        val d = GuardDocument(rules = listOf(a, b))
        val result = PolicyResolver.resolve(d, RuleCompiler.compile(d, now, zone, emptyList()), now, speaker)
        assertEquals(0, result.policy?.mediaCap); assertTrue(result.policy!!.pausePlayback)
        assertEquals(RingPolicy.SILENT, result.policy!!.ring)
    }
    @Test fun connectionWithoutRouteDoesNotGrantTrust() {
        val d = doc()
        val result = PolicyResolver.resolve(d, RuleCompiler.compile(d, now, zone, emptyList()), now, RouteEvidence("headset", false, true, "connected"))
        assertFalse(result.trustedRelease)
    }
    @Test fun trustedExceptionRequiresAllRulesToAllowIt() {
        val blocked = GuardTemplate("blocked", "不放行", SoundPolicy(trustedOutputAllowed = false))
        val d = GuardDocument(customTemplates = listOf(blocked), rules = listOf(GuardRule("r", "考试", "blocked", Trigger.ALL_DAY)))
        assertFalse(PolicyResolver.resolve(d, RuleCompiler.compile(d, now, zone, emptyList()), now, RouteEvidence("headset", true, true, "confirmed")).trustedRelease)
    }
    @Test fun temporaryBypassExpires() {
        val d = doc().copy(bypassUntil = now + 1000)
        val windows = RuleCompiler.compile(d, now, zone, emptyList())
        assertNull(PolicyResolver.resolve(d, windows, now, speaker).policy)
        assertNotNull(PolicyResolver.resolve(d, windows, now + 1000, speaker).policy)
    }
    @Test fun capNeverRoundsUpOrRaisesVolume() {
        assertEquals(1, PolicyResolver.cappedVolume(8, 15, 10))
        assertEquals(0, PolicyResolver.cappedVolume(0, 15, 20))
    }
    @Test fun endSessionIncludesOverlapsButNotTomorrow() {
        val windows = listOf(ProtectionWindow("a", "a", "strict", now - 100, now + 100), ProtectionWindow("b", "b", "strict", now + 50, now + 200), ProtectionWindow("c", "c", "strict", now + 200, now + 300))
        assertEquals(now + 200, PolicyResolver.endOfCurrentSession(windows, now, 0))
    }
}
