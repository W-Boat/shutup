package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.*
import android.content.*
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.*
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xiaomanjun.sleepdownschedule.CourseScheduleApp
import com.xiaomanjun.sleepdownschedule.R
import com.xiaomanjun.sleepdownschedule.data.guard.*
import com.xiaomanjun.sleepdownschedule.domain.guard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

internal data class GuardStatus(
    val title: String = "未生效", val reasons: List<String> = emptyList(),
    val route: String = "输出状态待检查", val next: Long? = null,
    val errors: List<String> = emptyList(), val updated: Long = 0, val serviceRunning: Boolean = false,
    val windows: List<ProtectionWindow> = emptyList()
)

internal object GuardEngine {
    val status = MutableStateFlow(GuardStatus())
    private val mutex = Mutex()
    private var windows: List<ProtectionWindow> = emptyList()
    private var lastAudit = ""
    @Volatile var running = false
    private var lastRefresh = 0L

    suspend fun reconcile(context: Context, refresh: Boolean = false): GuardStatus = mutex.withLock {
        val store = GuardStore(context)
        try {
            val document = store.document.first()
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            if (refresh || now - lastRefresh > 6 * 60 * 60_000L || now < lastRefresh) {
                val lessons = (context.applicationContext as CourseScheduleApp).repository.guardLessons(document, now, zone)
                windows = RuleCompiler.compile(document, now, zone, lessons)
                lastRefresh = now
            }
            val route = currentOutput(context, document)
            val resolved = PolicyResolver.resolve(document, windows, now, route)
            val errors = mutableListOf<String>()
            if (resolved.policy != null && route.identity == "unknown") errors += "无法确认音频路由：保持媒体限制，无法安全自动恢复此输出的旧音量"
            if (document.enhanced && !EnhancedAudioBridge.authorized()) errors += "Shizuku 不可用，已降级普通控制"
            val app = context.applicationContext as CourseScheduleApp
            document.rules.filter { it.enabled && it.trigger == Trigger.COURSES }.mapNotNull { it.scheduleId }.distinct().forEach { id ->
                if (runCatching { LocalDate.parse(app.repository.scheduleSnapshot(id).config.termStartDate) }.isFailure)
                    errors += "课表 $id 缺少有效开学日期，该课表尚未自动保护"
            }
            val backend = AndroidAudioBackend(context, document.enhanced)
            AudioController(backend).reconcile(store.runtime(), resolved, route, store::saveRuntime) { errors += it }
            val exact = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            if (!exact && document.rules.any { it.enabled }) errors += "未授予精确闹钟权限，时段切换可能延迟"
            val title = when {
                errors.isNotEmpty() -> "能力受限"
                resolved.policy == null -> "未生效"
                resolved.trustedRelease -> "可信耳机放行"
                else -> "正在保护"
            }
            val next = minOf(resolved.nextChange ?: Long.MAX_VALUE,
                Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            schedule(context, next)
            val result = GuardStatus(title, resolved.reasons, route.description, next, errors.distinct(), now, running, windows)
            status.value = result
            val summary = "$title｜${resolved.reasons.joinToString()}｜${errors.distinct().joinToString()}"
            if (summary != lastAudit) {
                val dao = GuardDatabase.get(context).dao()
                dao.insert(GuardEvent(time = now, message = summary)); dao.trimEvents()
                lastAudit = summary
            }
            result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            status.value.copy(title = "能力受限", errors = listOf(e.message ?: "规则读取／执行失败"), updated = System.currentTimeMillis(), serviceRunning = running)
                .also { status.value = it }
        }
    }

    suspend fun command(context: Context, action: String) {
        val now = System.currentTimeMillis()
        val store = GuardStore(context)
        store.update { doc -> when (action) {
            "protect" -> {
                require(doc.selectedTemplateId != null) { "请先在首页选择固定模板" }
                doc.copy(manualUntil = now + 60 * 60_000L, bypassUntil = 0, skipUntil = 0)
            }
            "bypass" -> doc.copy(bypassUntil = now + 5 * 60_000L)
            "end" -> doc.copy(manualUntil = 0, skipUntil = PolicyResolver.endOfCurrentSession(windows, now, doc.manualUntil))
            else -> doc
        } }
    }

    private fun schedule(context: Context, time: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, 7312, Intent(context, GuardReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent)
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent)
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
    private var refreshNeeded = true
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("snow_guard", "声音保护状态", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
        startForeground(7311, notification(GuardEngine.status.value))
        GuardEngine.running = true
        audio = getSystemService(AudioManager::class.java)
        audio.registerAudioDeviceCallback(devices, handler)
        audio.registerAudioPlaybackCallback(playback, handler)
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        scope.launch {
            val app = application as CourseScheduleApp
            app.repository.ensureDefaults()
            combine(GuardStore(this@GuardService).document, app.repository.allSchedulesState) { _, _ -> Unit }
                .catch { e -> GuardEngine.status.value = GuardEngine.status.value.copy(title = "能力受限", errors = listOf(e.message ?: "规则读取失败")) }
                .collect { refreshNeeded = true; signal.send(true) }
        }
        scope.launch {
            for (refresh in signal) {
                val result = GuardEngine.reconcile(this@GuardService, refresh || refreshNeeded)
                refreshNeeded = false
                manager.notify(7311, notification(result))
            }
        }
        scope.launch {
            // Watchdog is active only inside protection windows; complements OEM-missing volume callbacks.
            while (isActive) {
                delay(if (GuardEngine.status.value.reasons.isNotEmpty()) 1000 else 60_000)
                signal.send(false)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            try { intent?.action?.let { GuardEngine.command(this@GuardService, it) } }
            catch (e: Exception) { GuardEngine.status.value = GuardEngine.status.value.copy(errors = listOf(e.message ?: "操作失败")) }
            refreshNeeded = true; signal.send(true)
        }
        return START_STICKY
    }
    private fun notification(status: GuardStatus): Notification {
        val open = PendingIntent.getActivity(this, 7313, Intent(this, SnowLeopardActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, "snow_guard").setSmallIcon(R.drawable.ic_snow_guard)
            .setContentTitle("雪豹闭嘴 · ${status.title}").setContentText(status.errors.firstOrNull() ?: status.reasons.joinToString().ifBlank { "等待保护时段" })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        listOf("protect" to "立即保护", "bypass" to "放行 5 分钟", "end" to "结束本次").forEachIndexed { index, pair ->
            builder.addAction(0, pair.second, PendingIntent.getService(this, 7320 + index,
                Intent(this, GuardService::class.java).setAction(pair.first), PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        GuardEngine.running = false
        GuardEngine.status.value = GuardEngine.status.value.copy(serviceRunning = false)
        audio.unregisterAudioDeviceCallback(devices); audio.unregisterAudioPlaybackCallback(playback)
        contentResolver.unregisterContentObserver(observer)
        scope.cancel(); super.onDestroy()
    }
}

class GuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        (context.applicationContext as CourseScheduleApp).applicationScope.launch(Dispatchers.IO) {
            try {
                withTimeout(8_000) {
                    GuardEngine.reconcile(context, true)
                    val doc = GuardStore(context).document.first()
                    if (doc.rules.any { it.enabled } || doc.manualUntil > System.currentTimeMillis()) GuardEngine.start(context)
                }
            } finally { pending.finish() }
        }
    }
}

class GuardNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { if (GuardEngine.running) GuardEngine.start(this) }
}

class GuardTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { label = "雪豹闭嘴"; state = if (GuardEngine.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() }
    }
    override fun onClick() {
        val intent = Intent(this, SnowLeopardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("guard_action", "protect")
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 7330, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else { @Suppress("DEPRECATION") startActivityAndCollapse(intent) }
    }
}
