# 双版本语音输入实施计划

日期：2026-10-02；模型选择复核：2026-10-07。基线：0.7.2-dev。本文件是实施计划，不代表语音已经交付；模型质量、Android 真机速度、峰值内存和最终 APK 体积须先实测。

## 1. 结论

发布两个同版本、同签名、同应用 ID（`dev.mtmux`）的 APK：

| 版本 | 定位 | 麦克风权限 | 模型/native runtime | 终端底栏 |
| --- | --- | --- | --- | --- |
| **standard** | 当前轻量版 | 不声明 `RECORD_AUDIO` | 不包含 | 工具 / 收起键盘 / 快捷回复 / 发送 |
| **voice** | 自带离线语音输入 | 仅主动录音时请求 | 候选为 `whisper.cpp` + multilingual base q5_1 | 工具 / 收起键盘 / **语音** / 快捷回复 / 发送 |

在用户可接受完整 voice APK 约 100 MB 的前提下，首选候选调整为 `whisper.cpp` 的 **base multilingual q5_1**，不是 `.en` 模型。模型文件为 59,707,625 bytes（约 56.9 MiB）；tiny q5_1 为 32,152,673 bytes（约 30.7 MiB）。当前 standard release 为 12,104,747 bytes，因此 base 模型加现有 App 为 71,812,372 bytes，尚留约 28.2 MB 给 arm64 native runtime、对齐和其他资源；很可能能控制在 100 MB 左右，但必须以最终签名 APK 为准，universal 多 ABI 包尤其不能提前承诺。

选择 base 的依据不是“模型越大绝对不会退步”，而是它在与本产品最相关的中文官方数据上有一致且值得换取约 27.6 MB 额外模型空间的提升：

| OpenAI 官方零样本数据 | tiny multilingual | base multilingual | base 改善 |
| --- | ---: | ---: | ---: |
| Common Voice 9 Chinese CER | 52.4 | 44.9 | 下降 7.5 点，约 14.3% 相对下降 |
| FLEURS Chinese CER | 40.5 | 34.1 | 下降 6.4 点，约 15.8% 相对下降 |

OpenAI 的整体缩放分析也显示，除英文 ASR 外，多语种 ASR、翻译和语言识别随模型增大在**汇总指标**上继续改善。但个别语言/数据集并不单调，例如 FLEURS Amharic 从 tiny 到 base 反而变差，也有持平项目；单条录音的幻觉、专有词和口音同样可能退步。因此本文把 base 定为默认工程候选，而不是宣称它在所有输入上一定更强。

交付顺序仍是：**先做独立 base/tiny q5_1 基准 → base 达到速度、内存和质量门槛 → 再接入产品双版本**。tiny 作为低内存/超体积时的回退对照，不在同一 voice APK 同时内置两套模型；若 base 最终签名 APK 超过预算，优先尝试 arm64 单 ABI、AAB ABI split 或模型按需下载，再决定是否退回 tiny。

## 2. 方案比较与取舍

| 方案 | 优点 | 主要问题 | 本项目结论 |
| --- | --- | --- | --- |
| Android `SpeechRecognizer` | APK 几乎不增大；可利用厂商优化模型 | API 31 才有显式 on-device recognizer；API 33 才能检查支持/触发模型下载；普通识别和 `EXTRA_PREFER_OFFLINE` 是否真离线由实现决定，设备差异大 | 保留为失败时的可选后备，不作为“隐私离线版”的唯一引擎 |
| whisper.cpp tiny multilingual q5_1 | 单模型覆盖广泛语言；完全本机；约 30.7 MiB | 中文官方数据弱于 base，技术词和中英混说质量未知 | 低内存/体积回退与基准对照，不作为 100 MB 预算下的默认 |
| **whisper.cpp base multilingual q5_1** | 参数量更高；中文官方两项 CER 比 tiny 相对下降约 14–16%；约 56.9 MiB | 需要 CMake/NDK/JNI；速度、峰值内存、量化后的中文保持程度须在 Android 实测 | **100 MB 预算下的首选候选；基准通过后随 voice APK 自带** |
| Vosk small | 流式成熟；小模型通常约 40–50 MB，约 300 MB runtime RAM；可做词表偏置 | 每种语言是独立模型；要覆盖多语种需装多份，不符合“一份轻量广谱模型” | 不选为默认引擎，可在未来做特定语言/命令模式实验 |
| sherpa-onnx | Android/Kotlin 支持完整、纯离线，模型家族丰富 | 框架本身不等于小模型；SenseVoice 单模型支持中/英/日/韩/粤，但已知 ONNX 模型约 239 MB；Moonshine v2 当前是分语言模型 | 不作为首版；若 Whisper 性能不合格，再单独基准其小模型 |
| 输入法语音 | App 零额外模型/权限，当前已可使用 | 入口和离线性取决于输入法 | 永久保留的最低成本回退 |

官方参考：[`SpeechRecognizer`](https://developer.android.com/reference/android/speech/SpeechRecognizer)、[`RecognizerIntent`](https://developer.android.com/reference/android/speech/RecognizerIntent)、[`whisper.cpp`](https://github.com/ggml-org/whisper.cpp)、[whisper.cpp 模型仓库](https://huggingface.co/ggerganov/whisper.cpp/tree/main)、[Vosk models](https://alphacephei.com/vosk/models)、[sherpa-onnx Android](https://k2-fsa.github.io/sherpa/onnx/android/index.html)、[SenseVoice](https://k2-fsa.github.io/sherpa/onnx/sense-voice/index.html)。

`whisper.cpp` 官方未量化表给出的参考为 tiny 75 MiB / 约 273 MB RAM、base 142 MiB / 约 388 MB RAM；量化会降低模型磁盘占用，但不能据此直接推导 Android 峰值内存。OpenAI 的官方相对速度（A100 上英文转写）为 tiny 约 10x、base 约 7x，只能说明 base 的计算代价更高，不能当作 Android 延迟：按吞吐量粗看 base 约为 tiny 的 70%，等量音频耗时可能约高 43%，但手机线程、NEON、温控和解码参数会改变结果。

量化准确率也不能只靠模型大小推断。一项 2025 年研究在 Whisper base、10 条英文 LibriSpeech 音频上报告 INT5 与未量化 WER 同为 0.0199，模型从 141.11 MB 降到 52.75 MB；但样本很小、只测英文，且不能确认与本计划的 q5_1 文件和 Android 参数完全相同，因此不能作为“中文收益无损”的保证。基准必须直接比较 **base q5_1 与 tiny q5_1**，必要时抽样对照未量化 base。

上述 q5_1 文件大小来自当前模型仓库元数据，集成时要固定具体 whisper.cpp 版本、模型 URL 与 SHA-256，不能运行时下载“latest”。官方来源还包括 [Whisper README](https://github.com/openai/whisper#available-models-and-languages)、[Whisper 论文的模型缩放及原始多语种表](https://arxiv.org/html/2212.04356#S4.SS1)；量化小样本仅作补充参考：[Quantization for OpenAI’s Whisper Models](https://arxiv.org/html/2503.09905v1)。

## 3. 构建与模块结构

### 3.1 Product flavors

在 `app` 增加一个 flavor dimension（示意名 `speech`）：

```kotlin
flavorDimensions += "speech"
productFlavors {
    create("standard") { dimension = "speech" }
    create("voice") { dimension = "speech" }
}
```

- 两个 flavor 保持相同 `applicationId` 和签名，用户只能安装其中一个，但应能在二者之间覆盖升级并保留配置。切换方向、相同 versionCode 的系统安装行为须在真机验证；若普通安装器拒绝同 versionCode 替换，再决定采用成对 versionCode 或仅在应用内提供标准/语音组件开关，不能临时改成两个应用 ID 导致数据割裂。
- `src/voice/AndroidManifest.xml` 单独声明 `android.permission.RECORD_AUDIO`；standard 合并后的 manifest 必须确认完全没有录音权限。
- `src/main` 只放 `VoiceInput` 接口、草稿合并规则和通用 UI 插槽；`src/standard` 提供“无语音能力”，不显示按钮；`src/voice` 提供录音、模型和 JNI 实现。
- 最好增加独立 `:voice` Android library，`voiceImplementation(project(":voice"))`。这样 standard 的依赖树、native `.so`、模型和许可证资产不会被误打包。
- 固定 whisper.cpp release/commit；使用 CMake + NDK 构建。先支持 `arm64-v8a` 基准，之后再决定是否提供 `armeabi-v7a`。GitHub universal APK 若打包多个 ABI 会明显增大；Google Play AAB 可按 ABI 拆分。

### 3.2 模型分发

首版建议 **voice APK 内置一套通过基准的模型**，而不是首次点击再下载：

- 优点：安装完成即保证离线；没有模型下载中断、CDN、磁盘余量、版本兼容和校验 UI；“voice”版本含义明确。
- standard APK 保持当前约 12 MB 的量级（以 0.7.2 release 12,104,747 bytes 为基线）。
- voice APK 的硬预算暂定为 **约 100 MB**。按 0.7.2 standard 12,104,747 bytes + base q5_1 59,707,625 bytes 计算，模型与现有 App 合计约 71.8 MB；剩余空间能否容纳 whisper.cpp/ggml arm64 native 库和打包开销须由实际构建确认。
- base q5_1 入选时优先发布 arm64 voice APK，并在 Google Play 使用 AAB ABI split；若 universal APK 超过预算，不因 32-bit/x86 测试 ABI 重复打包而直接降级模型。
- tiny q5_1 仅作为低内存/体积回退或另行下载选项；不在同一 APK 同时放 tiny 和 base。
- 若未来改为可下载模型，必须下载到应用私有目录，先校验长度和 SHA-256，再原子重命名；取消/失败清理临时文件，设置提供删除模型和显示占用。无模型时可显式转系统识别，但不得把可能联网的路径冒充离线。

发布 workflow 输出并签名：

```text
mtmux-vX-standard.apk
mtmux-vX-standard.apk.sha256
mtmux-vX-voice.apk
mtmux-vX-voice.apk.sha256
```

CI 应用 `aapt2 dump permissions`/`apkanalyzer` 断言：standard 无 `RECORD_AUDIO`、无 voice native/model；voice 有权限和指定模型；两个 APK 的 package/version/cert 相同。Release 说明列出各自体积、ABI、模型版本和 SHA-256。

## 4. 录音与识别架构

建议组件边界：

```text
Terminal footer
  -> VoiceInputController (state machine)
      -> AudioRecorder (16 kHz mono PCM, foreground only)
      -> VoiceActivity / max-duration guard
      -> SpeechEngine
          -> WhisperCppEngine (voice flavor)
          -> SystemSpeechEngine (optional fallback)
      -> DraftMerge (insert / append / replace; never send)
```

状态机至少包含 `Unavailable / PermissionNeeded / Ready / Recording / Transcribing / Result / Error / Cancelled`。每次启动记录不可复用的 request ID，并快照当前 `wireToken`、`draftScopeKey` 和 pane identity；回调到达时任一身份变化，就停止自动写入，只在面板中保留结果供用户明确复制，绝不能写进另一个任务草稿。

### 录音

- `AudioRecord`，16 kHz、mono、PCM 16-bit；只在用户点击后录音，单次默认 30 秒、硬上限 60 秒。
- 音频保存在有界内存缓冲；60 秒约 1.83 MiB。除非低内存实测要求，否则不落盘。若必须用临时文件，只放 cache，结束/取消/崩溃恢复时清理。
- 首次点击才请求麦克风权限；拒绝后可继续键盘输入，不重复弹权限。锁屏、退后台、离开终端、连接结束、来电/音频焦点丢失时立即停止并释放。
- 录音和推理不得占用 SSH writer 或主线程；单独的受控 executor，一次只允许一个任务。取消后晚到结果按 request ID 丢弃。

### 推理

- 首版采用“按住/点击开始 → 停止 → 整段转写”，不假装移动端 Whisper 是成熟的逐字流式引擎。录音时显示音量和计时；停止后显示“正在转写”。
- 语言设置默认 `Auto`，另提供至少中文、English；Auto 支持同一套多语言模型，但中英混说效果仍须实测。界面语言和识别语言分开保存。
- 可实验固定技术词 initial prompt（SSH、tmux、Git、GitHub、Codex、Kiro、OpenCode 等），但必须 A/B 测试；如果提高幻觉或污染普通句子则关闭。不要把 prompt 文本写入日志。
- 对空白、纯静音和低置信结果不覆盖草稿；不要自动补 shell 引号、回车或命令格式。

## 5. UI 与草稿行为

voice 版底栏严格按以下顺序：

```text
工具 | 收起键盘 | 🎤语音 | 快捷回复 | 发送回车
```

- 360dp 英文界面五个完整文字按钮会过窄；语音按钮使用固定最小 48dp 的麦克风图标，提供中英文 `contentDescription`，录音时变为红色停止图标/计时。收起键盘也可继续使用图标优先布局，但不能降低触控目标。
- standard 保持当前四按钮，不留空白占位。
- 点击语音先收起键盘，打开紧凑 bottom sheet/覆盖层；不能改变固定终端网格、PTY rows/cols 或当前阅读锚点。
- 面板显示：离线标识、识别语言、开始/停止/取消、录音时长、转写进度、最终文本和“放入输入框”。不展示模型内部 token/调试信息。
- 最终文字**只进入现有 Compose 文本框**，从不直接调用 `sendDraft`：
  - 草稿为空：用户点击“放入输入框”后填入并聚焦，可继续修改；不自动发送。
  - 草稿非空：复用快捷回复的“追加 / 替换 / 取消”，默认不覆盖。
  - 录音开始后用户又编辑草稿：视同草稿非空/已变化，必须再次选择追加或替换。
  - 转写结果可保留在当前面板内直到明确操作或取消，但不跨进程落盘。
- 断线时可保留已经插入文本框的草稿；录音中断线则停止录音/推理或只保留面板结果，不能发送。

## 6. 隐私、安全与许可证

- standard 不声明麦克风权限；voice 首次主动录音才请求。设置和首次使用明确说明：Whisper 模式音频只在本机处理，系统识别后备可能由设备服务联网。
- 音频、转写文本、语言、置信信息不写 DebugLog，不进入首页状态缓存、云同步、崩溃信息或自动剪贴板。日志只记固定事件和数值：权限结果、录音开始/结束原因、时长、引擎枚举、模型加载结果、推理耗时、取消/错误代码。
- App 退后台立即停止录音，不做前台服务、常驻麦克风、热词唤醒或后台监听。
- 固定 whisper.cpp 与模型来源，检查 whisper.cpp/OpenAI Whisper 模型许可证及所有 native 传递依赖；更新 `THIRD_PARTY_NOTICES.md` 并把必要许可文本放进 voice APK。native 库还须检查四/十六 KiB 页面兼容、ABI、符号与 Play SDK/政策要求。
- 模型和 native 二进制须有可重复构建/下载脚本与 SHA-256；CI 不从未经固定的 URL 拉取可变文件。

## 7. 基准与验收门槛

### 7.1 语料

建立不包含真实凭据的 60 条录音集，每条保留期望文本：

- 中文 15、英文 15、中英混合 15、其他目标语言 15（首轮建议日语/西语各一部分）；
- 10/30/60 秒，安静/风扇/街道噪声，近讲/远讲；
- 技术词：SSH、tmux、Git/GitHub、pane、socket、jump host、Codex、Kiro、OpenCode、文件路径、参数、数字和标点意图；
- 至少两名说话者，不上传真实终端内容。

记录 CER/WER、技术词正确率、人工修改字符数、停止到结果 P50/P95、实时因子、模型加载时间、Java/native 峰值 PSS、CPU、温升、耗电和崩溃/ANR。

### 7.2 决策门槛（首轮目标，实测后调整）

| 指标 | base q5_1 入选门槛 |
| --- | --- |
| 最终签名 voice APK | 目标约 ≤ 100 MB；同时报告 arm64 与 universal/AAB 实际值 |
| 10 秒语音停止到结果 | 中端目标机 P95 争取 ≤ 4 秒；必须同时报告 tiny 对照，不以桌面/A100 数据代替 |
| 30 秒语音 | 不因内存终止；推理不阻塞 SSH 输出和草稿编辑 |
| 峰值进程 PSS | 先以 ≤ 550 MB 为工程目标并实测；低内存设备失败时给出可恢复提示 |
| 中文/中英混合 | 至少保持官方数据所示的 base 优势方向；项目技术词人工修改量显著优于 tiny，或以更稳定的低错误率胜出 |
| 技术词 | 典型命令回复无需大面积重写；强制语言与 Auto 分别测试 |
| 空白/静音 | 不生成幻觉文本覆盖草稿 |
| 连续 10 次 | 无 native crash、资源泄漏、明显持续升温后失控 |

base 是默认候选，但不是无条件上线：若 base q5_1 相对 tiny 在本项目语料没有可感知质量收益，或超出速度/内存门槛，就回退 tiny；若两者都不合格，voice 首版使用系统识别并标注联网边界。

### 7.3 自动化矩阵

- **standard build**：现有全部测试；manifest 无麦克风权限；APK 无 model/native；底栏顺序保持四项。
- **voice build（不调用真实模型）**：权限同意/拒绝/永久拒绝、开始/停止/取消、静音、录音上限、连接/目标切换丢弃迟到回调、已有草稿追加/替换/取消、从不自动发送、后台/锁屏释放、横竖屏和语言切换。
- **native instrumentation**：固定短 WAV 的中/英转写 smoke test；错误模型/校验失败/内存不足返回结构化错误，不崩进程。
- **真机**：Android 26、31、33+；arm64 高/中/低端；无 Google 服务设备；蓝牙耳机与麦克风占用；录音时 SSH 大量输出；电脑同时 attach；深浅主题、中文/英文、360dp 底栏。
- **发布检查**：两个 APK checksum、证书、package/version 一致；标准版不能请求麦克风；voice 版模型 checksum 和许可证存在；从 standard 覆盖安装 voice、再切回 standard 均保留服务器、凭据、草稿规则和主机信任（真机确认）。

## 8. 实施拆分

| 阶段 | 交付 | 估算 |
| --- | --- | --- |
| V0 基准原型 | 固定 whisper.cpp；以 base q5_1 为主、tiny q5_1 为对照，必要时抽样未量化 base；离线 WAV + 真机录音基准报告；确认 ≤100 MB 与模型/ABI | 3–5 人日 |
| V1 构建拆分 | standard/voice flavors、`:voice` 模块、voice-only 权限/依赖/模型、双 APK CI 与发布检查 | 2–4 人日 |
| V2 录音与引擎 | AudioRecord、状态机、JNI、取消/生命周期/错误、模型校验 | 4–7 人日 |
| V3 UI 与草稿 | 五按钮布局、语音面板、语言、追加/替换、连接身份保护、双语/无障碍 | 3–5 人日 |
| V4 验收发布 | 34 项既有回归 + 双 flavor 新测试、真机性能/功耗、许可证、文档和双资产 Release | 3–6 人日 |

总计约 **15–27 人日**，最大不确定性是 base q5_1 在项目技术词上的实际收益和 Android 低端机推理速度，而不是语音按钮本身。第一项应先做 V0；基准不通过时可以在没有污染主应用架构的情况下换回 tiny、其他模型或系统识别。