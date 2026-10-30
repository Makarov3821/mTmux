# 本地程序截图

截图 PNG、索引 HTML、manifest 和临时采集输出均为本地产物，由 `.gitignore` 排除；此说明和源码中的采集驱动继续纳入 Git。历史已跟踪图片在 0.9.1 中停止跟踪，本地文件保留。

当前图集：`docs/screenshots/current/index.html`。包含主页、服务器配置、设置、终端与输入、语音状态、验证弹窗及 tmux Doctor。生产组件固定状态和离线 xterm 合成图逐张标注，不作为真实 SSH、录音或 Android WebView 验证。

复现流程：

1. 在 `web/` 执行 `node render-catalog-terminal.mjs`。
2. 构建当前 voiceDebug 和 voiceDebugAndroidTest，安装到专用 Android 15 调试模拟器。仅使用 catalog 演示配置，驱动会拒绝覆盖非 catalog 服务器。
3. `adb shell am instrument -w -e uiCatalog true -e class dev.mtmux.UiCatalogTest,dev.mtmux.UiTerminalCatalogTest dev.mtmux.test/androidx.test.runner.AndroidJUnitRunner`
   补采最小配置预览与撤销确认：`adb shell am instrument -w -e uiCatalog true -e catalogGroup doctor-config -e class dev.mtmux.UiCatalogTest#captureDoctorConfig dev.mtmux.test/androidx.test.runner.AndroidJUnitRunner`。
4. 拉取 `sdcard/Android/data/dev.mtmux/files/ui-catalog` 到本地临时目录。
5. `python3 scripts/build_ui_catalog.py /path/to/ui-catalog`
6. 在 `web/` 执行 `node check-ui-catalog.mjs`，检查图片加载、筛选和放大。

测试驱动 `app/src/androidTestVoice/java/dev/mtmux/UiCatalogTest.kt` 仅进入测试 APK，不改变正式版截图保护。采集方法是原生 View.draw(Canvas)，不包含系统状态栏图标或其他进程的键盘/权限弹窗。
