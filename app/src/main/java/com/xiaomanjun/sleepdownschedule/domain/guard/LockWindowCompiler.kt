package com.xiaomanjun.sleepdownschedule.domain.guard

/** Locking reads the timetable directly; it never creates or enables an audio rule. */
object LockWindowCompiler {
    fun compile(settings: LockSettings, soundWindows: List<ProtectionWindow>, lessons: List<LessonOccurrence>): List<ProtectionWindow> {
        if (!settings.enabled) return emptyList()
        val courses = lessons.filter { it.end > it.start }.map { lesson ->
            ProtectionWindow("lock-course:${lesson.scheduleId}", lesson.name, "", lesson.start, lesson.end,
                isCourse = true, courseKey = lesson.courseId?.let { LockCourseScope.key(lesson.scheduleId, it) })
        }
        return (soundWindows + courses).distinctBy { it.classKey }
            .filter { LockCourseScope.allows(settings, it) }.sortedBy { it.classStart }
    }
}
