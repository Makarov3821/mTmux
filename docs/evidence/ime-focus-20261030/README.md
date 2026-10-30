# 输入连接恢复验证

这是未发布工作区的验证，不代表 v0.9.0 已包含修复。

- `baseline-android.txt`：旧开发调试安装（0.8.5/code35，修复前的 DirectTerminalInput 实现）的预期失败，两项回归均失败。真实 IME 初始连接成功，随后关闭连接再点键盘，45 秒内未重建。此报告不是新补丁失败。
- `fixed-android.txt`：同一 Android 15 软件模拟器，安装修复版 standardDebug 后的 7 项回归全部通过。包括真实 InputMethodManager、另一编辑窗口夺取/恢复焦点、旧 close 晚到和中文提交。
- `ime-events.txt`：固定测试的结构化 IME 事件，无输入正文、命令或服务器信息。反映连接被关闭后重建、旧回调丢弃和新连接恢复提交。
- `checks.json`：JVM、构建、lint、APK 哈希和验证范围。本机调试证书不能覆盖正式 Release 证书安装。

用户手机/第三方输入法仍需安装后复测；无法仅凭模拟器断定用户遇到的所有输入失效都来自同一路径。完整逐批 SSH/渲染时延日志尚未加入。
