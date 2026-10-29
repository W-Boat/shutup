package com.xiaomanjun.sleepdownschedule.feature.guard

import android.media.AudioDeviceInfo
import android.media.AudioPlaybackConfiguration
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.exitProcess

/** Runs as Shizuku's shell/root identity. Unsupported hidden APIs fail closed. */
class GuardAudioUserService : IGuardAudioService.Stub() {
    private val audio by lazy {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "audio") as IBinder
        Class.forName("android.media.IAudioService\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }
    override fun playbackRoutes(): String {
        val configurations = audio.javaClass.getMethod("getActivePlaybackConfigurations").invoke(audio) as List<*>
        val result = JSONArray()
        // A single-device API on old Android cannot rule out simultaneous speaker playback.
        val routeMethod = AudioPlaybackConfiguration::class.java.getMethod("getAudioDeviceInfos")
        for (raw in configurations) {
            val config = raw as AudioPlaybackConfiguration
            val state = config.javaClass.getMethod("getPlayerState").invoke(config) as Int
            if (state != 2) continue
            val devices = routeMethod.invoke(config) as List<*>
            check(devices.isNotEmpty()) { "活动播放没有可验证的输出设备" }
            devices.forEach { item ->
                val device = item as AudioDeviceInfo
                result.put(JSONObject().put("type", device.type).put("address", device.address))
            }
        }
        return result.toString()
    }
    override fun setMediaVolume(index: Int) {
        val max = audio.javaClass.getMethod("getStreamMaxVolume", Int::class.javaPrimitiveType).invoke(audio, 3) as Int
        require(index in 0..max)
        val setter = audio.javaClass.getMethod("setStreamVolume", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        setter.invoke(audio, 3, index, 0, "com.android.shell")
    }
    override fun destroy() = exitProcess(0)
}
