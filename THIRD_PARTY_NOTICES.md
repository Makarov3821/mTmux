# Third-party components

项目自有代码采用 GPL-3.0-only，见根目录 LICENSE。以下第三方代码、资源及构建工具保留原许可证；本项目的许可声明不替换它们的版权与许可文本。

当前主应用的直接依赖如下，版本固定在 Gradle、`voice/*.lock.json` 与 `web/package-lock.json`。X-ASR 与 sherpa 运行时只进入 voice APK；standard 不包含。独立 Audio Lab 的历史候选及许可见 `test-audio/`，不参与主应用发布。

| 组件 | 版本 | 许可证 | 用途 / 来源 |
| --- | --- | --- | --- |
| mwiede/JSch | 2.28.7 | BSD-3-Clause；内含 jBCrypt ISC、JZlib BSD 文本 | SSH、认证、PTY；https://github.com/mwiede/jsch |
| Bouncy Castle bcprov-jdk18on | 1.83 | MIT 风格许可证 | JSch 在 Android 上的现代密码算法实现；https://github.com/bcgit/bc-java |
| xterm.js | 5.5.0 | MIT | 终端渲染与 VT 解析；https://github.com/xtermjs/xterm.js |
| xterm addon-fit | 0.10.0 | MIT | 根据 WebView 容器计算行列 |
| xterm headless | 5.5.0 | MIT | 仅 Node 测试，不进入 APK |
| playwright-core | 1.56.1 | Apache-2.0 | 仅桌面浏览器测试，不进入 APK |
| AndroidX Activity / AppCompat / Compose / WebKit | 见 app/build.gradle.kts 与依赖树 | Apache-2.0 | 原生 UI、生命周期、应用内语言与本地资产加载 |
| Kotlin / kotlinx.coroutines | 2.2.21 / 1.10.2 | Apache-2.0 | 语言运行时与后台 IO |
| JUnit 4 / kotlin-test | 4.13.2 / 2.2.21 | EPL-1.0 / Apache-2.0 | 仅测试，不进入 APK |
| Gradle wrapper | 8.13 | Apache-2.0 | 构建工具，不进入 APK |
| X-ASR-zh-en INT8 权重 / 词表 | 480ms，2026-06-05，固定 SHA-256 | Apache-2.0 | 离线中英识别、原生标点与大小写；https://github.com/Gilgamesh-J/X-ASR 与 https://huggingface.co/GilgameshWind/X-ASR-zh-en |
| sherpa-onnx Android runtime / Kotlin API | v1.13.8，固定 SHA-256 | Apache-2.0 | X-ASR CPU 推理，静态链接 ONNX Runtime；https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8 |
| ONNX Runtime（sherpa 上游静态链接） | 随固定 sherpa v1.13.8 分发 | MIT；传递组件见随包 ThirdPartyNotices | 不再单独依赖 Maven onnxruntime-android / Java JNI；standard 不包含 |

许可证文本随本地资产保留：

- `app/src/main/assets/LICENSE-jsch.txt`
- `app/src/main/assets/LICENSE-jzlib.txt`
- `app/src/main/assets/LICENSE-jbcrypt.txt`
- `app/src/main/assets/LICENSE-bouncycastle.html`
- `app/src/main/assets/terminal/LICENSE-xterm.txt`
- `app/src/main/assets/terminal/LICENSE-addon-fit.txt`
- `voice/src/main/assets/licenses/LICENSE-sherpa-onnx.txt`（voice APK）
- `voice/src/main/assets/licenses/LICENSE-x-asr.txt` / `NOTICE-x-asr.txt`（voice APK）
- `voice/src/main/assets/licenses/LICENSE-onnxruntime.txt` / `NOTICE-onnxruntime.txt`（voice APK）

构建还会引入 AndroidX/Kotlin 的传递依赖。发布前应导出实际 `debugRuntimeClasspath` / `releaseRuntimeClasspath` 依赖树，补齐所有分发组件的 NOTICE、开源声明入口和最终许可证审查。不得根据此直接依赖清单宣称“全部上架要求已满足”。

维护状态与取舍：JSch 维护分支的 Maven 发布元数据已核对；xterm.js 选择固定且已有公共 API 文档的版本进行兼容验证，不宣称它是最新版本。WebView 路径避免自行实现 VT，但增加 JS 桥接、IME 和触摸行为的验证成本。P0 没有引入远程网页、分析或广告 SDK。

## Orca (MIT)

`core/.../AgentTitle.kt` adapts OpenCode title-envelope recognition and status-signal ideas from
[stablyai/orca](https://github.com/stablyai/orca), commit `122b8c25d7c16f76e395bf9a65887d7c4bc5003b`,
`src/shared/opencode-terminal-title.ts`, `agent-title-core.ts`, and `agent-title-status.ts`.
Copyright (c) 2026 Lovecast Inc. Full MIT notice is bundled in `app/src/main/assets/LICENSE-orca.txt`.
No Orca runtime, remote plugins, or hook configuration is installed.
