package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.Context
import com.xiaomanjun.sleepdownschedule.domain.guard.*

internal object EnhancedAudioBridge {
    fun authorized() = false
    fun requestPermission(): Unit = error("此发行渠道不包含 Shizuku，请使用 GitHub 版本")
    fun setMediaVolume(context: Context, index: Int): Unit = error("增强接口不可用")
    fun route(context: Context, document: GuardDocument): RouteEvidence = error("增强接口不可用")
}
