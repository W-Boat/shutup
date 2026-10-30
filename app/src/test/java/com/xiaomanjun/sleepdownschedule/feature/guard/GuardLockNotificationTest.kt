package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.Application
import com.xiaomanjun.sleepdownschedule.domain.guard.ProtectionWindow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GuardLockNotificationTest {
    @Test fun startActionTargetsActivityDirectlyAndRetainsSessionExpiry() {
        val context = RuntimeEnvironment.getApplication()
        val window = ProtectionWindow("r", "数学", "strict", 1000, 5000)
        val pending = GuardLockController.startPendingIntent(context, window)
        val shadow = shadowOf(pending)
        assertTrue(shadow.isActivityIntent)
        assertEquals(GuardLockActivity::class.java.name, shadow.savedIntent.component?.className)
        assertEquals(window.classKey, shadow.savedIntent.getStringExtra(GuardLockController.WindowKey))
        assertEquals(window.classEnd, shadow.savedIntent.getLongExtra(GuardLockController.ExpiresAt, 0))
    }
}
