package com.xiaomanjun.sleepdownschedule.domain.guard

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LockCourseScopeTest {
    private val math = ProtectionWindow("r", "数学", "strict", 0, 100, isCourse = true, courseKey = "1:10")
    @Test fun emptyListsPreserveAllCourses() {
        assertTrue(LockCourseScope.allows(LockSettings(), math))
    }
    @Test fun denylistTakesPriorityEvenIfOldDataHasBoth() {
        assertFalse(LockCourseScope.allows(LockSettings(courseAllowlist = listOf("1:10"), courseDenylist = listOf("1:10")), math))
    }
    @Test fun nonemptyAllowlistLimitsToSelectedCourseIdentity() {
        val settings = LockSettings(courseAllowlist = listOf("1:10"))
        assertTrue(LockCourseScope.allows(settings, math))
        assertFalse(LockCourseScope.allows(settings, math.copy(courseKey = "2:10")))
        assertFalse(LockCourseScope.allows(settings, math.copy(courseKey = "1:11")))
        assertFalse(LockCourseScope.allows(settings, math.copy(courseKey = null)))
        assertNotEquals(math.classKey, math.copy(courseKey = "2:10").classKey)
        assertEquals(math.classKey, math.copy(ruleId = "another-audio-rule").classKey)
    }
    @Test fun fixedAndManualWindowsKeepTheirOwnSemantics() {
        assertTrue(LockCourseScope.allows(LockSettings(courseAllowlist = listOf("missing")), math.copy(isCourse = false)))
    }
    @Test fun olderConfigurationHasEmptyCourseLists() {
        val settings = Json.decodeFromString<LockSettings>("{}")
        assertTrue(settings.courseAllowlist.isEmpty()); assertTrue(settings.courseDenylist.isEmpty())
    }
    @Test fun compilerCarriesIdentityWithoutChangingAudioWindow() {
        val now = java.time.Instant.parse("2026-10-30T08:00:00Z").toEpochMilli()
        val doc = GuardDocument(rules = listOf(GuardRule("r", "课程", "strict", Trigger.COURSES, scheduleId = 2)))
        val window = RuleCompiler.compile(doc, now, java.time.ZoneId.of("UTC"),
            listOf(LessonOccurrence(2, "数学", now, now + 3600000, courseId = 10))).single()
        assertEquals("2:10", window.courseKey)
        assertEquals(now - 120000, window.start)
        assertEquals(now, window.classStart)
    }
}
