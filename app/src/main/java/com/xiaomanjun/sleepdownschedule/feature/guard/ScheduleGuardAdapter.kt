package com.xiaomanjun.sleepdownschedule.feature.guard

import com.xiaomanjun.sleepdownschedule.data.repository.ScheduleRepository
import com.xiaomanjun.sleepdownschedule.domain.guard.*
import com.xiaomanjun.sleepdownschedule.domain.schedule.coursesForDate
import com.xiaomanjun.sleepdownschedule.domain.schedule.courseTimeSegments
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Same resolved occurrences as upstream day/week views, including holiday adjustments. */
internal suspend fun ScheduleRepository.guardLessons(document: GuardDocument, now: Long, zone: ZoneId): List<LessonOccurrence> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return document.rules.filter { it.enabled && it.trigger == Trigger.COURSES }.mapNotNull { it.scheduleId }.distinct().flatMap { id ->
        val state = scheduleSnapshot(id)
        // The upstream display can use a manual week without a term date. Automatic protection cannot.
        if (runCatching { LocalDate.parse(state.config.termStartDate) }.isFailure) return@flatMap emptyList()
        (-2L..9L).flatMap { day ->
            val date = today.plusDays(day)
            coursesForDate(state, date).mapNotNull { course ->
                val segments = courseTimeSegments(course, state.periods)
                val start = segments.minOfOrNull { it.start } ?: return@mapNotNull null
                val end = segments.maxOf { it.end }
                if (end <= start) return@mapNotNull null
                LessonOccurrence(id, course.name, date.atTime(start).atZone(zone).toInstant().toEpochMilli(),
                    date.atTime(end).atZone(zone).toInstant().toEpochMilli())
            }
        }
    }
}
