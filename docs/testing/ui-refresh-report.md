# UI 改版实现与验证

2026-10-20，基于 [美化方案](../design/ui-beautification-plan.md) 与 [用户 HTML 模板](../ui-template.html)。本轮实现固定为 0.8.3（versionCode 31），版本记录见 [0.8.3](../releases/0.8.3.md)。

## 实现

- 首页：服务器圆角卡片、任务分层底色、上次使用标签、多 pane 缩进和引导线、统一图标按钮，以及卡片内进度/错误提示和空状态。
- 外观：Tokyo Night、Catppuccin、Emerald、Zinc、Nord、Paper 六套浅深配色；支持跟随系统。既有安装首次迁移保留 Emerald，新安装默认 Tokyo Night。原生页面、WebView 背景、xterm 文字/光标/选区同步；ANSI 采用统一的浅深语义色，不触发重新连接或终端尺寸变更。
- 设置：外观与语言、SSH 连接、终端与输入、隐私/语音、诊断五组。大字体下主题、语言和 SSH 档位换行。
- 编辑：目标信息、认证、高级选项分区，地址与端口同排，跳板条目保留折叠、排序及导入能力。
- 终端：48 dp 顶栏、156 dp 底栏；键帽、带焦点描边的草稿框、发送主按钮、连接状态覆盖层。大字体使用短按钮文字和完整无障碍说明；草稿框减少可见行数以适应固定高度。
- 工具、快捷回复、静态复制、指纹、私钥导入、多行发送确认统一表面与圆角；保留原生选区手柄、敏感页面保护和发送确认。
- voice：麦克风矢量图标，底部面板区分启动、录音、转写、结果和错误。显示真实录音时长/60 秒进度，不做模拟波形；沿用只填草稿、已有草稿选择追加/替换、目标变化拒绝的行为。

## 远端滚动倍率

设置页与终端工具提供同一项 1×～10× 整数偏好。旧的 0.5～2 浮点值就近取整并限制到 1～10；无效值回到 1。调整立即生效并持久保存。

使用同一段滑动的基准步数，按倍率增加真实鼠标滚轮事件；不改 tmux 配置、不增加 SSH 链接/握手/心跳或状态轮询。每帧最多 8 个事件，待发事件最多 80 个，移动距离累积也有界；松手仅补发一个有界帧，随后清空，取消/反向/断线/切换目标同样清理。快速手势可能触及限速，因此倍率不是最终滚动行数的固定承诺。普通 shell 的本地历史滚动不走倍率路径。

## 自动化验证

- standard、voice 的 app 单元测试各 8 项通过，包括六套浅深配色的表面/文字对比度、旧倍率迁移和边界值。
- standard、voice debug APK 构建与 lint 通过；standard Android 测试 APK 编译通过。
- Web Node 测试 5 项通过。
- 真实 Chromium 加载已打包 xterm 页面通过：同一慢滑在 1× / 5× / 10× 分别产生 3 / 15 / 30 个滚轮事件；覆盖上下方向、鼠标协议、松手停止、目标切换、断线旧 token、UTF-8、粘贴、复制及主题切换保持网格与文字。
- 当前环境 Chrome 对本地 HTTP 回环返回访问拒绝；使用 `TERMINAL_TEST_FILE=1 npm --prefix web run test:browser` 加载同一份打包文件完成测试。默认 HTTP 测试路径保持可用。

复现本地构建：

```bash
JAVA_HOME="$PWD/.tools/jdk-17.0.20.1+1" \
GRADLE_USER_HOME="$PWD/.tools/gradle-home" \
ANDROID_USER_HOME="$PWD/.tools/android-user" \
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  :app:testStandardDebugUnitTest :app:testVoiceDebugUnitTest \
  :app:assembleStandardDebug :app:assembleVoiceDebug \
  :app:assembleStandardDebugAndroidTest \
  :app:lintStandardDebug :app:lintVoiceDebug --console=plain --max-workers=2
```

## Android 页面验证

新增 `UiRefreshTest` 覆盖卡片/多 pane、六套主题选中与持久化、两处倍率入口及终端键盘开关保持逻辑网格，并生成截图。既有测试更新固定设置栏返回按钮与终端短文案定位，主题回归明确选择 Emerald。

Android 35 ATD、360×800 dp、软件模拟器：已取得并人工检查 [首页](../evidence/ui-refresh/home-tokyo.png)、[多 pane 展开](../evidence/ui-refresh/home-panes-tokyo.png)、[深色设置](../evidence/ui-refresh/settings-tokyo.png)，未发现文字或操作溢出。

ATD 默认 `debug.hwui.drawing_enabled=0` 会输出纯黑图；改为 1 后才取得上述真实截图，黑图已丢弃。首轮整组 UI 测试被系统 WebView 124 渲染进程 SIGTRAP 中断；开启绘制后的主题用例和单独的终端用例也未能完整结束，均未计为通过。终端用例尝试在模拟器中临时关闭 WebView JIT/GPU 后仍异常退出；这些调试开关已清理，应用代码不包含这些开关。新增截图检查会拒绝纯黑图片。

本机无 KVM，不能将软件模拟器的启动/交互耗时作为手机性能数据。ARM 真机上的中文显示、大字体/412 dp、voice 录音转写和真实远端 TUI 手感仍需实机复测。

汇总结果保存在 [验证记录](../evidence/ui-refresh/validation.txt)。

## 本地 APK

两个 debug APK 均通过 APK Signature Scheme v2 验签。

| 文件 | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/standard/debug/app-standard-debug.apk` | `8a3821c6fb1582730bb71b587d9770bd1a1d9de08c084b755bf9498607af8f34` |
| `app/build/outputs/apk/voice/debug/app-voice-debug.apk` | `cda74cf16d10801af07290836010630b1fed9e7aaa16df7b307f8624cc0d23bc` |
