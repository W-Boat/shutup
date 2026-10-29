package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.xiaomanjun.sleepdownschedule.R

/**
 * Long-press shortcuts: the fastest path to the three operations a student actually repeats.
 * Dynamic (not static XML) so the actions can carry their command extras.
 */
internal object GuardShortcuts {
    private const val StartId = "guard_start"
    private const val StopId = "guard_stop"
    private const val BypassId = "guard_bypass"

    fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < 25) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val icon = Icon.createWithResource(context, R.drawable.ic_snow_guard)
        fun shortcut(id: String, label: String, longLabel: String, action: String, rank: Int) =
            ShortcutInfo.Builder(context, id)
                .setShortLabel(label)
                .setLongLabel(longLabel)
                .setIcon(icon)
                .setRank(rank)
                .setIntent(
                    Intent(context, SnowLeopardActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra(SnowLeopardActivity.EXTRA_GUARD_ACTION, action)
                )
                .build()
        val shortcuts = listOf(
            shortcut(StartId, "立即保护", "立即开始静音保护", "protect", 0),
            shortcut(StopId, "结束保护", "结束本次静音保护", "end", 1),
            shortcut(BypassId, "放行 5 分钟", "临时放行 5 分钟", "bypass", 2)
        )
        runCatching { manager.setDynamicShortcuts(shortcuts) }
    }

    fun clear(context: Context) {
        runCatching { context.getSystemService(ShortcutManager::class.java)?.removeAllDynamicShortcuts() }
    }
}
