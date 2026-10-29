# Android 11 基线的 Lint 修复

来源：GitHub Actions run `40895076153` 完整 XML 报告，20 errors / 530 warnings。保持 minSdk 30，未创建 Lint baseline，也未全局关闭 NewApi。

- ColorOS 渐进模糊：Android RenderEffect 只能在 API 31+ 使用；Android 11 返回普通视觉路径，通过 SDK 检查和独立 API 31 实现隔离。
- 旧实时通知勿扰入口：现有调用端已检查 API 35，为被调函数补充 RequiresApi 契约；纠正新 Condition 构造器的 source 与 flags 参数顺序。
- 今明课程 Widget：RemoteViews 直接使用真实布局，避免将 values 中的布局 alias 误识别为 RemoteViews 节点。
- 快捷磁贴：API 34+ 使用 PendingIntent；Android 11—13 保留必要的旧接口，只在兼容 helper 上豁免该弃用检查。
- Custom Tabs：已核对 Google Maven 发布的 AndroidX Browser 1.8.0 源码，rounded-corners 参数被库错误标注为 decoration 类型。保留正确 TOP 常量，只对该调用所在 helper 限定 WrongConstant 豁免。
- Compose：拓扑校验从返回 Unit 的 remember 改为提交后的 SideEffect；两处不读取约束的 BoxWithConstraints 改为 Box。
- 修复动画几何与远端配置中的两处缩进错误，不改变条件逻辑。

530 条警告属于单独的维护清单；本轮先修复阻止构建的全部错误。结果以修复提交对应的 Actions 报告为准，不能用此前单测通过代替完整构建成功。

同时补上新 Shizuku 用户服务及 AIDL 的 R8 保留规则：该服务由外部进程反射创建，不能让 Release 压缩移除其入口。CI 新增无签名的 Release shrinker 检查；正式 APK 签名仍只读取用户的仓库 Secrets。
