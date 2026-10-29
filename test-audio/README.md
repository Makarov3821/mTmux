# 语音对照实验室 0.2.0

本轮集中比较 X-ASR 与 Paraformer 的文字识别和标点。独立包名仍为 `dev.mtmux.audiotest`，与 mTmux 并存。

## 安装与模型复用

安装 `outputs/audio-lab-0.2.0-arm64.apk`（Android 8.0+、ARM64）。直接覆盖安装 0.1.0，保持相同签名与应用数据。已有模型沿用原来的路径和 SHA-256，不需要重新导入模型包。

如之前没准备 CT，点“准备选中模型”补齐缺少的模型。旧版 `audio-lab-models.zip` 依然可以导入：模型文件未变，ZIP 内旧的方案清单不会覆盖新版 App 设置。

## 默认三组

| 方案 | 权重与词表 | 本轮用途 |
|---|---:|---|
| X-ASR INT8 · 原生标点 | 161.39 MiB | 保留你已认可的原生标点基线 |
| Paraformer Small INT8 · CT 语义标点 | 150.13 MiB | 新增较强标点方案；整段优先，最大 512 token 上下文 |
| Paraformer Small INT8 · SRF 旧版对照 | 80.36 MiB | 原规则不变，观察 CT 改善了哪些断句 |

三组共用模型去重后约 313.8 MiB，不计运行库；安装包不包含权重。“其它候选”可展开原有方案，共 11 组可选。默认不再下载或运行另外八组。

## 本轮改动

- **Paraformer 每段录音只识别一次。** CT 与 SRF 直接使用同一个原始字符串，避免两次识别的细微差异影响标点比较。每段新录音、导入音频和重跑都新建识别缓存。
- CT 使用完整的短句 / 长指令上下文，最多 512 token；超过上限时重叠 128 token，保留离窗口边缘更远的预测。SRF 和原 Whisper+CT 对照仍维持旧的 128 token 窗口。
- 标点只允许插入符号，保留识别出的字、简繁、大小写、空格及换行。命令、路径、文件名与引用代码继续受保护。没有大模型改写或云端后处理。
- ASR 4 线程、标点 1 线程。一次比较只校验每个唯一权重一次，校验不计入解码时间；系统文件缓存可能因此预热。
- ASR 与标点模型仍分别创建、释放，不同时常驻多个大模型。
- 正在录音、准备模型或比较时保持屏幕亮；闲置后恢复正常熄屏。

这里先验证现有 CT 的标点效果，**尚未做 CT 词表裁剪、蒸馏或保证准确率提高**。目的是让你决定这条路线是否值得继续轻量化。

## 如何看结果

按住说话，等麦克风就绪后开始，松手依次比较。首次授权后重新按住。最长 60 秒；导入 WAV 要求 16 kHz、单声道、PCM16，最长 60 秒。

每张卡都显示 ASR 原文、最终文本、加载 / ASR / 标点耗时、模型文件空间。重点比较两张 Paraformer 卡：原文应完全相同，差异来自标点。

复用 ASR 的卡明确标注来源及“等效总计”：

```
等效总计 = 原始那次 ASR 的完整耗时 + 本卡实际标点处理时间
```

它不是第二次完整识别的实测用时，不能把复用带来的省时当作 ASR 速度提升。原始识别那张卡显示实际总计；两者的 ASR 数字引用同一次测量。开启轮换顺序时，ASR 来源可能换到另一张卡，复用关系会随结果导出。

“导出”保存音频、结果 JSON 与方案清单。结果格式升级为 2，包含 `asrSourceId`、`asrReused`、`asrTotalMs`、`observedRowMs`、`totalTimeKind` 和标点上下文设置。参考文本可选，不自动给出未经人工标注的 CER / WER。

建议覆盖否定句、连续操作指令、问句和 30～60 秒中英混说。例如：

- 不要删除现有配置如果连接失败就回到主界面并保留错误日志
- 先检查连接超时的原因然后修改代码修改完成后运行测试
- 这个问题是什么原因造成的可以帮我检查一下吗

## 构建与核验

```bash
bash test-audio/tools/build.sh
python3 test-audio/tools/verify_artifacts.py --apk test-audio/outputs/audio-lab-0.2.0-arm64.apk --pack test-audio/outputs/audio-lab-models.zip
```

依赖与原生构建沿用 0.1.0 的固定版本。Release 只包含 ARM64、使用相同本机测试证书。Debug 的软件 x86 模拟器兼容选项不改变 Release 优化。

固定权重的 27 组真实 CT / SRF 推理结果见 `evidence/focused-punctuation-020.json`，实际 Kotlin 重建输出见 `evidence/focused-reconstructed-020.json`。样例用于检查接线和文本保真，不能当成准确率评测或 Android 时延结论。

构建与测试结果见 `evidence/build-checks-020.json`。尚无本版本 ARM64 真机完整验证，最终效果与时延由你在目标手机上的同录音对照决定。

0.1.0 的完整候选清单、来源和历史验证限制见 [旧版说明](README-0.1.0.md)。所有权重来源、文件大小和 SHA-256 仍记录在 `app/src/main/assets/models.json`；许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

主应用现已选择同版本 X-ASR INT8。此目录继续保留历史模型对照；旧主项目的下载/标点评估工具已归档到 `tools/fetch_voice_benchmark.py`、`tools/fetch_voice_punctuation.py`、`tools/evaluate_voice_punctuation.py`，不参与主应用构建。

历史 Whisper 下载缓存已归入 `test-audio/.cache/legacy-whisper/`，主项目不再保留 `.voice-benchmark/` 目录或依赖。
