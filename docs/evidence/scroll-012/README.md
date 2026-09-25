# 连续触摸滚动证据

Android 15 模拟器，2026-09-23；合成数据与独立 tmux 测试 socket。

- [修复前](before.txt)：文字区域开始慢拖，仅收到 touchstart，历史位置停在 0。
- [修复后](after.txt)：同一手势分三段持续移动，历史位置为 5、10、20；包含事件追踪。
- [Android 测试结果](android-tests.xml)：10 项通过，无失败或跳过。
- [测试摘要](test-summary.txt)。
- [历史屏幕](live-tmux-touch-history.png)：慢拖后 `[20/137]` 与对应历史内容。
- [点击 pane](live-tmux-tap-pane.png)：滚动后点击切换 pane 的屏幕。

细节和限制见 [交付报告](../../p1-012.md)。
