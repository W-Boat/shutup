package com.xiaomanjun.sleepdownschedule.feature.guard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kyant.backdrop.Backdrop
import com.xiaomanjun.sleepdownschedule.AppState
import com.xiaomanjun.sleepdownschedule.core.ui.chrome.DockScrollPadding
import com.xiaomanjun.sleepdownschedule.core.ui.designsystem.SleepDownSecondaryPageList
import com.xiaomanjun.sleepdownschedule.core.ui.scaffold.detailContentTopPadding
import com.xiaomanjun.sleepdownschedule.core.ui.scaffold.settingsVisualConfig
import com.xiaomanjun.sleepdownschedule.core.ui.settings.GlassPreferenceSection
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsGroup
import com.xiaomanjun.sleepdownschedule.core.ui.settings.SettingsValueRow
import com.xiaomanjun.sleepdownschedule.data.guard.GuardStore
import com.xiaomanjun.sleepdownschedule.domain.guard.GuardDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 可信蓝牙设备页：保留并重做原「可信设备」功能，全部使用 Miuix 设置行。 */
@Composable
fun GuardDevicesScreen(state: AppState, backdrop: Backdrop?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GuardStore(context) }
    val visualConfig = settingsVisualConfig(state.config)
    var document by remember { mutableStateOf<GuardDocument?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { GuardStore(context).document.collect { document = it } }
            .onFailure { message = it.message }
    }
    SleepDownSecondaryPageList(contentTopPadding = detailContentTopPadding(), contentBottomPadding = DockScrollPadding) {
        item(key = "guard-devices") {
            val doc = document
            if (doc == null) {
                GlassPreferenceSection("可信设备") {
                    SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                        SettingsValueRow("读取中", "正在载入可信设备…", compact = true)
                    }
                }
            } else {
                TrustedDevicesSection(
                    document = doc,
                    backdrop = backdrop,
                    visualConfig = visualConfig,
                    onChange = { transform ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { GuardStore(context).update(transform) }
                                GuardEngine.start(context)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                message = e.message ?: "保存失败"
                            }
                        }
                    },
                    onError = { message = it }
                )
                message?.let {
                    GlassPreferenceSection("提示") {
                        SettingsGroup(backdrop = backdrop, config = visualConfig, modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                            SettingsValueRow("最近一次操作", it, compact = true)
                        }
                    }
                }
            }
        }
    }
}
