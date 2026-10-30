package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.xiaomanjun.sleepdownschedule.model.defaultConfig
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuardOnboardingRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeMiuixIntroCanMountAndDismissWithoutAnOverlayScaffold() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("shutup_onboarding", 0)
        prefs.edit().clear().commit()
        compose.setContent { GuardFirstLaunchPrompt(true, defaultConfig()) }
        compose.onNodeWithText("先了解保护权限").assertExists()
        compose.onNodeWithText("稍后设置").performClick()
        compose.runOnIdle { assertTrue(prefs.getBoolean("permissions_introduced", false)) }
    }
}
