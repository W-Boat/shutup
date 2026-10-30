package com.xiaomanjun.sleepdownschedule.feature.guard

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.xiaomanjun.sleepdownschedule.CourseScheduleApp
import com.xiaomanjun.sleepdownschedule.R
import com.xiaomanjun.sleepdownschedule.data.guard.*
import com.xiaomanjun.sleepdownschedule.domain.guard.*
import com.xiaomanjun.sleepdownschedule.feature.experimental.XiaomiSuperIsland
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*
import java.util.concurrent.atomic.AtomicBoolean

internal data class GuardStatus(
    val title: String = "未生效",
    val reasons: List<String> = emptyList(),
    val route: String = "输出状态待检查",
    val next: Long? = null,
    val errors: List<String> = emptyList(),
    val updated: Long = 0,
    val serviceRunning: Boolean = false,
    val windows: List<ProtectionWindow> = emptyList(),
    /** Latest end of the currently active protection; drives the Live Activity timer. */
    val endAt: Long? = null,
    /** What this reconcile actually changed, used as the Live Activity / island text. */
    val applied: String = "",
    val templateName: String = "",
    val overlayActive: Boolean = false
)

internal object GuardEngine {
    val status = MutableStateFlow(GuardStatus())
    private val mutex = Mutex()
    private var windows: List<ProtectionWindow> = emptyList()
    private var lastAudit = ""
    @Volatile var running = false
    private var lastRefresh = 0L
    private var scheduleWarnings: List<String> = emptyList()
    private var scheduledSignature = ""
    private var lastWhitelist = 0L
    private val reminded = mutableSetOf<String>()

    suspend fun reconcile(context: Context, refresh: Boolean = false): GuardStatus = mutex.withLock {
        val store = GuardStore(context)
        try {
            val document = store.document.first()
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            if (refresh || now - lastRefresh > 6 * 60 * 60_000L || now < lastRefresh) {
                val inputs = (context.applicationContext as CourseScheduleApp).repository.guardLessons(document, now, zone)
                scheduleWarnings = inputs.warnings
                windows = RuleCompiler.compile(document, now, zone, inputs.lessons)
                lastRefresh = now
            }
            val route = currentOutput(context, document)
            val resolved = PolicyResolver.resolve(document, windows, now, route)
            val errors = scheduleWarnings.toMutableList()
            if (resolved.policy != null && route.identity == "unknown") errors += "无法确认音频路由：保持媒体限制，无法安全自动恢复此输出的旧音量"
            if (document.enhanced && !EnhancedAudioBridge.authorized()) errors += "Shizuku 不可用，已降级普通控制"
            if (document.rootMode && !GuardRootBridge.isAuthorized(context)) errors += "Root 模式已开启但 su 未授权，已降级为普通控制"
            val backend = AndroidAudioBackend(context, document.enhanced, document.rootMode)
            val runtimeBefore = store.runtime()
            AudioController(backend).reconcile(runtimeBefore, resolved, route, store::saveRuntime) { errors += it }
            val runtimeAfter = store.runtime()
            val exact = Build.VERSION.SDK_INT < 31 || requireNotNull(context.getSystemService(AlarmManager::class.java)).canScheduleExactAlarms()
            if (!exact && document.rules.any { it.enabled }) errors += "未授予精确闹钟权限，时段切换可能延迟"
            val activeWindows = windows.filter { it.contains(now) }
            val templateName = document.templates.firstOrNull { it.id == document.selectedTemplateId }?.name
                ?: activeWindows.firstNotNullOfOrNull { w -> document.templates.firstOrNull { it.id == w.templateId }?.name }
                ?: "未选择模板"
            val title = when {
                errors.isNotEmpty() -> "能力受限"
                resolved.policy == null -> "未生效"
                resolved.trustedRelease -> "可信耳机放行"
                else -> "正在保护"
            }
            val boundary = minOf(
                resolved.nextChange ?: Long.MAX_VALUE,
                Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            )
            val reminderAt = reminderTarget(context, document, now)
            schedule(context, minOf(boundary, reminderAt))
            maybeRemind(context, document, now)
            GuardLockController.tick(context, document, windows, now)
            GuardCare.tick(context, document, windows, now)
            val endAt = maxOf(document.manualUntil, activeWindows.maxOfOrNull { it.end } ?: 0L).takeIf { it > now }
            val result = GuardStatus(
                title = title,
                reasons = resolved.reasons,
                route = route.description,
                next = if (boundary == Long.MAX_VALUE) null else boundary,
                errors = errors.distinct(),
                updated = now,
                serviceRunning = running,
                windows = windows,
                endAt = endAt,
                applied = describeApplied(resolved, runtimeBefore, runtimeAfter, backend),
                templateName = templateName
            )
            status.value = result
            val summary = "$title｜${resolved.reasons.joinToString()}｜${errors.distinct().joinToString()}"
            if (summary != lastAudit) {
                val dao = GuardDatabase.get(context).dao()
                dao.insert(GuardEvent(time = now, message = summary, title = title,
                    reason = resolved.reasons.joinToString("、"), error = errors.distinct().joinToString("\n"))); dao.trimEvents()
                lastAudit = summary
            }
            if (document.rootMode && GuardRootBridge.isAuthorized(context) && now - lastWhitelist > 3_600_000L) {
                lastWhitelist = now
                GuardRootBridge.whitelistBattery(context)
            }
            refreshTiles(context)
            result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            status.value.copy(title = "能力受限", errors = listOf(e.message ?: "规则读取／执行失败"), updated = System.currentTimeMillis(), serviceRunning = running)
                .also { status.value = it }
        }
    }

    private fun reminderTarget(context: Context, document: GuardDocument, now: Long): Long {
        val candidates = mutableListOf<Long>()
        val lead = document.preReminderSeconds * 1000L
        if (lead > 0) windows.asSequence().map { it.start - lead }.filter { it > now }.minOrNull()?.let { candidates += it }
        val careLead = document.care.safeLeadMinutes * 60_000L
        if (document.lock.enabled) {
            windows.flatMap { listOf(it.classStart, it.classEnd,
                minOf(it.classEnd, it.classStart + document.lock.safeGraceSeconds * 1000L)) }
                .filter { it > now }.minOrNull()?.let { candidates += it }
        }
        if (document.care.enabled && careLead > 0) {
            windows.filter { it.isCourse }.map { it.classStart - careLead }.filter { it > now }.minOrNull()?.let { candidates += it }
        }
        if (document.care.enabled && document.care.duringClass) {
            val every = document.care.safeEveryMinutes * 60_000L
            windows.filter { it.isCourse && it.classEnd > now }.forEach { window ->
                val bucket = maxOf(1L, (now - window.classStart) / every + 1)
                val at = window.classStart + bucket * every
                if (at > now && at < window.classEnd) candidates += at
            }
        }
        if (document.care.enabled) candidates += GuardCare.nextRetryAt(context, now)
        return candidates.minOrNull() ?: Long.MAX_VALUE
    }

    private fun maybeRemind(context: Context, document: GuardDocument, now: Long) {
        val lead = document.preReminderSeconds * 1000L
        if (lead <= 0) return
        val upcoming = windows.firstOrNull { it.start > now && it.start - now <= lead } ?: return
        val key = "${upcoming.ruleId}|${upcoming.start}"
        if (!reminded.add(key)) return
        if (reminded.size > 64) reminded.clear()
        val template = document.templates.firstOrNull { it.id == upcoming.templateId }
        GuardReminder.show(context, upcoming, ((upcoming.start - now) / 1000L).toInt(), template?.name ?: "固定模板")
    }

    private fun describeApplied(
        resolved: ResolvedPolicy,
        before: GuardRuntime,
        after: GuardRuntime,
        backend: AudioBackend
    ): String {
        val policy = resolved.policy ?: return if (before != after) "已恢复音量与亮度" else "当前无保护"
        val parts = buildList {
            policy.mediaCap?.let { cap -> add("媒体 ≤$cap%（现在 ${backend.volume(3)}/${backend.maximum(3)}）") }
            policy.brightnessCap?.let { cap -> add("亮度 ≤$cap%") }
            when (policy.ring) {
                RingPolicy.SILENT -> add("铃声静音")
                RingPolicy.VIBRATE -> add("震动")
                RingPolicy.KEEP -> Unit
            }
            if (policy.alarms == AlarmPolicy.MUTE) add("闹钟静音")
            if (policy.pausePlayback) add("尝试暂停播放")
            if (policy.focusTakeover) add(if (FocusHolder.granted) "音频焦点已获准 · 静音保活" else "音频焦点待获准")
        }
        return parts.joinToString(" · ").ifBlank { "保护中" }
    }

    suspend fun command(context: Context, action: String) {
        val now = System.currentTimeMillis()
        val store = GuardStore(context)
        GuardReminder.cancel(context)
        if (action == "preview") {
            val document = store.document.first()
            val template = document.templates.firstOrNull { it.id == document.selectedTemplateId }
            GuardReminder.show(
                context,
                ProtectionWindow("preview", "预览提醒", template?.id.orEmpty(), now, now + 60_000L),
                document.preReminderSeconds.coerceAtLeast(3),
                template?.name ?: "固定模板"
            )
            return
        }
        store.update { doc -> when (action) {
            "protect" -> {
                require(doc.selectedTemplateId != null) { "请先在保护页选择固定模板" }
                doc.copy(manualUntil = now + 60 * 60_000L, bypassUntil = 0, skipUntil = 0)
            }
            "bypass" -> doc.copy(bypassUntil = now + 5 * 60_000L)
            "end" -> doc.copy(manualUntil = 0, skipUntil = PolicyResolver.endOfCurrentSession(windows, now, doc.manualUntil))
            else -> doc
        } }
    }

    /** Single-tap entry point for the tile, shortcuts and the launcher intent. */
    suspend fun toggle(context: Context) {
        val document = GuardStore(context).document.first()
        val protecting = status.value.title == "正在保护" || status.value.title == "可信耳机放行" ||
            document.manualUntil > System.currentTimeMillis() ||
            windows.any { it.contains(System.currentTimeMillis()) }
        command(context, if (protecting) "end" else "protect")
        start(context)
        reconcile(context, true)
    }

    fun refreshTiles(context: Context) {
        runCatching { TileService.requestListeningState(context, ComponentName(context, GuardTileService::class.java)) }
        runCatching { TileService.requestListeningState(context, ComponentName(context, GuardBrightnessTileService::class.java)) }
    }

    fun tileActive(context: Context): Boolean = running && status.value.title == "正在保护"

    private fun schedule(context: Context, time: Long) {
        val alarm = requireNotNull(context.getSystemService(AlarmManager::class.java))
        val exact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        val signature = "$time|$exact"
        if (signature == scheduledSignature) return
        val intent = PendingIntent.getBroadcast(context, 7312, Intent(context, GuardReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (time == Long.MAX_VALUE) alarm.cancel(intent)
        else if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent)
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent)
        scheduledSignature = signature
    }

    /**
     * OEMs kill background FGS aggressively; a short exact wake-up keeps the guard alive after a
     * task swipe or a process reset without polling forever.
     */
    fun scheduleRestart(context: Context, delayMillis: Long = 2_000L) {
        runCatching {
            val alarm = requireNotNull(context.getSystemService(AlarmManager::class.java))
            val pending = PendingIntent.getBroadcast(context, 7314, Intent(context, GuardReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val at = System.currentTimeMillis() + delayMillis
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms())
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun start(context: Context) {
        try { ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java)) }
        catch (e: Exception) { status.value = status.value.copy(title = "能力受限", serviceRunning = false,
            errors = listOf("系统不允许后台启动保护服务，请打开应用：${e.javaClass.simpleName}")) }
    }
}

class GuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signal = Channel<Boolean>(Channel.CONFLATED)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private val refreshNeeded = AtomicBoolean(true)
    private val overlay by lazy { GuardOverlayKeepAlive(this) }
    @Volatile private var document: GuardDocument? = null
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { signal.trySend(false) }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { signal.trySend(false) }
    }
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) { signal.trySend(false) }
    }
    private val observer = object : ContentObserver(handler) { override fun onChange(selfChange: Boolean) { signal.trySend(false) } }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        GuardReminder.ensureChannel(this)
        GuardCare.ensureChannel(this)
        GuardShortcuts.publish(this)
        startAsForeground(notification(GuardEngine.status.value))
        GuardEngine.running = true
        audio = requireNotNull(getSystemService(AudioManager::class.java))
        audio.registerAudioDeviceCallback(devices, handler)
        audio.registerAudioPlaybackCallback(playback, handler)
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        scope.launch {
            val app = application as CourseScheduleApp
            app.repository.ensureDefaults()
            combine(GuardStore(this@GuardService).document, app.repository.allSchedulesState) { doc, _ -> doc }
                .catch { e -> GuardEngine.status.value = GuardEngine.status.value.copy(title = "能力受限", errors = listOf(e.message ?: "规则读取失败")) }
                .collect { doc ->
                    document = doc
                    syncOverlay(doc)
                    refreshNeeded.set(true); signal.send(true)
                }
        }
        scope.launch {
            for (refresh in signal) {
                val needsRefresh = refreshNeeded.getAndSet(false)
                val result = GuardEngine.reconcile(this@GuardService, refresh || needsRefresh)
                val doc = document
                syncOverlay(doc)
                val notification = notification(result)
                if (canPostNotifications()) {
                    getSystemService(NotificationManager::class.java)?.notify(FOREGROUND_ID, notification)
                }
                // Idle means: no enabled rules, no manual session, no active window.
                if (doc != null && doc.rules.none { it.enabled } && doc.manualUntil <= System.currentTimeMillis() &&
                    result.endAt == null && result.title == "未生效") {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
        scope.launch {
            // Event-driven reconcile plus a cheap heartbeat; the heartbeat only shortens while a
            // protection window is open, so idle cost stays near zero.
            while (isActive) {
                val active = GuardEngine.status.value.title == "正在保护"
                delay(if (active) 5_000 else 120_000)
                signal.send(false)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            try {
                when (val action = intent?.action) {
                    null, "reconcile" -> Unit
                    "toggle" -> GuardEngine.toggle(this@GuardService)
                    "lock_start", "lock_skip" -> GuardLockController.decide(this@GuardService,
                        intent?.action == "lock_start", intent?.getStringExtra(GuardLockController.WindowKey).orEmpty(),
                        intent?.getLongExtra(GuardLockController.ExpiresAt, 0) ?: 0)
                    else -> GuardEngine.command(this@GuardService, action)
                }
            } catch (e: Exception) {
                GuardEngine.status.value = GuardEngine.status.value.copy(errors = listOf(e.message ?: "操作失败"))
            }
            refreshNeeded.set(true); signal.send(true)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swiping the task away must not end the protection: wake ourselves again in 2s.
        val doc = document
        val busy = doc != null && (doc.rules.any { it.enabled } || doc.manualUntil > System.currentTimeMillis())
        if (busy) GuardEngine.scheduleRestart(this)
        GuardEngine.refreshTiles(this)
    }

    private fun syncOverlay(doc: GuardDocument?) {
        val wanted = doc?.keepAliveOverlay == true &&
            (GuardEngine.status.value.title == "正在保护" || GuardEngine.status.value.title == "可信耳机放行")
        // WindowManager.addView/removeView 需要创建窗口所在线程的消息循环：统一切主线程。
        handler.post {
            if (wanted && !overlay.isShowing()) overlay.show()
            if (!wanted && overlay.isShowing()) overlay.hide()
            GuardEngine.status.value = GuardEngine.status.value.copy(overlayActive = overlay.isShowing())
        }
    }

    private fun createChannels() {
        val manager = requireNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "声音保护状态", NotificationManager.IMPORTANCE_LOW).apply {
            description = "常驻状态、快捷操作与实况窗"
            setSound(null, null); enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            if (manager.isNotificationPolicyAccessGranted) setBypassDnd(true)
        })
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    private fun notification(status: GuardStatus): Notification {
        val open = PendingIntent.getActivity(this, 7313, Intent(this, SnowLeopardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = status.errors.firstOrNull()
            ?: status.applied.ifBlank { status.reasons.joinToString().ifBlank { "等待保护时段" } }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_snow_guard)
            .setContentTitle("ShutUp 课程表 · ${status.title}")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(
                buildString {
                    append(status.reasons.joinToString("、").ifBlank { "尚未进入保护时段" })
                    if (status.applied.isNotBlank()) append("\n").append(status.applied)
                    append("\n").append(status.route)
                    status.endAt?.let { append("\n结束：").append(formatClock(it)) }
                    status.errors.forEach { append("\n⚠ ").append(it) }
                }
            ))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setColor(android.graphics.Color.parseColor("#126B5B"))
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (canPostNotifications()) {
            if (Build.VERSION.SDK_INT >= 36) builder.setShortCriticalText(status.applied.ifBlank { status.title })
            runCatching { builder.extras.putBoolean("android.requestPromotedOngoing", true) }
        }
        fun action(key: String, title: String, request: Int) = builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_snow_guard), title,
                PendingIntent.getService(this, request, Intent(this, GuardService::class.java).setAction(key),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            ).build()
        )
        action("protect", "立即保护", 7320)
        action("bypass", "放行 5 分钟", 7321)
        action("end", "结束本次", 7322)
        val built = builder.build()
        val doc = document
        if (doc?.liveActivity == true) {
            runCatching {
                XiaomiSuperIsland.decorateGuard(
                    context = this,
                    notification = built,
                    title = if (status.title == "正在保护") "ShutUp 保护中" else "ShutUp·${status.title}",
                    content = status.applied.ifBlank { text },
                    shortText = status.templateName.ifBlank { "静音保护" },
                    timerAtMillis = status.endAt,
                    actionTitle = "结束保护",
                    actionKey = XiaomiSuperIsland.GuardActionKey
                )
            }
        }
        return built
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AndroidAudioBackend(this).abandonAudioFocus()
        GuardEngine.running = false
        GuardEngine.status.value = GuardEngine.status.value.copy(serviceRunning = false, overlayActive = false)
        overlay.hide()
        runCatching { audio.unregisterAudioDeviceCallback(devices) }
        runCatching { audio.unregisterAudioPlaybackCallback(playback) }
        runCatching { contentResolver.unregisterContentObserver(observer) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "shutup_guard"
        const val FOREGROUND_ID = 7311
    }
}

private fun formatClock(time: Long): String =
    Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

class GuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        (context.applicationContext as CourseScheduleApp).applicationScope.launch(Dispatchers.IO) {
            try {
                withTimeout(8_000) {
                    GuardEngine.reconcile(context, true)
                    val doc = GuardStore(context).document.first()
                    if (doc.rules.any { it.enabled } || doc.manualUntil > System.currentTimeMillis() ||
                        GuardEngine.status.value.endAt != null) GuardEngine.start(context)
                }
            } finally { pending.finish() }
        }
    }
}

class GuardNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { if (GuardEngine.running) GuardEngine.start(this) }
}

/** Tile 1: start/stop protection without opening the app. */
class GuardTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            label = "ShutUp 保护"
            state = if (GuardEngine.tileActive(this@GuardTileService)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = GuardEngine.status.value.templateName.ifBlank { "点按开始静音" }
            updateTile()
        }
    }

    override fun onClick() {
        val context = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                GuardEngine.toggle(context)
                GuardEngine.refreshTiles(context)
            } catch (_: Exception) {
            }
        }
    }
}

/** Tile 2: dim the screen to the template brightness cap for an instant focus mode. */
class GuardBrightnessTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            label = "ShutUp 亮度"
            state = Tile.STATE_INACTIVE
            subtitle = "点按压低屏幕亮度"
            updateTile()
        }
    }

    override fun onClick() {
        val context = applicationContext
        val current = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        }.getOrDefault(-1)
        val target = if (current > 90) 30 else 200
        val applied = if (Settings.System.canWrite(context)) {
            runCatching {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
                true
            }.getOrDefault(false)
        } else {
            GuardRootBridge.setBrightness(context, target)
        }
        qsTile?.apply {
            state = if (applied && target < 90) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (applied) "亮度 $target/255" else "需要“修改系统设置”或 Root"
            updateTile()
        }
    }
}
