# 图集校验记录

日期：2026-10-29。业务代码快照：`c6a672b`。

- 94 张 PNG：主界面 26、服务器配置 13、设置 6、终端与输入 25、语音 14、验证与确认分支 10。
- 所有图片均为 360 × 800，文件、尺寸和 SHA-256 与清单一致；终端分类没有完全重复的图片。
- 浏览器离线检查通过：94 张图片均可解码，分类、类型筛选、关键词搜索、放大、方向键翻图均正常，无 JavaScript 错误。
- Markdown 图片链接无缺失；旧的 116 张截图已删除，`docs` 中的程序位图统一集中在本目录。SVG 图标、HTML 设计源文件、历史日志和测试数据保留。
- `git diff --check` 通过。业务源码与 `c6a672b` 一致，新增代码仅用于 debug / Android 测试和文档生成。

## 原生采集验证

Android 15 AOSP ATD 专用软件模拟器，无 KVM。仅写入 `catalog-*` 演示配置。

- `UiTerminalCatalogTest`：最终采集 22 张，通过，`OK (1 test)`，374.529 秒。
- `UiCatalogTest` 的 `extras` 分组：最终采集 10 张，通过，`OK (1 test)`，161.399 秒。
- 其余 62 张来自先前非终端采集步骤。该早期整轮运行在后续终端 WebView 步骤失败，因此不将其记为整轮测试通过；已生成的非终端图片逐项收集并校验。终端随后独立重采并通过。
- 构建 `voiceDebug`、`voiceDebugAndroidTest`、`standardDebug` 和 `lintStandardDebug` 通过。

初次采集遇到软件 WebView 不稳定、输入法动画等待和关闭动画未结束等问题。最终终端图使用明确标注的离线 xterm 正文，原生弹窗等待展开/关闭后再采集。不能据此推断真实 SSH、Android WebView 或语音识别端到端测试通过。

运行 `python3 scripts/build_ui_catalog.py <采集目录>` 可重建索引并验证 PNG 元数据；在 `web/` 运行 `node check-ui-catalog.mjs` 可复查全部图片及浏览交互。覆盖与边界见 [coverage.md](coverage.md)。
