# Third-party components

项目自有代码采用 GPL-3.0-only，见根目录 LICENSE。以下第三方代码、资源及构建工具保留原许可证；本项目的许可声明不替换它们的版权与许可文本。

mtmux 0.8.0-dev 采用下列组件；版本固定在 Gradle 构建文件及 `web/package-lock.json` 中。表格是直接依赖的初步审查，不代表正式发布前的完整法律或供应链审计。Whisper/ggml 与 base 权重只进入 voice APK；standard APK 不包含。独立 `:voice-benchmark` 复用同一固定源码，但不进入正式 App。

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
| whisper.cpp / ggml | 1.9.5，commit `d1be6fde…` | MIT | voice APK 的完全离线语音推理及独立 Android V0 基准；standard APK 不包含 |
| OpenAI Whisper multilingual 量化权重 | HF revision `5359861…` | MIT | voice 默认内置 base q8_0，构建可选 q5_1；tiny/q5/q8 供独立基准；standard APK 不包含 |
| ONNX Runtime Android | 1.23.2 | MIT；传递组件见随包 ThirdPartyNotices | voice 的 CPU 标点恢复推理；standard APK 不包含 |
| DAMO / iic CT-Transformer 标点权重 | sherpa-onnx 2024-04-12 INT8 转换，固定归档与模型 SHA-256 | Apache-2.0 | 中英混合文本的离线标点恢复；来源 https://modelscope.cn/models/iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch 与 https://github.com/k2-fsa/sherpa-onnx/releases/tag/punctuation-models |
| SRF 中文标点 INT8 权重 / 词表 | 模型 HF revision `055df91…`；词表源码 `e4814e7…`，固定 SHA-256 | MIT（上游声明） | 仅轻量对照构建内置；https://github.com/ximeiorg/srf-punctuation 与 https://huggingface.co/rkingzhong/srf-punctuation |

许可证文本随本地资产保留：

- `app/src/main/assets/LICENSE-jsch.txt`
- `app/src/main/assets/LICENSE-jzlib.txt`
- `app/src/main/assets/LICENSE-jbcrypt.txt`
- `app/src/main/assets/LICENSE-bouncycastle.html`
- `app/src/main/assets/terminal/LICENSE-xterm.txt`
- `app/src/main/assets/terminal/LICENSE-addon-fit.txt`
- `voice-benchmark/src/main/assets/licenses/LICENSE-whisper.cpp.txt`（仅 benchmark APK）
- `voice-benchmark/src/main/assets/licenses/LICENSE-openai-whisper.txt`（benchmark APK）
- `voice/src/main/assets/licenses/LICENSE-whisper.cpp.txt`（voice APK）
- `voice/src/main/assets/licenses/LICENSE-openai-whisper.txt`（voice APK）
- `voice/src/main/assets/licenses/LICENSE-onnxruntime.txt` / `NOTICE-onnxruntime.txt`（voice APK）
- `voice/src/main/assets/licenses/LICENSE-ct-punctuation.txt` / `NOTICE-ct-punctuation.txt`（voice APK）
- `voice/src/main/assets/licenses/LICENSE-srf-punctuation.txt`（轻量对照模型声明及许可，voice APK）

构建还会引入 AndroidX/Kotlin 的传递依赖。发布前应导出实际 `debugRuntimeClasspath` / `releaseRuntimeClasspath` 依赖树，补齐所有分发组件的 NOTICE、开源声明入口和最终许可证审查。不得根据此直接依赖清单宣称“全部上架要求已满足”。

维护状态与取舍：JSch 维护分支的 Maven 发布元数据已核对；xterm.js 选择固定且已有公共 API 文档的版本进行兼容验证，不宣称它是最新版本。WebView 路径避免自行实现 VT，但增加 JS 桥接、IME 和触摸行为的验证成本。P0 没有引入远程网页、分析或广告 SDK。

## Orca (MIT)

`core/.../AgentTitle.kt` adapts OpenCode title-envelope recognition and status-signal ideas from
[stablyai/orca](https://github.com/stablyai/orca), commit `122b8c25d7c16f76e395bf9a65887d7c4bc5003b`,
`src/shared/opencode-terminal-title.ts`, `agent-title-core.ts`, and `agent-title-status.ts`.
Copyright (c) 2026 Lovecast Inc. Full MIT notice is bundled in `app/src/main/assets/LICENSE-orca.txt`.
No Orca runtime, remote plugins, or hook configuration is installed.
