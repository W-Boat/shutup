package com.xiaomanjun.sleepdownschedule.domain.guard

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ClassTimeTest {
    @Test fun protectionPaddingDoesNotShiftClassOrDecisionTime() {
        val at = Instant.parse("2026-10-30T08:00:00Z").toEpochMilli()
        val doc = GuardDocument(rules = listOf(GuardRule("r", "课程", "strict", Trigger.COURSES,
            scheduleId = 1, leadMinutes = 30, tailMinutes = 10)))
        val window = RuleCompiler.compile(doc, at, ZoneId.of("UTC"),
            listOf(LessonOccurrence(1, "数学", at, at + 3600000))).single()
        assertEquals(at, window.classStart)
        assertTrue(window.contains(at - 1))
        assertFalse(window.inClass(at - 1))
        assertFalse(window.inClass(at + 3600000))
    }
    @Test fun oldDocumentsAndRuntimeDecodeWithDefaults() {
        val json = Json { ignoreUnknownKeys = true }
        assertFalse(json.decodeFromString<GuardDocument>("{}").lock.enabled)
        assertNull(json.decodeFromString<GuardRuntime>("{}").brightnessMode)
    }
}
