# ShutUp 课程表 · 声音保护融合改造（2026-10-30）

## 目标

1. 后台可靠：切到后台、划掉最近任务后保护仍生效。
2. 完全融合：声音保护不再是一个独立的 Material3 Activity，而是课程表主应用里的一个页面。
3. 音频：抢占音频焦点、硬音量、亮度上限、保护前提醒、常驻通知、1px 悬浮窗保活、实况窗／超级岛。
4. Root 模式激活。
5. 通知栏磁贴与快捷入口（含亮度）。
6. 交互重构、安装包体积优化。

## 结构变化

| 位置 | 变化 |
| --- | --- |
| `AndroidManifest.xml` | 全部 `activity-alias` 目标改为 `.MainActivity`；新增 `WRITE_SETTINGS`、`SYSTEM_ALERT_WINDOW`；注册 `GuardBrightnessTileService`；`USER_UNLOCKED` 触发保护自启 |
| `feature/guard/SnowLeopardActivity.kt` | 变为兼容转发壳（旧通知、磁贴、快捷方式、勿扰规则组件名仍然可用），直接进入 `SettingsDetailActivity` 的 `SettingsPage.Guard` |
| `feature/guard/GuardSettingsScreen.kt` | 新的唯一保护页面，使用公共 `GlassPreferenceSection`／`SettingsGroup`／`SettingsToggleRow` 等 Miuix 设置组件 |
| `feature/settings/SettingsPage` | 新增 `Guard`，两个设置宿主（手机／平板详情）都接入；设置根页新增「声音保护」入口 |

## 后台与保活

- `GuardService` 用 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` 显式启动，`START_STICKY`。
- `onTaskRemoved` 触发 2 秒后的精确闹钟重启；`onDestroy` 不再连带结束保护。
- 心跳在保护窗口内 5 秒、空闲 120 秒；只在窗口内做高频核对。
- Root 可用时自动 `dumpsys deviceidle whitelist +pkg`（每小时一次）。
- 可选 1×1 透明、不可触的 `TYPE_APPLICATION_OVERLAY` 窗口，降低 OEM 冻结概率。
- 「从最近任务隐藏」钩子扩展到设置详情宿主，保护页同样生效。

## 音频／亮度

- `AndroidAudioBackend.takeAudioFocus()` 在保护期间持有 `AUDIOFOCUS_GAIN`（USAGE_MEDIA），保护结束或可信耳机放行时释放。
- 音量写入顺序：Shizuku → Root（`cmd media_session volume`）→ 公共 `AudioManager`；每次写入后回读确认。
- 亮度：`Settings.System.SCREEN_BRIGHTNESS`（需 `WRITE_SETTINGS`），否则 Root `settings put system screen_brightness`；写入前记录原值，退出保护后恢复。
- 模板新增 `brightnessCap`、`lockBrightness`、`focusTakeover`，旧 JSON 文档因 `@Serializable` 默认值保持兼容。
- 保护前提醒：`preReminderSeconds`（0—120 秒），引擎在窗口开始前安排一次唤醒，弹 Toast + 高优先级通知（可「取消 5 分钟」／「立即开始」）。
- 常驻通知：状态 + 已生效动作 + 结束时间 + 三个动作；`setShortCriticalText`（API 36）与 `android.requestPromotedOngoing` 让系统实况区展示；小米机型走 `XiaomiSuperIsland.decorateGuard` 焦点通知。

## Root 模式

`GuardRootBridge`：`su -c "id -u"` 授权判定并持久化，提供音量大、亮度、电池白名单、`appops WRITE_SETTINGS` 直写；未授权时全部自动降级为普通控制，并在状态里提示。

## 磁贴与快捷

- `GuardTileService`：点按直接开／关保护（不再只是拉起界面），长按进入保护页，副标题显示当前模板。
- `GuardBrightnessTileService`：点按在 30/255 与 200/255 之间切换亮度。
- `GuardShortcuts`：动态快捷方式（立即保护／结束保护／放行 5 分钟）。

## 安装包体积

- `android.enableR8.fullMode=true`。
- `androidResources.localeFilters = ["zh", "en"]`：剔除 AndroidX／Miuix 自带的多语言资源。
- `dependenciesInfo` 关闭、`packaging.resources.excludes` 清理 META-INF 等。
- CI 增加 `apk-size.txt` 产物，随 Artifact 一起下载。

## 验证

- 编译与单测只在 GitHub Actions 运行（本地不跑 Gradle）：`snow-ci.yml` 的 `testGithubDebugUnitTest`、`lintGithubDebug assembleGithubDebug`、`minifyGithubReleaseWithR8`。
- 仪器测试 `GuardPersistenceInstrumentedTest` 已改为校验 `SettingsPage.Guard` 页可启动。
