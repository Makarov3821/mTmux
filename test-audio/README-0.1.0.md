# 语音对照实验室

独立 Android 测试程序，包名 `dev.mtmux.audiotest`，与 mTmux 并存。主应用的版本、依赖和语音逻辑不受这个工程影响。

## 使用

1. 安装 `outputs/audio-lab-0.1.0-arm64.apk`，支持 Android 8.0+ 的 ARM64 手机。
2. 默认勾选全部 10 组。点击“准备选中模型”，从固定上游地址下载并核对 SHA-256；或者将 `outputs/audio-lab-models.zip` 复制到手机，在 App 内点击“导入模型 ZIP”。模型只需要准备一次。
3. 按住底部按钮，等“录音中”出现后说话，松手依次比较。首次授权麦克风后需重新按住。最长 60 秒；手指离开按钮导致手势取消或应用退到后台时丢弃这次录音。
4. 每张结果卡展示实际模型文件空间、加载 / ASR / 标点 / 总耗时、RTF、原始输出和最终输出。文本可以长按复制。
5. “重跑录音”复用当前音频；“导入 WAV”接受 16 kHz、单声道 PCM16、0.1～60 秒 WAV，不偷偷重采样。“导出”生成包含 `audio.wav`、`results.json`、`models.json` 的 ZIP。

全部模型去重后 **886,863,652 字节，约 845.8 MiB**。手机联网下载的原始模型包还包含不部署的精度和分块版本；下载解包时需要额外临时空间。导入离线 ZIP 可以避免这些冗余下载。模型保存在应用私有目录，清除应用数据或卸载会删除模型。界面另外显示 APK 文件大小和实际已保存模型空间。

## 十组方案

空间包括实际部署权重、词表，以及需要时的 SRF / CT；不含运行库。以下不是 APK 体积。

| 组 | 方案 | 空间 MiB | 配置 |
|---|---|---:|---|
| 01 | Whisper Base Q8 + CT | 150.00 | 已发布 CT 基线 |
| 02 | Whisper Base Q8 + SRF | 80.23 | 已发布 SRF 基线 |
| 03 | Paraformer Small + SRF | 80.36 | INT8 模型 |
| 04 | Zipformer zh-en 2023-11-22 + SRF | 74.03 | INT8 编码器、连接器 / FP32 解码器 |
| 05 | Zipformer Small bilingual 2023-02-16 + SRF | 59.61 | INT8 编码器、连接器 / FP32 解码器 |
| 06 | Whisper Base Q5_1 | 56.94 | 原生输出，不外挂标点 |
| 07 | Whisper Base Q8_0 | 77.98 | 与 01 / 02 使用完全相同的 ASR |
| 08 | Whisper Tiny Q8_0 | 41.52 | 原生输出，不外挂标点 |
| 09 | X-ASR zh-en 480ms INT8，2026-06-05 | 161.39 | 原生标点，FP32 解码器 |
| 10 | SenseVoice Small INT8，2025-09-09 | 226.43 | 开启 ITN，作为大模型对照 |

选择 SenseVoice 做第八个新增候选，避免为 NCNN 两个额外候选再引入第四套运行库；仍保留了轻量、小模型及较大模型的对照。

Zipformer Small 不使用包内的 INT8 解码器：在同一公开样例上，量化解码器仅输出“欢迎大家”，加长尾部静音至 3 秒也未恢复；替换 FP32 解码器后能输出整句，INT8 连接器可保留。这里采用可实际工作的混合精度组合，体积比头脑风暴中的约 50 MiB 更大。单条样例不代表总体准确率。

## 比较口径

- 同一 `FloatArray` 的 16 kHz PCM 用于所有组，无降噪、简繁转换、提示词或自动改写；明确保留原始文本。中文 / 自动 / 英文语言选项仅作用于 Whisper 与 SenseVoice，双语固定模型自行识别。
- Whisper 使用现有 whisper.cpp 1.9.5 的 CPU greedy 配置；4 线程。温度初值 0，保留上游的回退设置。Q8 三组分别完整解码，不复用上一次文本或耗时。
- CT / SRF 以主工程提交 `14fb48b4cee8431801edfadc9ca2ec3375be42a2` 的已发布规则为基线；**不包含未发布的 SRF 保守阈值调整**。原有标点保留，后处理失败会明确显示“标点失败”及原始文本。
- Sherpa-ONNX 1.13.8，CPU / 4 线程 / greedy；流式模型按 100ms 音频块推进、消费可解码帧，末尾补 1 秒静音并调用 `inputFinished`。
- 每组新建并释放识别器；ASR 释放后才运行标点。标点 ORT 使用 1 线程、禁止空转。没有同时常驻十个模型。
- 文件完整性校验在计时之外；校验会读一遍权重，因此文件缓存可能已预热。加载耗时包括识别器创建，ASR 耗时包括推理（Whisper 还包含释放），标点耗时包括标点模型加载与释放；总耗时包含整条流水线。RTF = 总秒数 / 原始录音秒数，流式补的静音不增加分母。
- 系统文件缓存不清理。多轮重跑可以启用“轮换首个模型”；缓存、温度、系统负载仍会影响结果。这里的“重新加载”不是保证物理冷盘读取。
- “停止”在当前原生调用安全返回后停止后续模型。不会在 JNI 仍使用识别器时强行释放内存。
- 原始输出可能本身有标点；比较原始 / 最终输出能定位后处理插入。SenseVoice 的 ITN 输出不保证每句话都有标点。
- 不展示未经测量的内存或推算 CER / WER。参考文本随导出保留，供之后人工评估。APK 和权重体积不能当作运行峰值内存。

## 构建

工程独立于主工程 Gradle 设置，无须修改主工程的 `settings.gradle.kts`。

```bash
# 在仓库根目录，已有 local.properties / 本地 Android 工具链时：
cp local.properties test-audio/local.properties
bash test-audio/tools/build.sh

# 可选：下载并准备全部部署文件，再生成离线模型包。
python3 test-audio/tools/prepare.py pack
```

需要 JDK 17、Android SDK 36、NDK 28.2.13676358、CMake 3.22.1。脚本优先复用仓库 `.tools`，固定并校验 Sherpa 原生库 / Kotlin API、Whisper 源码及全部权重。`vendor/`、`.cache/`、`outputs/` 和构建产物均忽略，不将大模型提交进 Git。

默认 Release 只构建 ARM64，以缩小 APK。Debug 原生编译关闭自动向量化，以避开软件 x86 模拟器的 SSE 页边界故障；手机性能比较使用 Release，保持正常优化。它使用本机 Android 测试证书签名，是独立测试包，无需主应用的 GitHub 发布证书。重装时请复用同一测试证书。需要 x86_64 模拟器版本时：

```bash
./gradlew -p test-audio -PaudioTestAbis=x86_64 :app:assembleDebug :app:assembleDebugAndroidTest
```

Android 完整引擎冒烟测试的源代码见 `EngineSmokeTest.kt`。运行前启动 App 使其外部 files 目录可用，将模型 ZIP 和短音频分别放在该目录的 `audio-lab-models.zip`、`smoke.wav`，然后运行 instrumentation。默认测试使用导入路径，检查十组解码、标点运行及三个 Q8 原始输出相同。软件模拟器可先预置经电脑校验的权重，传 `-e preinstalledModels true` 跳过大包解压及设备端全量散列，只检查文件长度并执行解码；`-e asrThreads 1` 用于软件模拟器的单线程兼容核验，`-e engineFilter sherpa` 可单独检查新增五个 Sherpa 方案。这些运行不算 Android ZIP 导入测试通过，也不能算 ARM64 手机性能结果。桌面兼容性检查用 `tools/smoke_sherpa.py`（Python 环境需要 sherpa-onnx 1.13.8、numpy）；桌面计时不代表 Android 性能。

## 来源

- [Sherpa 模型目录](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html)、[固定 Android 运行库](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)。模型包采用官方 `asr-models` / `punctuation-models` 发布文件；精确 URL、部署文件、大小和 SHA-256 在 `app/src/main/assets/models.json`。
- [流式 Zipformer 用法](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html)、[官方结束收尾示例](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/python-api-examples/online-decode-files.py)。
- [Whisper 固定权重版本](https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c739e955e79d9a303bcbc70fb988958b1)、[SRF 固定权重版本](https://huggingface.co/rkingzhong/srf-punctuation/tree/055df91f8f14696f93bb4eb4b56245ba51a6fcde)。
- `app/src/main/assets/licenses/` 包含运行库、原有模型及复用代码的许可 / notices；新增模型的原始发布来源单独列于 `THIRD_PARTY_NOTICES.md`。模型 ZIP 仅在本机生成，没有上传或创建 GitHub Release。

## 当前验证状态（2026-10-27）

Release 构建、7 项 WAV 单元测试、lint（0 错误）、签名、ZIP / ELF 16 KiB 对齐均通过。离线 ZIP 内的 22 个部署文件已逐一核对大小和 SHA-256；APK 内没有语音权重。桌面 CPU 实际解码已覆盖 5 组 Sherpa 权重和 3 种 Whisper 权重，记录见 `evidence/`；这不是准确率评测，也不是手机耗时。

**Android 完整十组矩阵尚未验证通过，尚无 ARM64 真机结果。** 软件模拟器先出现启动 ANR，随后在 Whisper mel 归一化的 x86 SSE 指令处发生页边界访问故障。已为 Debug 加入关闭自动向量化的兼容设置，并尝试预置权重、单线程运行；后续运行仍未在验证窗口内完成，模拟器已停止。不能据此声称十组 Android 解码全部通过。

结果与限制保存在 `evidence/build-checks.json`、`evidence/artifact-verification.json`。模型效果和耗时仍需在目标手机、使用同一份实际录音进行验证。
