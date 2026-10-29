# 1.2.7 修复版更新识别

## 用户目标与基线

用户反馈昨晚发布的 `1.2.7-r1` 无法被 `1.2.7` 的应用内更新发现，要求解决；随后明确选择兼容补发版本名 `1.2.7.1`，并要求撤下 r1 发布。

本次继续处理 r1 发布反馈，复用 `codex/nexio-glass-parity`。开始时工作树干净，HEAD 为 `add3ed64`，比远端 #78 head `746b0ac2` 多一笔既有应用内日志补全提交。GitHub 插件核对 #78 仍为面向 `main` 的 Draft，main 仍为 `a320f6a8`。本次不处理 main 回流。

## 根因与修复

- GitHub 与实际更新来源 Gitee 都存在 `v1.2.7-r1` 正式 Release，`prerelease=false`，主应用 APK 附件可由元数据找到。
- `v1.2.7` 的 `AppUpdate.kt` 仅比较数字版本、预发布阶段与阶段序号，忽略 `-rN`；`1.2.7` 与 `1.2.7-r1` 比较相等。提高 Android versionCode 不会修复该字符串比较。
- `ParsedVersion` 新增修订号，比较顺序为数字版本 → 阶段 → 阶段序号 → 修订号。保留 Beta、正式版、历史 exp 过滤与忽略 `+metadata` 的规则。修复及测试保存在独立本地提交 `5d123ac4`。
- 按用户选择准备 `1.2.7.1` / 35；第四段数字可由已发布旧客户端识别，同时让已装 r1 的用户正常升级。没有把旧 APK 改名充当新包。
- 增加应用内日志、日期及 `release-notes/1.2.7.1.md`，保留 r1 完整修复记录。课程组件未改动。

## 验证

- `:app:testGithubDebugUnitTest --tests com.xiaomanjun.sleepdownschedule.feature.update.GiteeAppUpdaterTest --console=plain --no-parallel --max-workers=2` 通过，3m01s；12 项、0 失败/错误/跳过。覆盖修订数字排序、正式/Beta 顺序、阶段序号优先于修订号、忽略构建元数据、两种渠道与列表正反顺序。
- 从 `v1.2.7:app/src/main/java/com/xiaomanjun/sleepdownschedule/feature/update/AppUpdate.kt` 原样提取比较函数与 `ParsedVersion`，用本机缓存的 Kotlin 编译器运行 8 项兼容场景：旧比较器对 `1.2.7 → 1.2.7-r1` 返回 false，对 `1.2.7`、`1.2.7-r1`、`1.2.7_beta5 → 1.2.7.1` 均返回 true；安装 1.2.7.1 后不会继续提示自身或回退到旧版，后续 1.2.8 仍可更新。脚本在忽略目录 `tmp/Verify-LegacyUpdate.ps1`，生成源与 class 也只在 tmp。
- 完整 `:app:assembleGithubRelease --console=plain --no-parallel --max-workers=2` 通过，3m58s；159 项中 32 执行、127 复用，包含 R8、资源压缩、lintVital 和正式签名，未使用跳过资源压缩参数。
- `aapt2 dump badging` 与构建元数据均为 `com.xiaomanjun.sleepdownschedule` / `1.2.7.1` / `35`；`apksigner verify --verbose --print-certs` 通过，v2 签名有效，证书 SHA-256 `0f2b50dfb7e10c6f5981ab966f1f4ebd76af81a7ad9ba31663284eaa88c0181a`，与历史发布一致。
- 本机交付副本 `tmp/review-apks/SleepDown-1.2.7.1-20261020.apk`，7,591,259 字节，SHA-256 `3c920a97b319567bd367a482ccd493f55d59aa8c3dfe54b5dcd49c153b83fc8f`；与构建输出摘要相同。与 r1 包大小恰好相同，摘要及 Manifest 不同，确为新构建。
- 未安装、未启动应用、未执行自动 UI 或性能测试。

## 下架与发布状态

- 用户明确授权撤下双平台 r1，保留 Git 标签和源码历史。GitHub 插件提供发布 GET，但没有撤回 Release 的写入工具；浏览器管理页面两次操作超时，尚未发生远端变更。
- 项目要求 GitHub 元数据使用插件，已向用户请求例外：使用昨晚的本机发布连接将 GitHub r1 转为草稿；Gitee 无草稿时移除该 Release。待明确答复后执行，脚本仅针对 `v1.2.7-r1`，先保存公开元数据，再核对远端状态与匿名发布列表，不打印或保存凭据。
- `1.2.7.1` 已完成构建与签名核验，尚未发布、推送或创建标签；待用户确认双平台补发。
