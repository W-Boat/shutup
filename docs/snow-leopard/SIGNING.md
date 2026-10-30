# 固定签名与后续升级

- 从本轮固定签名交付起，debug、slim 和正式 release 使用同一项目证书。版本构建只加载已有私钥，工作流不会生成新密钥。
- 证书 SHA-256：`18138020522ae6590f12a3fc7f48945853d2076419aba9b8e13ebb8d515242d7`。源码根目录 `signing-certificate.sha256` 为公开校验基准，不是私钥。
- 正常 push/手动构建必须配置：`SNOW_KEYSTORE_BASE64`、`SNOW_RELEASE_STORE_PASSWORD`、`SNOW_RELEASE_KEY_ALIAS`、`SNOW_RELEASE_KEY_PASSWORD`、`SNOW_SIGNING_CERT_SHA256`。密钥格式 PKCS12；缺配置或证书不符时拒绝出包。
- `setup-snow-signing` 先核对 keystore 证书与源码固定值；出包后 `verify-apk-signatures.sh` 使用 Android SDK apksigner 验证 APK 签名，并再次比较证书。证据随 APK artifact 的 `apk-signing.txt` 保存。
- PR 不接触项目密钥，只进行临时签名的编译验证，并且仅上传测试/Lint 报告，不分发其 APK。不要改成 pull_request_target 执行 PR 源码来获取签名权限。
- 私钥不进入源码、日志或普通 artifact；临时文件在 job 结束时移除。本机受限访问的备份目录位于仓库外，密码由当前 Windows 用户 DPAPI 保护。迁机前应另外妥善备份凭据；DPAPI 文件依赖当前用户/系统。
- 旧交付临时 debug 证书为 `0aa0021122d72a55f17f18e4d6466d486926180f4c417c818323c92f106d4acd`，与新项目证书不同。找不到旧私钥就不能伪造同签覆盖；不要直接卸载旧应用丢失数据，应先保留/导出课表与规则。
- slim 的应用 ID 为 `io.github.wboat.shutup`，debug 为 `io.github.wboat.shutup.debug`；二者数据分别保存。后续同一应用 ID 的升级还需要维持正确版本号与兼容迁移，签名一致不能代替这些要求。
