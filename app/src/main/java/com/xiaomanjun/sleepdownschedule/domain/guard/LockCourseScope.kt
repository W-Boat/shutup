package com.xiaomanjun.sleepdownschedule.domain.guard

/** A course name is presentation, never its identity: identical names in two tables stay separate. */
object LockCourseScope {
    fun key(scheduleId: Int, courseId: Long): String = "$scheduleId:$courseId"

    fun allows(settings: LockSettings, window: ProtectionWindow): Boolean = when {
        !window.isCourse -> true // Fixed-time / all-day / manual rules keep their own semantics.
        window.courseKey?.let { it in settings.courseDenylist } == true -> false
        settings.courseAllowlist.isEmpty() -> true
        else -> window.courseKey?.let { it in settings.courseAllowlist } == true
    }
}
