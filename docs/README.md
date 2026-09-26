# 文档目录

版本变更摘要统一在根目录 [CHANGELOG.md](../CHANGELOG.md)；产品范围与路线在 [plan.md](../plan.md)。

| 目录 | 内容 |
| --- | --- |
| [releases/](releases/) | 每个版本的详细交付记录（`<版本>.md`）：原因、实现、验证结果、截图与 APK SHA-256 |
| [design/](design/) | 设计与计划：[UI 方案](design/ui-plan.md)、[下一阶段评估](design/next-phase-plan.md)（语音、双语、云同步）、[界面草图](design/mockups/) |
| [testing/](testing/) | [P0 技术验证报告](testing/p0-report.md)（含 0.0.2、0.0.3）、[真机验证清单](testing/device-checklist.md) |
| [evidence/](evidence/) | 测试截图与原始结果，按版本分子目录（名称沿用历史命名，如 `p1-011` = 0.1.1、`ui-062` = 0.6.2） |

新增版本时：在 `releases/` 写详细记录，在 CHANGELOG 顶部加摘要并链接，截图放入 `evidence/<版本>/`。
