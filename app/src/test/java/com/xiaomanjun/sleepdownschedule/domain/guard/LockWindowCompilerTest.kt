package com.xiaomanjun.sleepdownschedule.domain.guard

import org.junit.Assert.*
import org.junit.Test

class LockWindowCompilerTest {
    private val lesson = LessonOccurrence(1, "数学", 1000, 5000, 10)

    @Test fun lockDoesNotRequireAnySoundRuleOrTemplate() {
        val window = LockWindowCompiler.compile(LockSettings(enabled = true), emptyList(), listOf(lesson)).single()
        assertTrue(window.inClass(1000))
        assertEquals(5000L, window.classEnd)
        assertEquals("1:10", window.courseKey)
        assertEquals("", window.templateId)
    }

    @Test fun courseChoicesApplyWithoutChangingSoundRules() {
        val lessons = listOf(lesson, lesson.copy(scheduleId = 2))
        val allowed = LockSettings(enabled = true, courseAllowlist = listOf("2:10"))
        assertEquals("2:10", LockWindowCompiler.compile(allowed, emptyList(), lessons).single().courseKey)
        assertTrue(LockWindowCompiler.compile(allowed.copy(courseDenylist = listOf("2:10")), emptyList(), lessons).isEmpty())
    }

    @Test fun existingSoundCourseAndDirectCourseShareOneSession() {
        val audio = ProtectionWindow("sound", lesson.name, "strict", 0, 6000, 1000, 5000, true, "1:10")
        val windows = LockWindowCompiler.compile(LockSettings(enabled = true), listOf(audio), listOf(lesson))
        assertEquals(listOf(audio), windows)
    }

    @Test fun disabledLockHasNoWindowsAndBadTimesAreIgnored() {
        assertTrue(LockWindowCompiler.compile(LockSettings(), emptyList(), listOf(lesson)).isEmpty())
        assertTrue(LockWindowCompiler.compile(LockSettings(enabled = true), emptyList(), listOf(lesson.copy(end = 1000))).isEmpty())
    }
}
