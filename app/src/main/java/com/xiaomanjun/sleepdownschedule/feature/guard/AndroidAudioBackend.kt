package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.AutomaticZenRule
import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.core.app.NotificationManagerCompat
import com.xiaomanjun.sleepdownschedule.domain.guard.*

internal class AndroidAudioBackend(
    private val context: Context,
    private val enhanced: Boolean,
    private val rootMode: Boolean = false
) : AudioBackend {
    private val audio = requireNotNull(context.getSystemService(AudioManager::class.java))

    override fun prepare(policy: SoundPolicy?) {
        if (Build.VERSION.SDK_INT < 29) return
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val prefs = context.getSharedPreferences("snow_zen_owner", Context.MODE_PRIVATE)
        val active = policy?.ring == RingPolicy.SILENT
        val storedId = prefs.getString("rule_id", null)
        if (!active && storedId == null) return
        check(manager.isNotificationPolicyAccessGranted) { "需要勿扰访问权限来管理本应用的静音规则" }
        val conditionId = Condition.newId(context).appendPath("snow-speaker-protection").build()
        val entry = storedId?.let { key -> manager.getAutomaticZenRule(key)?.let { key to it } }
            ?: manager.automaticZenRules.entries.firstOrNull { it.value.conditionId == conditionId }?.let { it.key to it.value }
        val id = entry?.first
        val existing = entry?.second
        if (!active && existing == null) return
        val zen = ZenPolicy.Builder().allowAlarms(policy?.alarms != AlarmPolicy.MUTE).allowMedia(true)
            .allowSystem(false).allowCalls(ZenPolicy.PEOPLE_TYPE_NONE).allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
            .allowEvents(false).allowReminders(false).build()
        val ruleId = if (existing != null) {
            if (active && existing.zenPolicy != zen) {
                existing.zenPolicy = zen; manager.updateAutomaticZenRule(requireNotNull(id), existing)
            }
            requireNotNull(id)
        } else {
            @Suppress("DEPRECATION")
            val rule = AutomaticZenRule("ShutUp 课程表 · 声音保护", null,
                ComponentName(context, SnowLeopardActivity::class.java), conditionId, zen,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
            requireNotNull(manager.addAutomaticZenRule(rule)).also { created ->
                check(prefs.edit().putString("rule_id", created).commit()) { "无法保存本应用勿扰规则" }
            }
        }
        val signature = "$ruleId|$active|$zen"
        check(prefs.edit().putString("rule_id", ruleId).commit()) { "无法保存本应用勿扰所有权" }
        if (prefs.getString("state", null) != signature) {
            manager.setAutomaticZenRuleState(ruleId, Condition(conditionId, if (active) "正在保护" else "保护结束",
                if (active) Condition.STATE_TRUE else Condition.STATE_FALSE))
            prefs.edit().putString("state", signature).apply()
        }
    }

    override fun volume(stream: Int): Int = audio.getStreamVolume(stream)
    override fun maximum(stream: Int): Int = audio.getStreamMaxVolume(stream)

    override fun setVolume(stream: Int, value: Int) {
        check(!audio.isVolumeFixed) { "设备采用固定音量，无法限制外放" }
        if (stream == AudioManager.STREAM_MUSIC && enhanced && EnhancedAudioBridge.authorized()) {
            try { EnhancedAudioBridge.setMediaVolume(context, value); return }
            catch (_: Exception) { /* Public API fallback; controller verifies the result. */ }
        }
        if (rootMode && GuardRootBridge.isAuthorized(context) && GuardRootBridge.setStreamVolume(context, stream, value)) return
        audio.setStreamVolume(stream, value, 0)
    }

    override fun ringMode(): Int = audio.ringerMode

    override fun setRingMode(value: Int) {
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        check(manager.isNotificationPolicyAccessGranted) { "需要勿扰访问权限才能安全切换静音／震动" }
        val previousFilter = manager.currentInterruptionFilter
        audio.ringerMode = value
        // Older platforms can couple ringer changes to the global filter. Preserve the user's
        // effective filter rather than setting ALL when restoring the volume mode.
        if (Build.VERSION.SDK_INT < 35 && manager.currentInterruptionFilter != previousFilter)
            manager.setInterruptionFilter(previousFilter)
    }

    override fun pause() {
        check(context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)) { "暂停播放需要通知访问权限" }
        val manager = requireNotNull(context.getSystemService(MediaSessionManager::class.java))
        manager.getActiveSessions(ComponentName(context, GuardNotificationListener::class.java)).forEach { controller ->
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controller.transportControls.pause()
        }
    }

    override fun brightness(): Int = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
    }.getOrDefault(-1)

    override fun maximumBrightness(): Int = 255

    override fun brightnessMode(): Int = Settings.System.getInt(context.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS_MODE, -1)

    override fun setBrightnessMode(value: Int) {
        require(value == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL || value == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        if (Settings.System.canWrite(context)) {
            check(Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, value)) { "自动亮度模式写入失败" }
        } else check(rootMode && GuardRootBridge.setBrightnessMode(context, value)) { "自动亮度模式需要修改系统设置权限或 Root" }
    }

    override fun setBrightness(value: Int) {
        val target = value.coerceIn(0, maximumBrightness())
        if (Settings.System.canWrite(context)) {
            check(Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)) { "亮度写入失败" }
            return
        }
        if (rootMode && GuardRootBridge.setBrightness(context, target)) return
        check(false) { "修改亮度需要“修改系统设置”权限或 Root" }
    }

    override fun takeAudioFocus() {
        // Focus must survive backend re-creation: one request per process, released explicitly.
        val request = FocusHolder.request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
            .also { FocusHolder.request = it }
        FocusHolder.granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandonAudioFocus() {
        FocusHolder.request?.let { audio.abandonAudioFocusRequest(it) }
        FocusHolder.request = null
        FocusHolder.granted = false
    }

    override fun capabilities() = CapabilityReport(listOf(
        Capability("媒体音量", !audio.isVolumeFixed, if (audio.isVolumeFixed) "系统固定音量" else "已提供音量接口；实际执行后回读"),
        Capability("铃声／通知", requireNotNull(context.getSystemService(NotificationManager::class.java)).isNotificationPolicyAccessGranted,
            "使用系统铃声模式，通知通常联动；图书馆模板关闭铃声震动，不控制第三方自行触发的触觉反馈"),
        Capability("暂停播放", context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context), "仅控制提供媒体会话的播放器，不保证拦截播放开头"),
        Capability("音频焦点抢占", true, "保护期间持续持有媒体焦点，其他应用获得焦点时会被本应用打断"),
        Capability("亮度控制", Settings.System.canWrite(context) || (rootMode && GuardRootBridge.isAuthorized(context)),
            if (Settings.System.canWrite(context)) "已授予修改系统设置权限" else "需要“修改系统设置”权限，或开启 Root 模式"),
        Capability("精确触发", Build.VERSION.SDK_INT < 31 || requireNotNull(context.getSystemService(AlarmManager::class.java)).canScheduleExactAlarms(), "未授权时采用非精确闹钟，可能延迟"),
        Capability("保护通知", NotificationManagerCompat.from(context).areNotificationsEnabled(), "建议允许通知，以便随时查看状态与放行"),
        Capability("Root 模式", rootMode && GuardRootBridge.isAuthorized(context),
            if (GuardRootBridge.isAuthorized(context)) "su 已授权，音量和亮度使用系统直写" else "未授权；可在设备页激活"),
        Capability("Shizuku", EnhancedAudioBridge.authorized(), "可选增强接口；不依赖 Root"),
        Capability("音量锁定", true, "自动纠正 + 可选 Root 直写；系统回调仍有毫秒级延迟")
    ))
}

/** 进程级焦点持有者：后端每次核对都会重建，但焦点只申请、释放各一次。 */
internal object FocusHolder {
    @Volatile var request: AudioFocusRequest? = null
    @Volatile var granted: Boolean = false
}

internal fun currentOutput(context: Context, document: GuardDocument): RouteEvidence {
    val devices = requireNotNull(context.getSystemService(AudioManager::class.java)).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val privateConnected = devices.any { it.type !in setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) }
    // The common disconnect case must not wait for a privileged-service rebind.
    if (!privateConnected) return RouteEvidence("speaker", false, false, "手机扬声器（无可确认的可信输出）")
    if (document.enhanced && EnhancedAudioBridge.authorized()) {
        runCatching { EnhancedAudioBridge.route(context, document) }.getOrNull()?.let { return it }
    }
    return RouteEvidence("unknown", false, false, "检测到外接设备，无法确认全部媒体路由，继续限制外放")
}
