# P1 首批 Android 15 回归证据

版本 0.1.0-dev；环境与测试边界见 [P1 报告](../../releases/0.1.0.md)。以下截图仅含合成数据；测试临时取消 FLAG_SECURE，正式 APK 仍禁止截图。测试清单见 [test-results.txt](test-results.txt)。

- [tmux 会话和窗口名称](360dp/tmux-readable-names.png)
- [连接前已存在的 tmux 历史](360dp/tmux-before-attach-history.png)
- [手指滑动后的本地历史](360dp/shell-touch-history.png)：该用例直接注入合成输出，并未打开 SSH，所以同时显示断开状态。
- [手动断开清空终端、草稿保留](360dp/manual-disconnect-cleared.png)
- [远端断开保留结果并标记历史](360dp/remote-disconnect-retained.png)
- [实际 SSH 命令结果](360dp/ssh-command-result.png)

`480dp/` 保存同组宽窗口截图，`360dp/` 为 720×1600 / density 320 手机布局。界面像素和真实手势测试均通过；这些截图不代表用户手机或真实 Agent 已完成验收。
