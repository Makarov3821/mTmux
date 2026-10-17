# Voice V0 evidence

`emulator-results.json` 是从 `.voice-benchmark/results/` 的两组原始 JSON 整理出的可提交结果：Android 15 x86_64 emulator、JFK 11 秒、4 threads，tiny/base 各 3 次；分别显式 `en` 和 `auto`。已移除随机 run ID、本机绝对路径和墙上时钟，保留每次耗时、RTF、peak RSS、检测语言及完整转写。

供应链、命令、结果解释和限制见 [语音 V0 报告](../../testing/voice-v0-report.md)。该证据只验证 Android native 链路和模拟器相对趋势，不是 ARM 真机或中文质量结论。
