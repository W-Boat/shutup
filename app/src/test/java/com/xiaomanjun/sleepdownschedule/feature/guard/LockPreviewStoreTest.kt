package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.Application
import com.xiaomanjun.sleepdownschedule.data.guard.LockStore
import com.xiaomanjun.sleepdownschedule.domain.guard.LockSession
import com.xiaomanjun.sleepdownschedule.domain.guard.LockStateMachine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LockPreviewStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("shutup_lock", 0).edit().clear().commit() }
    @Test fun previewSurvivesStoreRecreationAndExitNeverConsumesQuota() {
        val store = LockStore(context)
        val test = store.startTest(1000)
        assertTrue(LockStore(context).session().isTest)
        store.transition { LockStateMachine.entered(it, test.windowKey, 1001) }
        assertTrue(store.exit(test.windowKey, 1002, 0))
        assertEquals(0, store.exitsUsed())
        assertTrue(store.exitedWindows().isEmpty())
    }
    @Test fun waitingTestCanEndBeforePinningAndStaleKeyIsRejected() {
        val store = LockStore(context); val test = store.startTest(1000)
        assertFalse(store.endTest("old", 1001))
        assertTrue(store.endTest(test.windowKey, 1001))
        assertFalse(store.session().active)
    }
    @Test fun endTestCannotCancelRealClass() {
        val store = LockStore(context)
        store.transition { LockSession(active = true, windowKey = "real", endsAt = 5000) }
        assertFalse(store.endTest("real", 1000))
        assertTrue(store.session().active)
    }
}
