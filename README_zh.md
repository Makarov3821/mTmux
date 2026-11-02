# mTmux

[English](README.md) · [简体中文](README_zh.md)

在手机上继续你的远端终端工作。

mTmux 是面向 SSH 与 tmux 工作流的 Android 客户端。离开电脑后，你可以在手机上接入远端会话，查看任务输出、回复终端 Agent，并阅读它生成的文档和图片。

[下载 APK](https://github.com/Makarov3821/mTmux/releases/latest) · [使用指南](docs/usage-zh.md) · [版本变化](CHANGELOG.md)

## 适合谁

- 在远端 Linux 服务器或开发机上使用 tmux，希望随时接着操作终端的开发者。
- 使用 Codex、OpenCode 等终端 Agent，需要在手机上查看进度、补充指令或阅读结果的人。
- 管理多台服务器，希望把常用会话集中在一个手机界面里的人。

## 主要特色

- **服务器与会话管理**：分组、搜索服务器，查看 tmux 会话和任务状态，直接进入已有会话或新建终端。
- **适合手机的终端操作**：直接键盘输入、触屏滚动、Ctrl／Shift／Alt 组合键、功能键和快捷回复。
- **文档与图片预览**：在终端中点击 Markdown、HTML 和常见图片路径即可查看，图片支持缩放和拖动。
- **离线语音输入**：voice 版支持中英文语音转写，识别结果可编辑后插入终端。
- **连接与环境配置**：支持密码、私钥和多级跳板，并提供 tmux Doctor 检查服务器环境。

## 安装与开始使用

从 [GitHub Releases](https://github.com/Makarov3821/mTmux/releases/latest) 下载 APK，按需要选择一种版本：

| 版本 | 适合的使用方式 |
| --- | --- |
| standard | 使用键盘和终端工具输入 |
| voice | 在 standard 功能上增加离线语音输入 |

手机需要 Android 8.0 或以上版本；服务器需要能通过 SSH 登录，建议使用 tmux 3.2a 或以上版本。

1. 添加服务器，填写地址、用户名和认证信息。
2. 保存时核对主机指纹，按需要运行 tmux Doctor。
3. 在首页选择 tmux 会话，或新建一个终端开始使用。

首次配置、跳板、语音和文件预览的详细步骤见 [使用指南](docs/usage-zh.md)。

## 二次开发

项目使用 Kotlin、Jetpack Compose 和 xterm.js，SSH 与 tmux 逻辑位于独立的 core 模块。

准备 JDK 17、Android SDK Platform 36 和 Build Tools 35.0.0，配置好 SDK 后可使用 Android Studio 打开项目，或在仓库根目录构建 standard 调试版：

```bash
./gradlew :app:assembleStandardDebug
```

模块结构、voice 构建、网页资产修改和测试入口见 [开发指南](docs/development-zh.md)。

## 文档与反馈

- [使用指南](docs/usage-zh.md)：连接服务器、操作终端、使用语音和文件预览。
- [文档目录](docs/README.md)：开发、维护和历史记录入口。
- [隐私与安全说明](docs/privacy-and-security.md)：凭据、指纹、缓存和截图设置。
- [问题反馈](https://github.com/Makarov3821/mTmux/issues)：请附上版本、复现步骤和实际表现；日志导出方法见使用指南。

## 许可证

项目自有代码采用 **GPL-3.0-only**，许可证正文见 [LICENSE](LICENSE)。第三方组件的版权与许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
