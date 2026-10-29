# 主应用切换 X-ASR INT8

2026-10-30，按手机对照测试的选择，将主应用 voice 切换到 Audio Lab 使用的同一 X-ASR 480ms INT8。迁移验证时使用 0.8.5 开发版本；随后纳入 [0.9.0](../releases/0.9.0.md) 发布，既有 0.8.5 Release 保持原样。下列迁移证据中的本地 APK 哈希属于迁移验证构建，正式 0.9.0 签名附件以 GitHub Release 为准。

## 实现

`录音 → X-ASR 中英识别及原生标点/大小写 → 可编辑卡片 → 用户确认插入终端`。按住录音、60 秒上限、最后十秒变色、上滑取消、切换连接/后台取消以及插入不自动回车保持原逻辑。

模型包含 `encoder.int8.onnx`、FP32 `decoder.onnx`、`joiner.int8.onnx` 和 `tokens.txt`，共 **169,227,953 bytes / 161.39 MiB**。INT8 是上游方案名称，并不表示每个组件都是 INT8。下载归档 133,895,136 bytes；实际 APK 还包含运行时及主程序，不符合早期的 100 MB 整包目标。

固定文件与哈希：`voice/model.lock.json`、`voice/runtime.lock.json`。与实验室相同的 sherpa-onnx v1.13.8 Android 静态 ONNX Runtime 版本，包含 arm64-v8a / x86_64；构建下载器只提取白名单文件，下载和 APK 校验均检查文件大小与 SHA-256。

识别器仍按流式 API 工作：录音结束后以 100ms 分块喂入，增加 1 秒静音并调用 `inputFinished()` 排空末尾结果。端点检测关闭，greedy search、CPU 最多 4 线程，与实验室 X-ASR 配置一致；不传递界面语言，不另做简繁转换或标点改写。

待机时不加载识别器。每次转写创建一个识别器/stream，成功、失败或取消均释放。全局锁避免旧请求尚未退出时加载第二份模型；取消在每次 native decode 之间检查，不能打断正在执行的模型构造或单次 native 调用。请求以原子状态决定完成或取消，防止迟到结果覆盖草稿。

## 旧路径清理

删除主应用 Whisper/ggml JNI、CMake、Whisper 与标点模型配置、CT/SRF 后处理/单测、单独 Maven ONNX Runtime Java 依赖和对应许可。删除旧 `:voice-benchmark` 主工程模块、对照 APK 工作流，更新下载、发布、签名自检和包体校验。当前语音 CI 为 `.github/workflows/voice.yml`。

设置页移除自动语言检测和标点恢复开关，显示 X-ASR 模型说明。voice 启动后后台删除旧已解压模型、残留 `.part` 文件及两个旧偏好；不触碰服务器、信任记录或草稿。切换到 standard 继续删除整个私有 voice 模型目录。首次使用 X-ASR 会在私有无备份目录安装约 169 MB 模型，APK 内压缩资源仍占用安装空间。

独立 `test-audio/` 保留历史对照方案与记录。旧模型下载和标点评估脚本移到其 `tools/` 下；主应用与 CI 无依赖。旧发布记录、截图和测试证据作为历史保留。

## 验证与复现

```bash
python3 scripts/fetch_voice.py
python3 -m unittest discover -s scripts/tests
./gradlew :voice:testDebugUnitTest :app:testStandardDebugUnitTest :app:testVoiceDebugUnitTest \
  :app:assembleStandardDebug :app:assembleVoiceDebug :app:assembleVoiceDebugAndroidTest \
  :app:assembleStandardRelease :app:assembleVoiceRelease :app:lintStandardDebug :app:lintVoiceDebug
python3 scripts/verify_voice_apk.py \
  app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk \
  app/build/outputs/apk/voice/release/app-voice-release-unsigned.apk
```

切换模型时若复用旧 APK，AGP 增量 ZIP 可能保留已删除内容占用的空洞。先删除旧输出 APK 再打包；最终校验包含文件白名单、模型/运行库哈希、旧引擎黑名单、体积上限和 standard 隔离。主应用构建无须编译语音 C++ 源码。

固定中文与英文 WAV 仅在 `androidTestVoice/assets` 内，出处及哈希见同目录 README。Android 仪器测试覆盖实际解码、取消、旧文件/偏好清理和录音资源释放。测试输出只含公开样例文字；产品日志不记音频或转写正文。

Android 15 软件 x86_64 模拟器的两轮检查（4 线程和 1 线程）均通过取消前、取消进行中、升级文件/偏好清理及录音释放共四个用例。四线程轮在中文解码中主动终止；单线程轮的中文完整解码在关闭前完成并通过断言，输出“欢迎大家来体验达摩院推出的语音识别模型”，耗时 222,749ms（软件 TCG，不能作为手机时延）。随后启动的英文用例被主动终止，未取得结果，因此 **Android 中文已通过，英文与 ARM64 真机仍未完成验证**。终止报告中的 `Process crashed` 是主动 `am force-stop` 的结果，不能计为通过。公开音频在 Linux 桌面 sherpa-onnx 1.13.8 上均完成解码。专用模拟器已关闭。

本地构建 standard / voice 的 Debug、Release 与 voice 测试 APK 全部通过，两个 flavor 的 lint 均通过；JVM 单测 80 项（voice 4、standard 38、voice app 38）及 Python 资源测试 7 项通过。Release unsigned：standard 12,394,379 bytes，voice 193,095,605 bytes；Debug voice 199,253,611 bytes。两个架构运行库的 ELF LOAD 均为 16 KiB 对齐，Debug / Release voice APK 的 `zipalign -P 16` 校验通过。

实际构建和设备测试结果见 [验证证据](../evidence/voice-xasr-20261030/)。软件模拟器或桌面 CPU 测试只能确认接线与兼容性，不能据此推断手机延迟、准确率、峰值内存或温升；ARM64 真机长录音和中英混说质量仍需后续使用验收。

来源：[X-ASR 上游项目](https://github.com/Gilgamesh-J/X-ASR)、[模型卡](https://huggingface.co/GilgameshWind/X-ASR-zh-en)、[sherpa 固定运行时](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)。

历史 Whisper 下载缓存已归入 `test-audio/.cache/legacy-whisper/`，主项目不再保留 `.voice-benchmark/` 目录或依赖。
