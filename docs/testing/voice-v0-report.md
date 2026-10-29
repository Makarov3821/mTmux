# 语音 V0：whisper.cpp Android 基准报告

> 历史记录：当前主应用已切换为 [X-ASR INT8](voice-xasr.md)。本文的旧引擎、开关与构建命令仅适用于当时提交；对照实验保留在 `test-audio/`。

日期：2026-10-08。状态：**本报告记录产品整合前的独立 V0 数据。** 当时只有 x86_64 模拟器英文 smoke，产品 App/权限/UI 未改变；用户随后明确接受约 100 MB 并要求直接交付 base，0.8.0-dev 已据此整合。V0 结果仍不能替代 ARM 真机质量/性能验收。

## 目标与边界

V0 用固定依赖在 Android 上比较 multilingual `tiny q5_1` / `base q5_1` 的延迟、实时因子、进程峰值 RSS、语言检测和转写文本。它读取已准备的 16 kHz mono PCM16 WAV，不录音、不联网、无 `RECORD_AUDIO`/`INTERNET` 权限；不把模型提交到 Git。

独立模块 `:voice-benchmark` 使用 application ID `dev.mtmux.voicebenchmark`，只为开发基准。正式 `dev.mtmux` APK 不依赖该模块的 native 库。benchmark release APK 用 debug key 方便本地安装，不用于对外发布。

## 固定供应链

| 内容 | 固定版本 / 大小 | SHA-256 |
| --- | --- | --- |
| whisper.cpp | v1.9.5，commit `d1be6fde11ac6e0407606b4e42fe72d34add8037`；source tar 9,631,456 B | `645445da884df6dbd52b30f82d7be7c01d1767c938714e477e786b88d529e9f4` |
| HF 模型仓库 | revision `5359861c739e955e79d9a303bcbc70fb988958b1` | — |
| tiny q5_1 | 32,152,673 B | `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7` |
| base q5_1 | 59,707,625 B | `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898` |
| JFK WAV | 352,078 B，11.0 s | `59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e` |

下载脚本在写入前同时检查长度和 SHA-256，使用临时文件后原子替换；源码归档拒绝路径穿越及符号/硬链接，并以全部 2,039 个解压常规文件的确定性 tree SHA-256 `6c5e308f2a6bda094acaac548222e5c3a34f50d5f3d62e1d64689003cbd12572` 绑定解压树。每个 CMake configure task 都先执行 `verify-source`，本地修改/残缺源码会让构建失败。whisper.cpp 和 OpenAI Whisper 权重均为 MIT，许可文本随 benchmark APK 打包，模型本身留在忽略目录 `.voice-benchmark/`。

## 重现

要求 JDK 17、Android SDK 36、NDK `28.2.13676358`、CMake `3.22.1` 和已连接的 `arm64-v8a` 或 `x86_64` Android 设备：

```bash
# 下载并校验源码、tiny/base 模型和固定音频（约 102 MB）
python3 scripts/fetch_voice_benchmark.py all
python3 scripts/fetch_voice_benchmark.py verify

# 构建 release 优化、debug key 签名的独立 benchmark APK
./gradlew :voice-benchmark:testReleaseUnitTest :voice-benchmark:assembleRelease :voice-benchmark:lintRelease

# 每个模型独立冷启动进程，重复三次；结果写到被忽略的 .voice-benchmark/results/
python3 scripts/run_voice_benchmark.py --models tiny,base --audio .voice-benchmark/audio/jfk.wav \
  --language en --threads 4 --repeat 3

# 比较自动语言选择
python3 scripts/run_voice_benchmark.py --models tiny,base --language auto --threads 4 --repeat 3 --no-build
```

自定义音频必须是 RIFF/WAVE PCM、16 kHz、mono、signed 16-bit，0.1–60 秒；解析器要求 RIFF 声明长度与文件完全一致，限制所有 chunk/padding 在容器内，并拒绝重复 fmt/data、不一致 header、截断、超限和其他编码。runner 在 host 和设备上校验固定模型 SHA-256，通过 app 私有目录传入，不给予 benchmark 任意文件读取路径。无论成功、超时或错误，runner 都 force-stop benchmark 并删除设备 staging、私有音频和设备结果；只有 host `.voice-benchmark/results/` 保留转写。

每次 JSON 包含：whisper/model/设备/ABI、输入时长、线程数、模型加载/推理/总时间、real-time factor（推理时间 ÷ 音频时间）、current/peak RSS、整进程 PSS、推理前及释放 context 后的 native heap（不是峰值）、请求/检测语言、分段数、system info 和转写文本。音频正文不进入正式 App 日志。

## 当前环境

- Android 15 / API 35 x86_64 emulator，4 个推理线程，约 3,048,052 kB RAM；system info 只有 SSE3/SSSE3，**不是目标 ARM NEON 性能**。
- 固定 JFK 11 秒英文演讲；每组 3 次、每次 force-stop 后冷启动 benchmark Activity。设备文件页缓存仍可能存在，因此模型 load 时间不能视为首次安装冷盘结果。
- 原始整理结果：[emulator-results.json](../evidence/voice-v0/emulator-results.json)。

### 明确指定 English

| 模型 | load 中位数 | inference 中位数 | RTF 中位数 | peak RSS 中位数 | 转写 |
| --- | ---: | ---: | ---: | ---: | --- |
| tiny q5_1 | 95 ms | 4,707 ms | 0.428 | 230,992 kB | 正确；`Americans ask` 无逗号 |
| base q5_1 | 134 ms | 11,680 ms | 1.062 | 280,884 kB | 正确；增加预期逗号 |

### 自动语言

| 模型 | load 中位数 | inference 中位数 | RTF 中位数 | peak RSS 中位数 | 检测 |
| --- | ---: | ---: | ---: | ---: | --- |
| tiny q5_1 | 96 ms | 9,069 ms | 0.824 | 231,052 kB | en |
| base q5_1 | 135 ms | 22,727 ms | 2.066 | 281,056 kB | en |

## 解释与暂定决策

1. native/JNI/WAV/模型/JSON/ADB 链路有效；两个模型均正确转写 smoke 样本，最终报告为 whisper 1.9.5。
2. 该模拟器上 base 相比 tiny 推理约慢 **2.48 倍**，但 peak RSS 只增加约 **50 MB**。base 明确指定英文时刚慢于实时（RTF 1.062），自动语言时为 2.066。这不代表 ARM 手机也有相同比例。
3. 自动语言相对显式 English 在两个模型上都接近 **1.9 倍耗时**。产品应记住用户选择的识别语言，并优先显式指定；`Auto` 保留为可选项，是否默认须等中文/中英混合真机测试。
4. base 的模型空间仍符合约 100 MB 目标：0.7.2 release 12,104,747 B + base 59,707,625 B + 当前 arm64 native `.so` 2,707,544 B = 约 **74.5 MB**（尚未包含最终 voice UI/打包差异）。benchmark universal APK 自身 6,491,920 B，含 arm64+x86_64 和重复 Kotlin runtime，不能直接加到产品 APK。
5. **V0 当时只把 base q5_1 作为质量首选候选。** 后续用户明确选择直接整合 base；这不改变仍需物理 arm64 手机 + 中文/英文/中英混合/技术词语料验证的边界。若真机延迟或人工改字量不可接受，后续版本仍可重新评估。

## 已完成验证

- benchmark WAV JVM 单测、release APK、Android lint（0 error；12 项 benchmark-only i18n/icon/deprecation warning）。
- NDK r28c 生成的 arm64/x86_64 ELF 所有 LOAD segment 对齐 `0x4000`；APK `zipalign -P 16` 通过。
- 固定下载 `verify` 全通过；模型不在 Git 状态中。
- 正式 App 回归：Web 4 单测 + browser smoke、core/真实 OpenSSH、隔离 tmux 4 项、APK/test APK/lint，以及 Android 15 emulator 34/34（0 skip/failure）全通过。
- 主 CI 不下载模型/NDK，运行 `:voice-benchmark:testReleaseUnitTest` 及 standard 构建；0.8 起独立 `Voice checks` workflow 在 voice 相关改动时获取固定源码/base，编译 JNI、检查 16 KiB/权限/模型/体积，并可在手动 workflow 中执行 Android 15 模拟器 bundled model、取消和 voice UI smoke。tiny 与完整语料 benchmark 仍为手动/真机门槛。

## 下一步

1. 在至少一台中端 arm64 真机运行相同 JFK 命令，记录 CPU/SoC、RAM、Android、温度前后；base/tiny 各 5 次。
2. 建立不含个人信息的 60 条语料及 reference（中文、英文、中英混合、技术词、静音/噪声），再决定 base/tiny；当前英文单句不能评价 CER/WER 或幻觉。
3. 测 `zh` 显式语言与 `auto`，决定产品默认；尝试技术词 initial prompt 的 A/B，不能只看单句。
4. 用户已选择在 0.8.0-dev 实施 `standard` / `voice` flavors、`AudioRecord` 和草稿合并 UI；本报告中的 V0 benchmark 自身仍不申请麦克风权限。
