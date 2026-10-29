package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.xiaomanjun.sleepdownschedule.data.guard.*
import com.xiaomanjun.sleepdownschedule.domain.guard.*
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
        ActivityScenario.launch<SnowLeopardActivity>(Intent(context, SnowLeopardActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }
}
