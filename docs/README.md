# 文档目录

## 使用产品

- 使用指南：[中文](usage-zh.md) · [English](usage-en.md)。安装、服务器与跳板、tmux Doctor、终端、语音、文件预览和问题反馈。
- [隐私与安全说明](privacy-and-security.md)：凭据、主机指纹、输入与缓存、日志和截图设置。
- [版本变化](../CHANGELOG.md)：面向用户的逐版本变化。

## 开发与维护

- 开发指南：[中文](development-zh.md) · [English](development-en.md)。环境、模块、两种 APK、网页资产与测试入口。
- [发布说明规范](release-notes.md)：CHANGELOG、Release 与内部交付记录的分工和生成方式。
- [WebView 测试与签名发布](testing/webview-and-release-runbook.md)：测试分层、排障和固定发布操作。
- [改动验收流程](testing/ui-redesign-change-and-release-routine.md)：按改动范围选择检查并核对证据。
- [设计目录](design/)：界面、连接、输入和预览的设计与实现计划。

## 记录与专项资料

- [版本交付记录](releases/)：每版原因、实现与交付范围。
- [测试记录](testing/) 与 [原始证据](evidence/)：保留对应版本和环境的结论。
- [预览加载与缓存](design/preview-viewport-cache.md)及其[验证记录](testing/preview-viewport-cache.md)。
- [连接性能与日志](testing/connection-performance.md)、[tmux Doctor](testing/tmux-doctor.md)、[输入恢复](testing/terminal-ime-recovery.md)。
- [当前语音引擎迁移](testing/voice-xasr.md)与独立 [Audio Lab](../test-audio/README.md) 历史实验。
- [图集采集说明](screenshots/README.md)：图集在本地生成并被 Git 忽略，不作为功能回归证据。

原 README 临时汇总页已拆入使用、开发和隐私说明。历史版本的操作与结果以相应版本记录为准；项目编辑约定见 [AGENT.md](../AGENT.md)。
