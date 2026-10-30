package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.xiaomanjun.sleepdownschedule.core.identity.currentIconResId
import com.xiaomanjun.sleepdownschedule.R
import org.junit.Assert.*
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
    @Test fun launcherUsesSeparateLayersAndActuallyTransparentArtwork() {
        val context = RuntimeEnvironment.getApplication()
        val icon = context.getDrawable(R.mipmap.ic_snow_launcher) as AdaptiveIconDrawable
        assertTrue(icon.background is ColorDrawable)
        assertTrue(icon.foreground is InsetDrawable)
        assertNotNull(icon.monochrome)
        val bitmap = requireNotNull(BitmapFactory.decodeResource(context.resources, R.drawable.ic_snow_leopard_flat))
        assertTrue(bitmap.hasAlpha())
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        assertTrue(Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 0)
    }
}
