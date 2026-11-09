# 开发指南

[English](development-en.md) · [简体中文](development-zh.md)

## 环境与模块

使用 JDK 17、Android SDK Platform 36、Build Tools 35.0.0 和仓库自带 Gradle Wrapper。可在 Android Studio 打开项目并配置 SDK，命令行构建可设置 `ANDROID_HOME` 后在根目录执行：

```bash
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:assembleStandardDebug
```

`local.properties` 是本机配置，已被 Git 忽略。首次构建需要下载 Gradle 与 Maven／Google 仓库依赖。

| 目录 | 职责 |
| --- | --- |
| `app/` | Compose 页面、输入、终端 WebView、预览与服务器配置 |
| `core/` | SSH、tmux 命令和解析、主机信任、连接复用与 SFTP |
| `web/` | xterm.js 桥接、文档渲染与浏览器测试源码 |
| `voice/` | X-ASR 语音模型安装、录音与推理接入 |
| `scripts/` | 固定资源下载、测试夹具、APK 校验及发布说明生成 |
| `test-audio/` | 独立语音模型对照实验，不参与主应用构建和发布 |

连接使用 JSch，终端渲染使用 xterm.js，Markdown 与静态 HTML 预览使用 marked 和 DOMPurify。依赖、资源版本及许可证见 [第三方声明](../THIRD_PARTY_NOTICES.md)。

## 构建两种版本

设置页的“安装来源”展示安装包的发布渠道，版本读取 `BuildConfig.VERSION_NAME`。默认渠道为 GitHub；Google Play 构建使用 `-Pmtmux.distributionChannel=play`，此参数适用于 standard 与 voice。

standard 调试版：

```bash
./gradlew :app:assembleStandardDebug
```

输出为 `app/build/outputs/apk/standard/debug/app-standard-debug.apk`。

voice 版先获取锁定的 X-ASR 模型和 Android 运行时，再构建：

```bash
python3 scripts/fetch_voice.py
./gradlew :app:assembleVoiceDebug
```

输出为 `app/build/outputs/apk/voice/debug/app-voice-debug.apk`。下载源、成员大小与 SHA-256 固定在 `voice/model.lock.json`、`voice/runtime.lock.json`；当前运行时使用预编译包，主应用无需本机编译语音 C++。

本机 debug 签名和正式版签名是不同的安装链。签名发布、覆盖升级和证书配置见 [固定发布流程](testing/webview-and-release-runbook.md#固定签名与-github-发布)。

## 修改终端或预览页面

需要 Node.js 22 或以上。终端与预览资产已随源码提交，修改 `web/` 后在仓库根目录重建：

```bash
npm --prefix web ci --ignore-scripts
npm --prefix web test
npm --prefix web run bundle
```

生成目录是 `app/src/main/assets/terminal/` 和 `app/src/main/assets/preview/`，与 web 源码一同提交。浏览器回归需要桌面 Chromium／Chrome，可通过 `CHROME_BIN` 指定：

```bash
npm --prefix web run test:browser
```

该命令在仓库根目录运行；桌面浏览器结果与 Android WebView 结果分别记录。

## 测试入口

```bash
./gradlew :core:test :app:testStandardDebugUnitTest :app:lintStandardDebug
python3 -m unittest discover -s scripts/tests
```

voice 相关修改另外执行 `:voice:testDebugUnitTest`、`:app:testVoiceDebugUnitTest` 与 `:app:lintVoiceDebug`。需要测试 voice 资产时先运行下载脚本。

真实 SSH／SFTP／PTY 集成测试使用隔离夹具：

```bash
python3 scripts/test_tmux.py
python3 scripts/test_ssh.py --probe-pty
```

需要本机 tmux、OpenSSH 服务端及密钥工具。普通 `:core:test` 中依赖夹具的项目可能跳过，验收要核对实际执行数量与 XML，不能只看 Gradle 成功。

Android 测试使用专用模拟器或设备，设置 `ANDROID_SERIAL` 后按 [WebView 测试流程](testing/webview-and-release-runbook.md) 和 [改动验收流程](testing/ui-redesign-change-and-release-routine.md) 执行。文档图集采集器单独运行，不计为功能回归。文件预览的最新专项记录见 [视口与缓存测试](testing/preview-viewport-cache.md)。

## 提交与发布

界面英文位于 `app/src/main/res/values/strings.xml`，中文位于 `values-zh/strings.xml`，两份同步维护。core 使用结构化错误和进度，界面层翻译，诊断字段保持英文。

提交按功能、修复和文档划分，附上与改动相符的验证结果。行为设计放入 `docs/design/`，实现和交付记录放入 `docs/releases/`，测试记录放入 `docs/testing/`。当前编辑规则见 [AGENT.md](../AGENT.md)。

开发中的用户变化记入 CHANGELOG 的 Unreleased，发版前整理对应版本条目。Release 正文由脚本提取，格式见 [发布说明规范](release-notes.md)。历史语音实验、旧构建命令及老版本结果保存在对应版本记录，不作为当前构建步骤。
