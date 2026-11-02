# 项目代理约定

## 文案与文档

- 产品页面保留操作名称、参数、实际状态、必要错误和确认控件。功能机制、实现细节与安全边界的长说明放入 docs，不作为常驻页面提示。
- 根 README 面向产品用户，介绍适用人群、解决的问题、已实现的特色、安装与开始使用、二次开发入口和许可证。详细操作、内部机制、测试结果和历史版本说明分别归入 docs。
- CHANGELOG.md 是面向用户的版本变化来源。自 0.9.3 条目起，版本正文只使用逐条变化列表；开发中的变化先记入 Unreleased。正式版汇总相对上一正式版的变化，包含期间预发行的改动并去重。
- GitHub Release 正文从 CHANGELOG 对应版本直接提取，只列用户可感知的新增、改善和修复。测试数量、证书、包体、模型文件、实现细节与保证性描述放入内部交付记录。
- 实现与交付记录保存在 docs/releases，验证证据保存在 docs/testing 或 docs/evidence。README、CHANGELOG 与 Release 不重复维护三份版本摘要。
- 整理文档时合并重复内容并核对当前代码；历史记录保持其版本语境。使用与开发入口统一维护在 docs/README.md。
- 发布说明格式与操作约定见 [发布说明规范](docs/release-notes.md)。

## 开发维护

- 界面英文与中文资源同步修改；core 输出结构化错误和进度，界面层负责翻译，诊断字段保持英文。
- 修改 web 源码后重建对应的 app 终端与预览资产，不只修改生成文件。
- 构建与模块入口见 [开发指南](docs/development-zh.md)。测试和发布沿用 [固定流程](docs/testing/webview-and-release-runbook.md) 与 [改动验收流程](docs/testing/ui-redesign-change-and-release-routine.md)，分别记录桌面浏览器、Android WebView、真实 SSH 夹具和真机的结果；跳过不计为通过。
- test-audio 是独立实验项目，不作为主应用构建或发布的依赖。
- 调试日志、生成图集、APK、模型下载、凭据和本机环境不纳入普通源码提交；沿用 .gitignore。
