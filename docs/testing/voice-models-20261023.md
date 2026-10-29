# q8 与离线标点模型试验

> 历史记录：当前主应用已切换为 [X-ASR INT8](voice-xasr.md)。本文的旧引擎、开关与构建命令仅适用于当时提交；对照实验保留在 `test-audio/`。

2026-10-23，未发布修改。用户要求寻找更小的中文标点方案，必要时接受 CT-Transformer INT8，并尝试把 Whisper q5 换成 q8。

2026-10-25 后续本地改动：SRF 的弱标点和提前句末标点增加保守过滤，见 [过滤记录](srf-conservative-punctuation.md)；该调整尚未进入 rc.2 发布包。

## 最终实现

默认：`按住录音 → Whisper multilingual base q8_0 → CT-Transformer INT8 → 可编辑草稿`。

另提供 q8 + SRF INT8 的轻量对照构建。每个 APK 只打包一套 Whisper 权重和一个标点模型；轻量包另带独立词表，不把 CT 与 SRF 同时内置。设置的“自动补充标点”默认开启，可以关闭以查看 Whisper 原文。结果仍需用户发送；已有草稿保留追加/替换选择。

Whisper 参数及原生引擎仍为 whisper.cpp 1.9.5、CPU greedy、temperature 0、best_of 1、最多 4 个推理线程；只切换量化权重。Whisper 原生上下文释放后才运行标点模型。标点按需创建单线程 ONNX Runtime 1.23.2 session，关闭线程空转，完成或取消后关闭 session；不在待机时加载模型或推理。全局 ONNX 环境和加载的运行库不等于模型权重常驻。

标点采用原文偏移重建，只允许插入标点，保留原有字符、大小写、空格、换行及标点。检测到的代码、引号片段、路径、URL、文件名和参数跳过插入；字符级模型不能在英文单词内部插标点。它不是任意编程语言的语法解析器，最终文字仍可编辑。长文本用重叠窗口覆盖到末尾，不按首个窗口截断；超过 4096 UTF-16 单元的内容回退原文。

失败回退 Whisper 原文；取消、切换目标或后台退出丢弃结果，不让迟到的标点结果覆盖用户草稿。每个标点任务独立持有 ONNX RunOptions，取消旧任务不会终止新任务。日志新增 `VOICE_PUNCTUATE`、`VOICE_PUNCTUATE_DONE`、`VOICE_PUNCTUATE_FAILED`，只记录阶段、耗时与处理标记，不记录文本。

## 找到的小模型

| 候选 | 实际文件大小 | 结论 |
| --- | ---: | --- |
| SRF 中文标点 INT8 + 词表 | 2,243,714 + 119,104 bytes | 已接入轻量对照；作者面向手机语音输入法，约 1.57M 参数；样例有误断句，未当作与 CT 等质量替代 |
| CT-Transformer 中英混合 INT8 | 75,519,198 bytes | 默认对照基线；这批长指令断句更连贯；不是每句话都保证更准确 |
| Funmula 0c D512 | 130,584,075 bytes，未量化 ONNX | 主要为繁体输入；作者披露验证集曾参与训练，不直接据此替代本项目的简体技术语音链路 |
| Edge-Punct-Casing INT8 | 官方约 7.1 MiB | 仅英文，不适合作为中文默认方案 |
| zh-wiki / zh-yue / multilingual-small 等 | 核对的现成权重约 268～615 MB | 没有比本轮候选更合适的现成包体 |

SRF 原始发布代码会清理已有标点、截断长度；本项目重新实现了保留原文的重建、字面量保护和窗口覆盖，不直接复用该行为。

来源：[SRF 作者仓库](https://github.com/ximeiorg/srf-punctuation)、[SRF 权重](https://huggingface.co/rkingzhong/srf-punctuation)、[sherpa-onnx 标点模型说明](https://k2-fsa.github.io/sherpa/onnx/punctuation/pretrained_models.html)、[Funmula 模型卡](https://huggingface.co/Funmula/0c-punct-d512)。

## 实际对照

五条固定中文样例的原始输出和桌面 CPU 推理时间保存在 [punctuation-comparison.json](../evidence/voice-models-20261023/punctuation-comparison.json)。例如：

| 原文 | SRF | CT-Transformer |
| --- | --- | --- |
| 这个问题是什么原因造成的可以帮我检查一下吗 | 这个问题是什么？原因造成的？可以帮我检查一下吗？ | 这个问题是什么原因造成的，可以帮我检查一下吗？ |
| 先检查连接超时的原因然后修改代码修改完成后运行测试 | 先检查连接超时的原因，然后修改代码修改，完成后运行测试。 | 先检查连接超时的原因，然后修改代码，修改完成后运行测试。 |

因此保留 CT 为默认构建，提供 SRF 小包实测。这里没有足够的独立标注语料来报告 F1 或总体优劣；耗时是 Linux 桌面单线程结果，不是 Android 时延，JSON 展示原始模型输出，不是产品的字面量保护结果。

同一段公开中文音频的 q5/q8 CPU greedy 对照见 [whisper-comparison.json](../evidence/voice-models-20261023/whisper-comparison.json)。两者输出不同，单样本没有体现出确定的 q8 质量优势；没有人工标注，不计算 CER，也不把 q8 称为已验证的识别精度升级。q8 按用户要求供进一步真实录音试用。

## 构建与校验

```bash
python3 scripts/fetch_voice_benchmark.py deps
python3 scripts/fetch_voice_benchmark.py base
python3 scripts/fetch_voice_punctuation.py
./gradlew :app:assembleVoiceDebug :app:assembleVoiceRelease

# 较小的标点模型，Whisper 同样为 q8
python3 scripts/fetch_voice_punctuation.py --model srf
./gradlew -PvoicePunctuationModel=srf :app:assembleVoiceDebug :app:assembleVoiceRelease

# 同录音比较 q5；每个包仍只带一个 Whisper 模型
python3 scripts/fetch_voice_benchmark.py base-q5
./gradlew -PvoiceWhisperQuantization=q5_1 :app:assembleVoiceDebug
```

固定来源、revision、文件长度和 SHA-256 分别在 `fetch_voice_benchmark.py`、`fetch_voice_punctuation.py`、Gradle BuildConfig 中。资源生成是 Sync，不会在切换参数后残留上一套包内模型；首次使用校验安装到应用私有目录，替换后清理旧模型缓存。下载支持有限重试及断点续传，只有最终大小与 SHA-256 均匹配才原子安装；错误 Range、忽略 Range、截断和错误摘要有四项本地测试。

`verify_voice_apk.py` 检查 standard 不含语音模型和 ONNX 库、voice 只包含指定模型及词表，并验证两个 ABI 的运行库和模型摘要。由于用户已接受较大的标点方案，CI 的 voice 包体保护调整为 200,000,000 bytes，原 100 MB 目标不再被错误地宣称已满足。Release workflow 仍默认 q8 + CT；Voice checks 额外构建 SRF 对照包。

## 本轮验证与包体

最终构建数据见 [build-summary.json](../evidence/voice-models-20261023/build-summary.json)：

| q8 组合 | 未签名 Release | 本地测试签名 APK |
| --- | ---: | ---: |
| CT-Transformer INT8（默认） | 196,535,810 bytes / 196.5 MB | 196,582,345 bytes / 196.6 MB |
| SRF INT8（轻量对照） | 136,725,073 bytes / 136.7 MB | 136,768,532 bytes / 136.8 MB |

两种包均包含 arm64-v8a 和 x86_64。ONNX Runtime 两套 ABI 的 `.so` 合计约 42.7 MB，另有 Whisper 原生库；APK 下载体积、安装时的模型副本与运行时峰值内存是不同指标，不能只把两个模型的原始文件长度相加。

测试包位于本机 `app/build/outputs/voice-comparison/mtmux-q8-{ct,srf}-trial.apk`，旁有 SHA-256 文件。它们采用 Release 构建配置并使用本地 Android 测试证书签名，可安装但不能覆盖 GitHub 正式证书版本；不是正式发布，也没有推送新版本标签。两套测试包的包名及版本相同，互相替换时不需要修改代码。

- standard / voice app 单元测试各 22 项通过；两种标点后端分别运行 voice 模块 13 项测试，全部通过。
- 下载完整性四项测试通过；standard、两种 voice 的 APK 构建与 voice Android 测试 APK 编译通过。
- 两种 Debug lint 均 0 错误，standard 78 / voice 50 条警告。两种试用包模型 SHA、flavor 隔离、APK 签名与 ZIP 16 KiB 对齐通过；新增 ONNX 库及 Whisper 的 ELF LOAD 段均为 16 KiB 对齐。
- 首轮桌面 CMake 基准生成了下载源码目录内的 `bindings/javascript/package.json`，固定源码目录校验正确地拒绝构建；已恢复固定源码，再次通过目录 SHA 与后续构建。
- 切换模型时 AGP 的增量 Debug ZIP 可能保留已删除模型的空洞，导致物理 APK 比有效条目更大。轻量 CI 构建前删除原 Debug APK、重新打包，实际验证新包只含 SRF 模型/词表且小于包体保护；最终试用包从独立 Release 产物签名，避免沿用增量 Debug 的空洞。

新增真实模型 Android 测试覆盖补标点与技术字面量保留、开始前取消；已完成测试 APK 编译，当前无已连接 ARM 真机，未运行设备推理、麦克风与手势验收。桌面模型运行及构建检查不能代替手机延时、温升和识别质量验证。
