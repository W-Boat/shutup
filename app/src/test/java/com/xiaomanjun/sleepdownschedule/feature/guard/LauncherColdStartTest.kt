package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import com.xiaomanjun.sleepdownschedule.core.identity.AppIconManager
import com.xiaomanjun.sleepdownschedule.core.identity.AppIconStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Host JVM check of manifest/default preferences, without starting an emulator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class LauncherColdStartTest {
    @Test fun freshInstallKeepsManifestLauncherAndDoesNotWriteRedundantOverrides() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app_icon_preferences", 0).edit().clear().commit()
        assertEquals(AppIconStyle.MINIMAL, AppIconManager.currentStyle(context))
        val component = ComponentName(context.packageName, "com.xiaomanjun.sleepdownschedule.LauncherFollow")
        assertTrue(context.packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS).enabled)
        AppIconManager.applyStoredMode(context)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            context.packageManager.getComponentEnabledSetting(component))
    }
}
