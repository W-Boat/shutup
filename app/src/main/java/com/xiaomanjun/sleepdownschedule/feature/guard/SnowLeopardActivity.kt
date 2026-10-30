package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.xiaomanjun.sleepdownschedule.SettingsDetailActivity
import com.xiaomanjun.sleepdownschedule.app.ui.SettingsDetailPageExtra
import com.xiaomanjun.sleepdownschedule.feature.settings.SettingsPage

/**
 * Compatibility entry point.
 *
 * Older builds, the notification channel and the system DND rule all reference this component, so
 * it stays registered and forwards straight into the single ShutUp 课程表 shell instead of hosting
 * a second, Material-only "app" surface.
 */
class SnowLeopardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.getStringExtra(EXTRA_GUARD_ACTION)
        // Shortcuts and the notification "open" intent carry a command; run it before the page
        // appears so the state is already correct when the UI binds.
        if (action != null) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, GuardService::class.java).setAction(action)
                )
            }
        }
        val target = Intent(this, SettingsDetailActivity::class.java)
            .putExtra(SettingsDetailPageExtra, SettingsPage.Guard.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (action != null) target.putExtra(EXTRA_GUARD_ACTION, action)
        runCatching { startActivity(target) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_GUARD_ACTION = "guard_action"
    }
}

