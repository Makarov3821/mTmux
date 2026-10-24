# 离线语音模型预算与对照构建

2026-10-23。用户已允许尝试 q8，并接受必要时采用较大的 INT8 标点模型；当前未发布实现默认 q8 + CT-Transformer，另提供 q8 + 轻量 SRF 对照构建。实际包体与验证见 [模型试验记录](../testing/voice-models-20261023.md)。

原 100 MB 目标按 100,000,000 bytes 计算；APK 下载体积与安装占用、运行时内存分别看待。**下文保留加入标点模型前的 q8 单模型预算分析，不代表当前双模型 APK 体积。**

## 标点模型加入前的基线

0.8.4 的 `WhisperTranscriber` 固定的是 **Whisper multilingual base q5_1**，文件 `ggml-base-q5_1.bin`，59,707,625 bytes，约 59.7 MB / 56.9 MiB。运行时为 whisper.cpp 1.9.5，CPU 离线转写，最多 4 个推理线程。它不是英语专用 `.en` 模型。

已发布的 [v0.8.4 voice APK](https://github.com/Makarov3821/mTmux/releases/tag/v0.8.4) 为 74,589,257 bytes，约 **74.6 MB / 71.1 MiB**。本地按住录音改版的 Debug APK 为 79,726,278 bytes；Debug 包不能直接作为最终 Release 大小。

2026-10-23 从 whisper.cpp 模型仓库元数据核对：

| 模型 | 模型文件大小 | 与当前的差别 | 100 MB APK 预算 |
| --- | ---: | --- | --- |
| multilingual base q5_1（当前） | 59,707,625 bytes / 59.7 MB | base 的 5 位量化版本 | 当前正式 APK 约 74.6 MB |
| multilingual base q8_0 | 81,768,585 bytes / 81.8 MB | 相同 base 参数规模，8 位量化；不是更大容量模型 | 有希望，接近上限；须实际构建 |
| multilingual base，未量化 GGML 文件 | 147,951,465 bytes / 148.0 MB | 相同 base 参数规模，更高权重精度 | 该模型文件本身已超预算，不列为本轮候选 |
| multilingual small q5_1 | 190,085,487 bytes / 190.1 MB | 从 base 的 74M 升到 small 的 244M 参数 | 现成文件明显超预算，不列为本轮候选 |

来源：[base q5_1](https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-base-q5_1.bin)、[base q8_0](https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-base-q8_0.bin)、[small q5_1](https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-small-q5_1.bin)、[完整文件目录](https://huggingface.co/ggerganov/whisper.cpp/tree/main)、[Whisper 模型参数及取舍](https://github.com/openai/whisper#available-models-and-languages)。

### q8 包体估算

本地 0.8.4 未签名 Release APK 为 74,580,985 bytes；其中当前模型在 ZIP 中压缩后占 56,747,133 bytes。由此，除模型外占 17,833,852 bytes。若新模型完全不压缩，沿用 0.8.4 正式包与本地未签名包的 8,272 bytes 差额：

```text
17,833,852 + 81,768,585 + 8,272 = 99,610,709 bytes ≈ 99.61 MB
```

这是固定其他包内容后的替换估算，不是已经构建出的 q8 APK。实际压缩可能减小体积，新代码、ZIP 对齐与签名布局也会改变结果；距离严格 100 MB 仅约 0.39 MB，仍需最终正式签名包验证。若要给后续功能留余量，可另评估仅 arm64 发布；当前 voice 同时包含 arm64-v8a 与 x86_64，此项本次未修改。

## 能否改善标点

**q8 有对比价值，但不能把包体增长理解为识别能力必然升级。** q5_1 → q8_0 保留同一个 base 模型，只是量化方式不同、权重数值保留更充分。量化误差减少可能影响文字及标点输出，但在没有中文对照语料前，无法判断改善幅度或承诺会更积极地断句。

加入标点恢复前的实现没有删除标点；greedy 解码、温度 0、best_of 1，没有风格示例 prompt，也没有独立标点恢复。若缺标点来自模型的断句习惯，换到同架构 q8 未必解决；这是根据当前实现与量化关系作出的判断，不是手机实测结论。

建议把 base q8_0 作为下一次 **同录音 A/B 候选**，同时评估字词准确率、逗号/句号/问号、长条件是否分句、代码路径是否被误改、停录到草稿时间、峰值内存及最终包体。只测不同录音或只看模型大小无法分辨收益。若文字已准确而标点仍弱，再单独比较风格示例或标点恢复；[Whisper prompt](https://developers.openai.com/cookbook/examples/whisper_prompting_guide) 是上下文与风格样例，不是直接下达“加标点”的指令；[FunASR ct-punc](https://github.com/modelscope/FunASR#usage) 是单独的标点恢复路径，会增加模型及推理成本。

不把官方 GPU/英文速度表当作 Android 的延迟、耗电或内存测量。参数更多的 small 有整体识别质量对照价值，但本轮体积限制下不适合直接替换。
