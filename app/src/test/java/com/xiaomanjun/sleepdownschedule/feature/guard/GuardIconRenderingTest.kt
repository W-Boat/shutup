package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.xiaomanjun.sleepdownschedule.core.identity.currentIconResId
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
class GuardIconRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsIconLoadsThroughActualComposePainterResource() {
        val context = RuntimeEnvironment.getApplication()
        compose.setContent {
            Image(painterResource(currentIconResId(context)), null,
                Modifier.size(56.dp).testTag("settings-app-icon"))
        }
        compose.onNodeWithTag("settings-app-icon").assertExists()
    }
}
