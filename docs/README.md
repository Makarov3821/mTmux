# 文档目录

版本变更摘要统一在根目录 [CHANGELOG.md](../CHANGELOG.md)；产品范围与路线在 [plan.md](../plan.md)。

| 目录 | 内容 |
| --- | --- |
| [releases/](releases/) | 每个版本的详细交付记录（`<版本>.md`）：原因、实现、验证结果、截图与 APK SHA-256 |
| [design/](design/) | 设计与计划：[UI 方案](design/ui-plan.md)、[本轮美化方案](design/ui-beautification-plan.md)、[双版本语音输入计划](design/voice-input-plan.md)、[按住说话状态图](design/voice-hold-preview.md)、[100 MB 模型预算](design/voice-model-budget.md)、[下一阶段评估](design/next-phase-plan.md)（双语、语音、云同步）、[界面草图](design/mockups/) |
| [testing/](testing/) | [P0 技术验证报告](testing/p0-report.md)（含 0.0.2、0.0.3）、[语音 V0 Android 基准](testing/voice-v0-report.md)、[连接性能与日志 v2](testing/connection-performance.md)、[真机验证清单](testing/device-checklist.md)、[UI 改版验证](testing/ui-refresh-report.md)、[终端组合键与循环翻页](testing/terminal-keyboard.md)、[按住说话与标点分析](testing/voice-hold-input.md)、[服务器管理](testing/server-management.md)、[终端直接输入](testing/terminal-direct-input.md) |
| [evidence/](evidence/) | 测试截图与原始结果，按版本分子目录（旧目录沿用历史命名，如 `p1-011` = 0.1.1、`ui-062` = 0.6.2；0.7.0 起直接用版本号） |

当前语音模型试验：[q8、轻量 SRF 与 CT-Transformer 的对照及 APK 体积](testing/voice-models-20261023.md)。

新增版本时：在 `releases/` 写详细记录，在 CHANGELOG 顶部加摘要并链接，截图放入 `evidence/<版本>/`。
