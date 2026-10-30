# Root 恢复与锁机体验

用户授权：独立 Root 守护默认关闭；另提供 Root 启用/恢复锁机无障碍。二者分别主动开启，旧 JSON 新字段均默认 false。固定项目签名保持不变，所有测试/出包只走 Actions。

## 恢复语义

- 普通模式保留 START_STICKY、任务划掉唤醒闹钟、开机/闹钟接收器。仅锁机也参与恢复判断；重新打开任一页面时，进程前台事件会自动启动已启用的保护，补齐原先只进课表页面不会重启服务的缺口。
- 对比 b4d6251→f89d437，任务恢复条件是扩展，没有删除恢复逻辑。未连接用户手机，不能把其旧版可恢复/新版不可恢复的现象归因于某一厂商策略。最近进程退出记录用于区分内存回收、崩溃、ANR 与用户/系统停止，不把 USER_REQUESTED 当成强停的唯一证据。
- 真正 force-stop 与划掉任务不同；普通应用在 stopped 状态下不能保证恢复。Android 15 还会取消 PendingIntent。来源：[官方 stopped 状态说明](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state)。

## 独立 Root 守护

- 开关在上课锁机页，默认关闭。单次 su、电池白名单都不等于独立守护。开启时验证 uid=0，启动 nohup shell，并用独立 su 回读 PID/命令行确认存活；这仍不代表真机强停恢复已经验证。
- 脚本随版本内置，写入 root 所有且权限 0700 的 /data/local/tmp/shutup-root-UID。拒绝符号链接目录与非 root 所有目录；不下载脚本、不接收 AI 或用户命令，不修改 init/Magisk 开机文件。
- 应用写入私有心跳及可撤销标记。守护每 15 秒检查，心跳超过 45 秒后尝试 unstop 和启动本应用 GuardService；命令限时，连续恢复失败最多三次，成功刷新心跳后清除次数。仅操作本应用及当前 Android 用户，不拉起其他包。
- 关闭先删除应用所有的标记，即使 su 撤销也能让循环退出；Root 可用时只终止经 PID 和脚本路径共同验证的本守护。卸载/清数据移除标记也会使循环结束。守护可能仍在等待一次限时命令或 sleep，停止不是硬实时。
- Root 所用的 unstop/FGS 命令取决于系统支持与 SELinux。源码依据：[PackageManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)、[ActivityManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerShellCommand.java)。没有移植开机模块，重启后的恢复依赖正常开机入口/重新打开应用及 Root 授权，不能声称永久保活。

## Root 无障碍

- 使用系统 secure settings 为当前用户追加准确组件，保留已有服务、避免重复，再回读设置。当前用户来自 AOSP UID 分段，不调用隐藏 getIdentifier API。[UserHandle 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/UserHandle.java)。
- 区分设置已写入与 onServiceConnected 实际回调；等待最多 5 秒，未连接时提示在系统权限页检查。开启自动恢复后，仅前台恢复/保护服务配置载入时尝试恢复，未连接不伪报成功。关闭开关只停止自动恢复，不擅自撤销系统已有授权。

## 锁机测试

- 入口为“测试锁机 · 60 秒”，无需课表/声音模板/开启自动锁机。会请求真实系统屏幕固定，可提前结束，精确到期收起页面；不消耗退出次数、不写正式课程退出抑制键、不覆盖仍在进行的正式锁机会话。
- 测试标记独立持久化并带默认 false；页面、通知、无障碍仍使用同一会话 key 和到期校验。测试不启用声音规则。
- CI 增加锁机测试状态/配额、共同服务生命周期以及实际 shell 脚本隔离测试；不在本机运行。真实 Root 授权、系统强停、厂商清理、撤销 Root、重启与真实无障碍绑定必须真机验收。
