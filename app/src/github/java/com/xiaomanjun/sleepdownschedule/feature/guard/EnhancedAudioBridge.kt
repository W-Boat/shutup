package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.xiaomanjun.sleepdownschedule.domain.guard.*
import org.json.JSONArray
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object EnhancedAudioBridge {
    @Volatile private var service: IGuardAudioService? = null
    private val main = Handler(Looper.getMainLooper())
    fun authorized() = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun requestPermission() {
        check(Shizuku.pingBinder()) { "请先在 Shizuku 中启动服务" }
        Shizuku.requestPermission(7310)
    }
    @Synchronized private fun get(context: Context): IGuardAudioService {
        check(Looper.myLooper() != Looper.getMainLooper()) { "增强接口必须在后台线程调用" }
        check(authorized()) { "Shizuku 未运行或未授权" }
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = binder?.let(IGuardAudioService.Stub::asInterface); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null; latch.countDown() }
        }
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, GuardAudioUserService::class.java.name))
            .daemon(false).processNameSuffix("snow_audio").debuggable(false).version(1)
        main.post { runCatching { Shizuku.bindUserService(args, connection) }.onFailure { latch.countDown() } }
        try {
            check(latch.await(3, TimeUnit.SECONDS)) { "Shizuku 声音服务连接超时" }
            return checkNotNull(service?.takeIf { it.asBinder().pingBinder() }) { "增强服务不可用" }
        } finally { main.post { runCatching { Shizuku.unbindUserService(args, connection, false) } } }
    }
    fun setMediaVolume(context: Context, index: Int) = get(context).setMediaVolume(index)
    fun route(context: Context, document: GuardDocument): RouteEvidence {
        val routes = JSONArray(get(context).playbackRoutes())
        if (routes.length() == 0) return RouteEvidence("unknown", false, false, "暂无可验证的活动播放路由")
        val entries = (0 until routes.length()).map { routes.getJSONObject(it) }
        val identities = entries.map { "${it.getInt("type")}:${it.getString("address")}" }.distinct()
        val trusted = entries.all { device ->
            when (device.getInt("type")) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID ->
                    document.trustedDevices.any { it.address.equals(device.getString("address"), ignoreCase = true) && it.address.isNotBlank() }
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> document.trustWired
                AudioDeviceInfo.TYPE_USB_HEADSET -> document.trustUsb
                else -> false
            }
        }
        val speakerOnly = entries.all { it.getInt("type") == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        return RouteEvidence(if (speakerOnly) "speaker" else identities.sorted().joinToString("|"), trusted, trusted,
            if (trusted) "已核对全部活动播放的可信耳机输出" else "增强检测：存在未信任或扬声器输出")
    }
}
