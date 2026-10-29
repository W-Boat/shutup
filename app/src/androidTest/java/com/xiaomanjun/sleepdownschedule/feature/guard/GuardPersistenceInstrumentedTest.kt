package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import android.content.Context
import com.xiaomanjun.sleepdownschedule.data.guard.*
import com.xiaomanjun.sleepdownschedule.domain.guard.*
import com.xiaomanjun.sleepdownschedule.AppDatabase
import com.xiaomanjun.sleepdownschedule.MainActivity
import com.xiaomanjun.sleepdownschedule.data.repository.ScheduleRepository
import com.xiaomanjun.sleepdownschedule.model.CourseEntity
import com.xiaomanjun.sleepdownschedule.model.WeekParity
import com.xiaomanjun.sleepdownschedule.model.ImportDraft
import com.xiaomanjun.sleepdownschedule.model.defaultConfig
import com.xiaomanjun.sleepdownschedule.model.defaultPeriods
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuardPersistenceInstrumentedTest {
    @Test fun rulesPersistAndInvalidWritesDoNotReplaceThem() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = GuardStore(context)
        store.update { GuardDocument(rules = listOf(GuardRule("test", "测试", "strict", Trigger.ALL_DAY, enabled = false))) }
        assertEquals("test", GuardStore(context).document.first().rules.single().id)
        try { store.update { it.copy(rules = listOf(GuardRule("bad", "", "missing", Trigger.WEEKLY))) }; fail("must reject") }
        catch (_: IllegalArgumentException) { }
        assertEquals("test", store.document.first().rules.single().id)
        store.update { GuardDocument() }
    }
    @Test fun localDraftSurvivesDaoReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        GuardDatabase.get(context).dao().saveDraft(GuardDraft("test", 123, "draft"))
        assertEquals("draft", GuardDatabase.get(context).dao().draft("test")?.payload)
        GuardDatabase.get(context).dao().deleteDraft("test")
    }
    @Test fun homeLaunchesWithoutAutomaticallyProtecting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking { GuardStore(context).update { GuardDocument() } }
        // The guard page now lives inside the course app: the legacy activity is a trampoline and
        // the real surface is the settings detail host showing SettingsPage.Guard.
        val intent = Intent(context, com.xiaomanjun.sleepdownschedule.SettingsDetailActivity::class.java)
            .putExtra(
                com.xiaomanjun.sleepdownschedule.app.ui.SettingsDetailPageExtra,
                com.xiaomanjun.sleepdownschedule.feature.settings.SettingsPage.Guard.name
            )
        ActivityScenario.launch<com.xiaomanjun.sleepdownschedule.SettingsDetailActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }
    @Test fun originalTimetableEntryRemainsLaunchable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }
    @Test fun overwriteKeepsRecoverableScheduleAndUndoPreservesNewImport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ScheduleRepository(database)
            repo.ensureDefaults()
            fun course(name: String) = CourseEntity(name = name, teacher = null, location = null,
                weekday = 1, periods = listOf(1), weeks = listOf(1), weekParity = WeekParity.ALL, note = null)
            repo.addCourse(course("原课程"))
            repo.importDraft(ImportDraft(defaultConfig(), defaultPeriods(), listOf(course("新课程"))))
            assertEquals("新课程", repo.activeSnapshot().courses.single().name)
            repo.undoLastGuardImport()
            assertEquals("原课程", repo.activeSnapshot().courses.single().name)
            assertTrue(repo.snapshot().allCourses.any { it.name == "新课程" })
        } finally { database.close() }
    }
    @Test fun emptyScheduleCanAlsoBeRecoveredAfterImport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ScheduleRepository(database)
            repo.ensureDefaults()
            val before = repo.activeSnapshot()
            val imported = CourseEntity(name = "误导入", teacher = null, location = null,
                weekday = 1, periods = listOf(1), weeks = listOf(1), weekParity = WeekParity.ALL, note = null)
            repo.importDraft(ImportDraft(defaultConfig(), defaultPeriods(), listOf(imported)))
            repo.undoLastGuardImport()
            val recovered = repo.activeSnapshot()
            assertTrue(recovered.courses.isEmpty())
            assertEquals(before.config.termStartDate, recovered.config.termStartDate)
            assertEquals(before.periods.map { it.startTime to it.endTime }, recovered.periods.map { it.startTime to it.endTime })
        } finally { database.close() }
    }
}
