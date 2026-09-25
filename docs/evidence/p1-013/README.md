# 回复目标回归证据

2026-09-23，Android 15 模拟器，隔离 OpenSSH/tmux 3.4 与合成内容。

- [Android 测试结果](android-tests.xml)：11 项通过，0 失败，0 跳过。
- [核心测试](TEST-dev.mtmux.core.CoreTest.xml) 与 [SSH/tmux 集成测试](TEST-dev.mtmux.core.SshIntegrationTest.xml)：共 12 项通过，无跳过。
- [目标绑定截图](bound-pane-reply.png)：原回复只在左 pane，显式切换后的回复只在右 pane；手机展示绑定目标 %2，输入框已清空。截图已人工检查。

Android 用例通过独立 SSH 控制端切换焦点，确认拦截时两边都未收到、草稿保留；随后恢复发送和显式换目标。核心用例另验证过期服务身份、pane 重建/删除、copy-mode、同步输入、连接失效及 buffer 清理。

最终 debug 编译与 Android lint 通过。实际手机及真实 Agent 仍待验证，详见 [交付说明](../../p1-013.md)。
