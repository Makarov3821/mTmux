# 0.9.3 完整自动化测试记录

本轮以 versionName `0.9.3` / versionCode 44 为发布候选，完整检查普通版与语音版。开始时基于 `ff92238` 后的工作区；结果完成后补充到本文。仅在必需检查完成并通过后推送正式标签。

## 范围与环境

- 专用 `emulator-5554` / `mtmux-test`，Android 15 / API 35 google_apis x86_64，Google WebView 124.0.6367.219。设备环境采集在 `.tools/diagnostics/release-093/env-before/`。
- standard 与 voice 全部功能测试类、`UiRedesignComponentTest`；voice 另含 `VoiceProductTest` 的真实中英文 X-ASR 推理、取消、旧模型迁移及录音资源释放。
- `UiCatalogTest` / `UiTerminalCatalogTest` 是显式 opt-in 的文档截图生成器，不计作功能回归，也不因排除它们宣称零跳过。
- JVM：core、voice 与两版 app；Python 资产/下载测试；真实宿主 SSH/SFTP/tmux 集成；Node 和实际打包页面的桌面 Chromium；两版 Debug/Release、测试 APK 编译、lint 与模型/runtime/权限/大小/对齐检查。
- 真实 SSH/tmux 与 Android 测试使用隔离 loopback sshd、临时密钥与专用 tmux socket；为允许 PTY 在沙箱外执行。不访问用户保存的服务器、不修改默认 tmux。
- 逐类执行 Android 测试，保存每类原始 console、XML、单项日志，并与预期方法清单核对。脚本 `DONE` 和退出码本身不作为通过证据；失败及重跑分别保留。
- 本机没有连接 ARM 实机；不将模拟器录音、输入法和布局验证称为真实麦克风质量或所有厂商输入法验收。没有改动独立 Audio Lab。

## 结果

待完整测试完成后记录各层级结果与必要的失败整改。
