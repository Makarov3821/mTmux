# Third-party components

项目自有代码采用 GPL-3.0-only，见根目录 LICENSE。以下第三方代码、资源及构建工具保留原许可证；本项目的许可声明不替换它们的版权与许可文本。

mtmux 0.6.7-dev 采用下列组件；版本固定在 Gradle 构建文件及 `web/package-lock.json` 中。表格是直接依赖的初步审查，不代表正式发布前的完整法律或供应链审计。

| 组件 | 版本 | 许可证 | 用途 / 来源 |
| --- | --- | --- | --- |
| mwiede/JSch | 2.28.7 | BSD-3-Clause；内含 jBCrypt ISC、JZlib BSD 文本 | SSH、认证、PTY；https://github.com/mwiede/jsch |
| Bouncy Castle bcprov-jdk18on | 1.83 | MIT 风格许可证 | JSch 在 Android 上的现代密码算法实现；https://github.com/bcgit/bc-java |
| xterm.js | 5.5.0 | MIT | 终端渲染与 VT 解析；https://github.com/xtermjs/xterm.js |
| xterm addon-fit | 0.10.0 | MIT | 根据 WebView 容器计算行列 |
| xterm headless | 5.5.0 | MIT | 仅 Node 测试，不进入 APK |
| playwright-core | 1.56.1 | Apache-2.0 | 仅桌面浏览器测试，不进入 APK |
| AndroidX Activity / Compose / WebKit | 见 app/build.gradle.kts 与依赖树 | Apache-2.0 | 原生 UI、生命周期与本地资产加载 |
| Kotlin / kotlinx.coroutines | 2.2.21 / 1.10.2 | Apache-2.0 | 语言运行时与后台 IO |
| JUnit 4 / kotlin-test | 4.13.2 / 2.2.21 | EPL-1.0 / Apache-2.0 | 仅测试，不进入 APK |
| Gradle wrapper | 8.13 | Apache-2.0 | 构建工具，不进入 APK |

许可证文本随本地资产保留：

- `app/src/main/assets/LICENSE-jsch.txt`
- `app/src/main/assets/LICENSE-jzlib.txt`
- `app/src/main/assets/LICENSE-jbcrypt.txt`
- `app/src/main/assets/LICENSE-bouncycastle.html`
- `app/src/main/assets/terminal/LICENSE-xterm.txt`
- `app/src/main/assets/terminal/LICENSE-addon-fit.txt`

构建还会引入 AndroidX/Kotlin 的传递依赖。发布前应导出实际 `debugRuntimeClasspath` / `releaseRuntimeClasspath` 依赖树，补齐所有分发组件的 NOTICE、开源声明入口和最终许可证审查。不得根据此直接依赖清单宣称“全部上架要求已满足”。

维护状态与取舍：JSch 维护分支的 Maven 发布元数据已核对；xterm.js 选择固定且已有公共 API 文档的版本进行兼容验证，不宣称它是最新版本。WebView 路径避免自行实现 VT，但增加 JS 桥接、IME 和触摸行为的验证成本。P0 没有引入远程网页、分析或广告 SDK。

## Orca (MIT)

`core/.../AgentTitle.kt` adapts OpenCode title-envelope recognition and status-signal ideas from
[stablyai/orca](https://github.com/stablyai/orca), commit `122b8c25d7c16f76e395bf9a65887d7c4bc5003b`,
`src/shared/opencode-terminal-title.ts`, `agent-title-core.ts`, and `agent-title-status.ts`.
Copyright (c) 2026 Lovecast Inc. Full MIT notice is bundled in `app/src/main/assets/LICENSE-orca.txt`.
No Orca runtime, remote plugins, or hook configuration is installed.
