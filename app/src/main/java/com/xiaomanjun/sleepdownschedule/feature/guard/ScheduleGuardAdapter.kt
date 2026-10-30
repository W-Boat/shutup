package com.xiaomanjun.sleepdownschedule.feature.guard

import com.xiaomanjun.sleepdownschedule.data.repository.ScheduleRepository
import com.xiaomanjun.sleepdownschedule.domain.guard.*
import com.xiaomanjun.sleepdownschedule.domain.schedule.coursesForDate
import com.xiaomanjun.sleepdownschedule.domain.schedule.courseTimeSegments
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/** Same resolved occurrences as upstream day/week views, including holiday adjustments. */
internal data class GuardCourseInputs(val lessons: List<LessonOccurrence>, val warnings: List<String>)

internal suspend fun ScheduleRepository.guardLessons(document: GuardDocument, now: Long, zone: ZoneId): GuardCourseInputs {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val warnings = mutableListOf<String>()
    val soundScheduleIds = document.rules.filter { it.enabled && it.trigger == Trigger.COURSES }.mapNotNull { it.scheduleId }
    val lockScheduleIds = if (document.lock.enabled) allSchedulesState.first().schedules.map { it.id } else emptyList()
    val lessons = (soundScheduleIds + lockScheduleIds).distinct().flatMap { id ->
        val state = runCatching { scheduleSnapshot(id) }.getOrElse {
            warnings += "课表 $id 无法读取，请重新选择课表"; return@flatMap emptyList()
        }
        // The upstream display can use a manual week without a term date. Automatic protection cannot.
        if (runCatching { LocalDate.parse(state.config.termStartDate) }.isFailure) {
            warnings += "课表 $id 缺少有效开学日期，该课表尚未自动保护"; return@flatMap emptyList()
        }
        (-2L..9L).flatMap { day ->
            val date = today.plusDays(day)
            coursesForDate(state, date).mapNotNull { course ->
                val segments = courseTimeSegments(course, state.periods)
                val start = segments.minOfOrNull { it.start } ?: return@mapNotNull null
                val end = segments.maxOf { it.end }
                if (end <= start) return@mapNotNull null
                LessonOccurrence(id, course.name, date.atTime(start).atZone(zone).toInstant().toEpochMilli(),
                    date.atTime(end).atZone(zone).toInstant().toEpochMilli(), course.id)
            }
        }
    }
    return GuardCourseInputs(lessons, warnings)
}
