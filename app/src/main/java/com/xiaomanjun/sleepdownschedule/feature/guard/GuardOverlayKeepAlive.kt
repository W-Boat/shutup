package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 1×1 transparent, non-touchable overlay window.
 *
 * An overlay-typed window keeps the owning UID marked as "visible" for the app freezer on
 * HyperOS/ColorOS, which is the cheapest legal way to keep the guard service scheduled while
 * the user is in a class. It draws nothing, takes no touches and is removed when the guard
 * window closes, so it costs no frame time.
 */
internal class GuardOverlayKeepAlive(private val context: Context) {
    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun isShowing(): Boolean = view != null

    fun show(): Boolean {
        if (!canShow()) return false
        val manager = windowManager ?: return false
        if (view != null) return true
        val overlay = View(context).apply { setBackgroundColor(android.graphics.Color.TRANSPARENT) }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 0.0f
        }
        return runCatching {
            manager.addView(overlay, params)
            view = overlay
            true
        }.getOrDefault(false)
    }

    fun hide() {
        val overlay = view ?: return
        view = null
        runCatching { windowManager?.removeView(overlay) }
    }
}
