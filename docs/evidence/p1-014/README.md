# 最近任务与安全重连验证

2026-09-24，Android 15 模拟器，临时 OpenSSH 服务和独立 tmux 3.4 socket。

- [Android 全量回归](android-full.xml)：13 项通过，无失败、无跳过。
- [最终存储与恢复回归](android-recent-final.xml)：最近记录去重/旧服务清理后的两项针对性回归通过。
- [核心测试](TEST-dev.mtmux.core.CoreTest.xml) 与 [SSH/tmux 集成测试](TEST-dev.mtmux.core.SshIntegrationTest.xml)：共 14 项通过，无跳过，包含真实服务重启且名称/ID 复用场景。
- [最近任务入口](recent-task-entry.png)：重建 Activity 后仍显示已保存任务，旧 pane 记录已去重。
- [旧任务拒绝恢复](recent-task-stale-blocked.png)：提示重新选择，未连接，草稿仍在。两张截图已人工核对实际显示。
- 最终 APK 编译和 Android lint 通过。页面截图使用合成内容，测试进程对重建 Activity 关闭防截图；正式 APK 保留防截图。

实际手机网络切换、真实 Agent 版本和跨 Android 兼容性仍需验证。详见 [交付报告](../../p1-014.md)。
