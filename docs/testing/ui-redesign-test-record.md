# 界面改版后的 Android 测试整改记录

2026-10-31。对应提交：改版 `795085c`，本次整改为其后的提交。流程按 [WebView 测试与发布操作说明](webview-and-release-runbook.md)。

## 环境

| 项 | 值 |
| --- | --- |
| 模拟器 | `mtmux-test`：Android 15（API 35）google_apis x86_64，KVM 硬件加速，720×1600 / density 320 |
| WebView | com.google.android.webview 124.0.6367.219 |
| 输入法 | Gboard（com.google.android.inputmethod.latin） |
| SSH 环境 | `scripts/test_ssh.py --android`：临时 loopback sshd 和独立 tmux socket，逐个测试类执行 |

之前用的 `mtmux-catalog` 是给截图图集准备的 ATD 镜像，默认关闭绘制，没有 KVM 时还会出现 WebView renderer SIGTRAP，不适合跑回归测试。

## 改版前后对照（整改前）

在同一台模拟器上，用同样的方式跑 standard 全套：

| 代码 | 通过 | 失败 |
| --- | ---: | ---: |
| 原代码 `8b1d212`（0.9.1） | 51 | 8 |
| 改版 `795085c` | 52 | 7 |

两边失败的是同一批，改版没有引入新的失败。原代码多出的那 1 项是 `encryptedPrivateKeysRestoreAndConnectWithOneClick` 偶发超时。全套界面测试上次通过是在 0.8.0，0.8.1 之后的版本都只跑了定向测试，这批失败是在这期间积累下来的。

## 失败原因与处理

| 测试 | 原因 | 处理 |
| --- | --- | --- |
| `TerminalUiTest` 中 3 项卡在 `hideKeyboard` | 编辑卡片是底部面板，在单独的窗口里；测试只向主窗口请求收起键盘 | 测试改为向应用的所有窗口请求收起，并在所有窗口上确认键盘已收起 |
| `remoteDisconnectPreservesOutputAndMarksItAsHistory` | 插入成功后编辑卡片会自动关闭，“插入终端”按钮跟着消失 | 测试改为断线后重新打开编辑卡片，再确认不能向已断开的连接插入 |
| `confirmingTheFixtureFingerprintFinishesSavingAndReturnsHome` | 终端页一直留在服务器列表下层，`terminal-home` 节点始终存在 | 测试改为直接检查当前显示的是服务器列表，不再判断终端节点是否存在 |
| `namedSessionOpensItsNewTerminalAndDuplicateStaysInTheDialog` | **产品问题**：新建会话成功后，打开终端的回调可能在后台线程执行，触发 WebView 线程检查，被当成“凭据无法读取” | `NewTmuxDialog` 改为在主线程打开终端 |
| `UiRefreshTest#terminalDockToolsAndKeyboardKeepTheLogicalGrid` | 还在找 0.8.5 已经去掉的底部输入框 | 改成现在的流程：点键盘按钮直接输入，长文本用编辑卡片，两种情况下都检查终端行列不变 |
| 同上（改写后才测出来） | **产品问题**：直接输入时窗口按 `adjustResize` 缩小，终端从 37 行变成 17 行，会触发远端 resize。原代码同样存在 | `MainActivity` 在键盘弹出期间沿用键盘弹出前的高度；旋转屏幕或改变宽度时重新计算 |

另外，随新界面调整了 4 处定位方式：英文版的取消按钮改用 `cancel-edit` 标签查找；快捷键改为检查“六键等宽并铺满整行”；任务行的会话名和窗口名改为分别查找；指纹分组显示后，读屏和测试拿到的仍是完整指纹。

## 新增测试

`UiRedesignTest` / `UiRedesignComponentTest`，共 7 项，不需要 SSH 环境：

- 首页“等待回复 / 运行中”数量和状态点一致，没有需要处理的任务时标签隐藏；卡片按钮顺序是终端、新建、刷新、⋮；⋮ 面板里没有“新建会话”。
- tmux 不可用时只禁用新建按钮，终端和刷新仍可用。
- 连接中的步骤卡显示每一步的耗时，失败时标出失败的那一级，按钮换成“重试 / 返回首页”。
- 断线卡片只有“重连”；翻看历史时缩成胶囊，回到底部后恢复。
- Doctor 报告的问题项排在正常项前面，正常项默认收起，点“展开”后才显示原因。
- 指纹分组显示，读屏和测试拿到的仍是完整指纹。
- 语音手指离开按压区即进入取消态，移回即恢复；在取消态松手会丢弃录音。

为确认这些测试能发现问题，临时改坏了两处：交换卡片上两个按钮的顺序、去掉胶囊。两次都有对应测试失败，之后已改回。

## 整改后全套结果

| 版本 | 通过 | 失败 |
| --- | ---: | ---: |
| standard（66 项） | 65 | 1 |
| voice（72 项，含 `VoiceProductTest`） | 72 | 0 |

standard 失败的那项是 `taskStatusDiscoveryReadsExactPaneWithoutChangingFocus`，它只操作远端 tmux，不涉及界面；之后单独连续跑 3 次都通过，判断为偶发。其余检查：两个版本的单元测试、lint、构建，以及 `npm --prefix web test` 都通过。

原始结果在 `.tools/diagnostics/matrix/`（base-standard、new-standard、fix-standard、fix-voice），已被 Git 忽略。运行和汇总脚本见 `scripts/run_android_matrix.sh`、`scripts/summarize_android_results.py`，固定流程见 [改动、测试与发布流程](ui-redesign-change-and-release-routine.md)。

## 0.9.2 发布前复跑

补上语音进出场和转写过渡后，重启模拟器并复跑全套：voice 72/72；standard 65/66，失败的 `encryptedPrivateKeysRestoreAndConnectWithOneClick` 单独重跑通过。模拟器连续运行数小时后，有两项会稳定超时；改动前的提交在同一状态下同样失败，重启后恢复，因此判断为模拟器状态问题。动画用暂停时钟逐帧截图检查过，帧图没有提交。

## 0.9.2 真机反馈修复（problems/1）

| 问题 | 原因 | 修复 | 测试 |
| --- | --- | --- | --- |
| 首页状态总览刷新后计数变少或消失 | 总览的“当前时间”只在每分钟刷新一次；新快照的采样时间晚于它，被当成未来时间判为灰色 | 快照变化时重新取时间，与卡片状态点同一规则 | `UiRedesignTest#summaryKeepsCountingTasksAfterARefresh`（真实 tmux，刷新两次） |
| 任务行 `>` 参差不齐 | 名称和占位 Spacer 各占一份剩余宽度 | 名称、窗口、标签放进同一个占满剩余宽度的 Row | `UiRefreshTest#homeRowsAlignAndTerminalFillsTheWidth` |
| 服务器标题比状态点更靠左 | 标题左内边距 14dp，任务行 16dp | 统一为 16dp | 同上 |
| 终端右侧空白 | xterm 5.5 测得滚动条宽度为 0 时按 15px 预留；Android 是悬浮滚动条 | 仅在滚动条确实不占宽度时取消预留 | 同上：空白须小于一个字符宽 |
| 按键与工具栏太挤 | — | 快捷键区加高 6dp（约 1mm），底栏 100→106dp，终端高度同步扣除 | 截图核对 |
| 翻页指示一次亮两个点 | 高亮条 10dp 宽，盖住两个 3dp 的点 | 改成一个点大小 | 截图核对 |
| 设置分段选项换行时其他选项偏上 | 各选项高度不同，按顶部对齐 | 所有选项取同一高度并居中 | `UiRedesignComponentTest#segmentedChoiceKeepsOptionsCenteredWhenOneLabelWraps` |

逐项把修复改回后，新增的检查都会失败（例如终端：314/336px，空白 22px，大于字符宽 8.5px）。全套：standard 68/69，voice 75/75；失败的 `TmuxDoctorUiTest#offlineDiagnosis…` 在未改动的 HEAD 上同样稳定失败，原因另查。

## 0.9.3-rc.1 真机反馈二次调整

- 首页服务器标题左边距 18dp（比状态点右 2dp，测试同步）。
- 终端顶栏：服务器名 14→16sp，任务名 11→13sp，高度 48→54dp。
- 底栏：工具/快捷回复图标 22dp、文字 16sp，约为 52dp 语音按钮的 70%；语音按钮居中，两侧各自居中对称。
- 快捷键：文字 14→11sp，去掉键帽底色，仅锁定的修饰键保留高亮；按键与下方间距 10dp，翻页点在这段间距内。底栏 106→102dp。
- 全套：standard 68/69，voice 74/75。失败的 `encryptedPrivateKeysRestoreAndConnectWithOneClick`（等待第 0 行出现 SSH 欢迎语超时）在未改动的 HEAD 上也失败，重启模拟器后仍失败，原因另查。

## 仍未覆盖

- 语音弧形区、气泡、按钮的视觉过渡，以及真实麦克风录音。
- ARM 真机上的手感、动画，以及厂商输入法的表现。
- 截图图集还没重新生成到 `docs/screenshots/current/`。
