# Android 15 终端显示回归

2026-09-23，APK 0.0.3-p0（versionCode 3）。本地 Android 15 / API 35 x86_64 模拟器，KVM，SwiftShader，Android System WebView 124.0.6367.219；SSH 使用隔离 loopback OpenSSH、临时 Ed25519 密钥和合成输出。

## 复现与修复

修复前：原生窗口与网页视口为 464×479，终端容器为 464×0，xterm 为 52×1。原始测试尺寸日志见 [before.txt](before.txt)。与用户的 42×1、已解析 1254 字节但无画面反馈一致。

修复后：容器固定填充视口，不再依赖百分比高度。480dp 窗口为 52×29；360dp 窗口（720×1600，density 320）为 38×29，CSS 视口及容器均为 344×478。行列数随设备、字体及可用空间变化，不固定为此值。

## 验证

`python3 scripts/test_ssh.py --android`：两个用例通过，无跳过。实际截图的文字区域必须存在绿色像素，且容器高度等于视口高度、终端多于 1 行。截图前等待界面与窗口动画完成，避免只验证解析缓冲区或把绿色按钮计作终端输出。

- [连接后尚未输入：欢迎语与提示符](360dp/ssh-welcome-before-input.png)
- [发送后：命令结果与清空的草稿](360dp/ssh-command-result.png)
- [本地中文输出](360dp/local-terminal-output.png)
- [配置面板关闭后](360dp/after-settings.png)
- [软键盘收起后](360dp/after-keyboard.png)

480dp 对应截图保存在 `480dp/`。截图仅使用合成测试服务。测试在绘制之前清除 FLAG_SECURE；产品仍保留防截图保护。

其他检查：4 项 xterm 测试、桌面浏览器页面回归、debug APK 编译、Android lint 通过。手机型号、厂商 WebView、真实服务器与 Agent TUI 仍须用户复测。

APK SHA-256：`1b92c65a2d378433e4f62d9eb4ff1bf1de6d6ce4967801f2aaa0d4fe2768905a`。
