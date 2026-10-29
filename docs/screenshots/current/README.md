# 当前程序界面图集

代码快照：`c6a672b`（0.8.5 后续已提交改动）。

共 94 张 Android 原生视图快照。打开 [离线浏览图集](index.html)，支持按界面类别、采集类型和关键词筛选，点击放大。也可直接查看 [Markdown 缩略图](gallery.md)。

所有地址使用文档保留网段，口令、指纹、终端输出均为演示数据。未读取或连接真实服务器，未使用真实私钥，语音状态不启动录音或识别。

## 采集类型

- **实际程序界面**：当前代码中的页面、菜单、弹窗及交互结果，部分终端状态通过测试数据固定。
- **原生组件固定状态**：直接调用当前生产组件，例如录音光晕，以演示状态固定倒计时。
- **原生界面＋离线终端画面**：真实 MainActivity 原生控件和弹窗；终端输出由同版本 xterm.js 在桌面 Chromium 离线渲染后注入视图。软件模拟器 WebView 无法稳定工作，不能将这些图当作 Android WebView 运行验证。
- **瞬时分支预览**：网络验证、创建进度与语音失败等短暂分支，用当前资源文案和 Android Material 控件复现；不冒充已实际执行的认证或转写。

采集方式为 Android `View.draw(Canvas)`，由实际 Compose/View 树生成，包含应用内弹窗。它与系统整屏截屏不同：不包含其他进程绘制的状态栏图标、系统键盘、权限控制器或文件选择器。受保护页面仅使用专用测试设备中的演示数据；截图测试操作不打入 Release，也不改变正式应用的截图保护。

应用没有独立账户登录页面；SSH 登录表单见“服务器配置”。这里覆盖独立页面、菜单、弹窗和主要状态，不穷举语言、字号、主题、服务器数量及所有错误文案的组合。

界面分支对照与覆盖边界见 [coverage.md](coverage.md)，本次检查见 [verification.md](verification.md)。

## 分类索引

| 类别 | 图片数 |
| --- | ---: |
| 主界面与分组 | 26 |
| SSH 登录与服务器配置 | 13 |
| 设置 | 6 |
| 终端与输入 | 25 |
| 语音状态 | 14 |
| 验证与确认分支 | 10 |

## 截图清单

| 界面 | 类型 | 图片 |
| --- | --- | --- |
| 首次打开：空服务器列表 | 实际程序界面 | [打开](home/01-empty.png) |
| 主界面：分组、服务器、任务状态 | 实际程序界面 | [打开](home/02-populated.png) |
| 服务器排序菜单 | 实际程序界面 | [打开](home/03-sort-menu.png) |
| 本地搜索：匹配任务 | 实际程序界面 | [打开](home/04-search-results.png) |
| 搜索无结果 | 实际程序界面 | [打开](home/05-search-empty.png) |
| 分组折叠 | 实际程序界面 | [打开](home/06-folder-collapsed.png) |
| 新建分组弹窗 | 实际程序界面 | [打开](home/07-folder-new.png) |
| 服务器更多操作 | 实际程序界面 | [打开](home/08-server-menu.png) |
| 主界面：浅色 | 实际程序界面 | [打开](home/09-light.png) |
| 分组操作菜单 | 实际程序界面 | [打开](home/10-folder-menu.png) |
| 重命名分组 | 实际程序界面 | [打开](home/11-folder-rename.png) |
| 删除分组确认 | 实际程序界面 | [打开](home/12-folder-delete.png) |
| 移动服务器到分组 | 实际程序界面 | [打开](home/13-move-server.png) |
| 删除服务器确认 | 实际程序界面 | [打开](home/14-delete-server.png) |
| 服务器任务列表折叠 | 实际程序界面 | [打开](home/15-server-collapsed.png) |
| 同一 tmux 窗口包含多个 pane | 实际程序界面 | [打开](home/16-multiple-panes.png) |
| 展开窗口：逐个选择 pane | 实际程序界面 | [打开](home/17-panes-expanded.png) |
| 任务状态：灰色未知 / 已过期 | 实际程序界面 | [打开](home/18-unknown-states.png) |
| 最近使用的任务标记 | 实际程序界面 | [打开](home/19-last-used.png) |
| 新建分组：重名校验 | 实际程序界面 | [打开](home/20-folder-duplicate.png) |
| 主界面主题：CATPPUCCIN | 实际程序界面 | [打开](home/theme-catppuccin.png) |
| 主界面主题：EMERALD | 实际程序界面 | [打开](home/theme-emerald.png) |
| 主界面主题：NORD | 实际程序界面 | [打开](home/theme-nord.png) |
| 主界面主题：PAPER | 实际程序界面 | [打开](home/theme-paper.png) |
| 主界面主题：TOKYO | 实际程序界面 | [打开](home/theme-tokyo.png) |
| 主界面主题：ZINC | 实际程序界面 | [打开](home/theme-zinc.png) |
| 添加服务器：私钥认证 | 实际程序界面 | [打开](server/01-add-key.png) |
| 添加服务器：密码认证 | 实际程序界面 | [打开](server/02-add-password.png) |
| 编辑服务器：密码认证 | 实际程序界面 | [打开](server/03-edit-password.png) |
| 高级设置：分组、Jump Host、tmux 路径与鼠标 | 实际程序界面 | [打开](server/04-advanced.png) |
| 服务器所属分组菜单 | 实际程序界面 | [打开](server/05-folder-picker.png) |
| 保存校验失败：目标信息不完整 | 实际程序界面 | [打开](server/06-validation.png) |
| 放弃未保存的修改 | 实际程序界面 | [打开](server/07-abandon.png) |
| 粘贴 SSH 私钥 | 实际程序界面 | [打开](server/08-key-paste.png) |
| 从已保存服务器导入跳板 | 实际程序界面 | [打开](server/09-import-jump.png) |
| Jump Host 认证与路由编辑 | 实际程序界面 | [打开](server/10-jump-expanded.png) |
| 新建独立命名 tmux 会话 | 实际程序界面 | [打开](server/11-new-tmux.png) |
| 新会话名称不合法 | 实际程序界面 | [打开](server/12-new-tmux-invalid.png) |
| 粘贴私钥：格式错误提示 | 实际程序界面 | [打开](server/13-private-key-invalid.png) |
| 设置：外观、主题与语言 | 实际程序界面 | [打开](settings/01-top.png) |
| 设置：SSH 保留、终端显示与滚动倍率 | 实际程序界面 | [打开](settings/02-terminal.png) |
| 设置：隐私与本地语音 | 实际程序界面 | [打开](settings/03-privacy-voice.png) |
| 设置：诊断日志导出与清除 | 实际程序界面 | [打开](settings/04-logs.png) |
| 设置：浅色主题 | 实际程序界面 | [打开](settings/05-light.png) |
| 诊断日志清除结果 | 实际程序界面 | [打开](settings/06-log-cleared.png) |
| 终端：Agent 界面与直接输入 | 原生界面＋离线终端画面 | [打开](terminal/01-agent.png) |
| 终端：Ctrl + Shift 粘滞等待 | 原生界面＋离线终端画面 | [打开](terminal/02-sticky.png) |
| 终端：语音/长文本编辑卡片 | 原生界面＋离线终端画面 | [打开](terminal/03-editor.png) |
| 编辑卡片：键盘避让布局 | 原生界面＋离线终端画面 | [打开](terminal/04-editor-keyboard.png) |
| 终端直接输入：键盘避让布局 | 原生界面＋离线终端画面 | [打开](terminal/05-keyboard.png) |
| 终端工具：回复目标与输入 | 原生界面＋离线终端画面 | [打开](terminal/06-tools-top.png) |
| 终端工具：滚动、字号与复制 | 原生界面＋离线终端画面 | [打开](terminal/07-tools-display.png) |
| 终端文字静态副本与复制 | 原生组件固定状态 | [打开](terminal/08-copy.png) |
| 快捷回复列表 | 原生界面＋离线终端画面 | [打开](terminal/09-replies.png) |
| 添加快捷回复 | 原生界面＋离线终端画面 | [打开](terminal/10-reply-add.png) |
| 编辑/删除快捷回复 | 原生界面＋离线终端画面 | [打开](terminal/11-reply-edit.png) |
| 终端连接详细信息 | 原生界面＋离线终端画面 | [打开](terminal/12-details.png) |
| 终端：连接准备中 | 原生界面＋离线终端画面 | [打开](terminal/13-connecting.png) |
| 终端：认证失败 | 原生界面＋离线终端画面 | [打开](terminal/14-auth-failed.png) |
| 终端：断线后的历史与重连 | 原生界面＋离线终端画面 | [打开](terminal/15-disconnected.png) |
| 终端：回复目标校验拦截 | 原生界面＋离线终端画面 | [打开](terminal/16-pane-blocked.png) |
| 终端：浅色主题 | 原生界面＋离线终端画面 | [打开](terminal/17-light.png) |
| 终端：首次指纹确认 | 原生界面＋离线终端画面 | [打开](terminal/18-host-first.png) |
| 终端：主机密钥变化拦截 | 原生界面＋离线终端画面 | [打开](terminal/19-host-changed.png) |
| 复制窗口：暂无终端文字 | 原生组件固定状态 | [打开](terminal/20-copy-empty.png) |
| 复制窗口：内容裁剪提示 | 原生组件固定状态 | [打开](terminal/21-copy-clipped.png) |
| 普通版终端：没有语音按钮的底栏 | 原生组件固定状态 | [打开](terminal/22-standard-controls.png) |
| F1–F6 快捷键 | 原生界面＋离线终端画面 | [打开](terminal/keys-1.png) |
| F7–F12 快捷键 | 原生界面＋离线终端画面 | [打开](terminal/keys-2.png) |
| Ctrl / Shift / Alt 与编辑键 | 原生界面＋离线终端画面 | [打开](terminal/keys-3.png) |
| 语音：麦克风准备中 | 原生组件固定状态 | [打开](voice/01-preparing.png) |
| 语音：录音与绿色倒计时 | 原生组件固定状态 | [打开](voice/02-recording.png) |
| 语音：最后十秒变橙 | 原生组件固定状态 | [打开](voice/03-last-ten.png) |
| 语音：即将结束变红 | 原生组件固定状态 | [打开](voice/04-last-five.png) |
| 语音：上滑松手取消 | 原生组件固定状态 | [打开](voice/05-cancel.png) |
| 语音：本机转写中 | 瞬时分支预览 | [打开](voice/06-transcribing.png) |
| 语音录音：浅色主题 | 原生组件固定状态 | [打开](voice/07-light-recording.png) |
| 麦克风权限被拒绝，仍可继续键盘输入。 | 瞬时分支预览 | [打开](voice/error-1.png) |
| 无法启动或读取麦克风，请检查是否被其他应用占用。 | 瞬时分支预览 | [打开](voice/error-2.png) |
| 录音太短，请至少说 0.1 秒。 | 瞬时分支预览 | [打开](voice/error-3.png) |
| 未识别到语音，草稿没有改变。 | 瞬时分支预览 | [打开](voice/error-4.png) |
| 内置 Whisper 模型缺失或损坏，请重新安装 voice APK。 | 瞬时分支预览 | [打开](voice/error-5.png) |
| 连接或回复目标已改变，语音文字未填入。 | 瞬时分支预览 | [打开](voice/error-6.png) |
| 语音转写失败，原草稿没有改变。 | 瞬时分支预览 | [打开](voice/error-7.png) |
| 核对主机指纹 | 瞬时分支预览 | [打开](dialogs/01-host-first.png) |
| 主机密钥已改变 | 瞬时分支预览 | [打开](dialogs/02-host-changed.png) |
| 验证并保存服务器 | 瞬时分支预览 | [打开](dialogs/03-server-checking.png) |
| 核对主机指纹 | 瞬时分支预览 | [打开](dialogs/04-server-fingerprint.png) |
| 核对主机指纹 | 瞬时分支预览 | [打开](dialogs/05-jump-fingerprint.png) |
| 服务器验证失败，尚未保存 | 瞬时分支预览 | [打开](dialogs/06-save-anyway.png) |
| 正在创建 tmux 会话… | 瞬时分支预览 | [打开](dialogs/07-tmux-creating.png) |
| 已存在同名 tmux 会话，请使用其他名称 | 瞬时分支预览 | [打开](dialogs/08-tmux-duplicate.png) |
| 无法确认 tmux 会话创建结果，请刷新列表检查后重试 | 瞬时分支预览 | [打开](dialogs/09-tmux-uncertain.png) |
| 快捷回复：追加、替换或取消 | 瞬时分支预览 | [打开](dialogs/10-reply-conflict.png) |

## 复现

测试驱动：[UiCatalogTest.kt](../../../app/src/androidTestVoice/java/dev/mtmux/UiCatalogTest.kt)。只放在 `androidTestVoice` 中。

1. 先在 `web/` 执行 `node render-catalog-terminal.mjs` 生成离线终端素材，再构建当前 voice 调试 APK 和 Android 测试 APK，安装到空白专用 Android 15 测试模拟器。驱动只允许 debug 模拟器和 catalog 演示配置，默认在正常回归测试中跳过。
2. 执行 `adb shell am instrument -w -e class dev.mtmux.UiCatalogTest,dev.mtmux.UiTerminalCatalogTest -e uiCatalog true dev.mtmux.test/androidx.test.runner.AndroidJUnitRunner`。
3. 从 `sdcard/Android/data/dev.mtmux/files/ui-catalog` 拉取图片。
4. 执行 `python3 scripts/build_ui_catalog.py /path/to/ui-catalog`，然后在 `web/` 执行 `node check-ui-catalog.mjs` 验证离线图片和浏览交互。

可用 `-e catalogGroup home|server|settings|voice|dialogs|extras（UiCatalogTest）；terminal（UiTerminalCatalogTest）` 分组补采；`-e catalogSmoke true` 仅采集首页空态和列表态。软件模拟器无 KVM 时，需要给系统服务足够的超时预算。

旧的 116 张程序截图/界面 PNG 已删除；日志、测试数据与 SVG 图标保留。删除清单见 [removed-legacy-images.json](removed-legacy-images.json)，可从 Git 提交 `c6a672b` 恢复历史文件。
