# 0.9.2 界面改版：改动、测试与发布流程

2026-10-31。本文记录 0.9.2 这次改动做了什么、怎么测的，并把测试和发布的做法固定成以后每次都照着走的流程。逐项失败原因见 [界面改版测试记录](ui-redesign-test-record.md)。另一份 [WebView 测试与发布操作说明](webview-and-release-runbook.md) 由其他 agent 整理，之后合并；两者冲突时以更严格的那条为准。

## 1. 本次改动

| 提交 | 内容 |
| --- | --- |
| `795085c` feat | 界面改版：共用组件、首页、终端、语音、验证/指纹、新建会话、Doctor、设置等 |
| `3d9f39f` test | 修复过时的 Android 测试，新增 7 项界面测试，两项产品修复 |
| `91e8ff4` release | 语音浮层进出场与转写过渡；版本 0.9.2（versionCode 38），标签 `v0.9.2` 预发行 |

### 设计来源

`docs/plan-ui/` 是设计稿：三套方案（A/B/C）、A 第二版 `a2-refined.html`，以及 Doctor 与 0.9 新增界面 `a2-doctor.html`。渲染出的 PNG 不入库，重新生成用 `node docs/plan-ui/shared/render.mjs`。设计过程中用户确认过的决定：

- 沿用方案 A：深色、圆角卡片、以终端为主。首页标题用 “mTmux”。
- 服务器卡片右侧从左到右：终端、新建、刷新、⋮，前三个常驻在卡片上；⋮ 里只放低频操作（Doctor、编辑、指纹、移动、删除）。
- 首页不显示 pane 行和“已就绪”文字；这个阶段不支持多 pane。
- 主机指纹的核对放在添加服务器的验证流程里。
- 断线时只给“重连”，不提供“重新选择任务”（想换任务就直接返回首页）。翻看历史时卡片缩成胶囊。
- 语音：按住说话，松开后转成文字填进编辑卡片，不自动发送。手指往任意方向上滑、离开按压区就算取消，没有单独的取消按钮。

### 代码结构

- `UiComponents.kt`：共用的 `AppDialog`、`AppSheet`、`SheetAction`、`StepRow`、`RouteChips`、`FloatingCard`、`NoticeStrip`、`FingerprintBlock`、`SegmentedChoice`、`SettingRow`、`StatusColors`、`BrandMark`。图标路径和 `docs/plan-ui/shared/icons.js` 是同一套。
- 页面：`ServerHome.kt`、`MainActivity.kt`（终端顶栏、阶段卡、断线卡片和胶囊）、`ServerVerification.kt`、`NewTmuxDialog.kt`、`TmuxDoctorDialog.kt`、`AppSettings.kt` 等，改成调用上面这些组件。
- 语音：`VoiceHoldButton.kt`（浮层、按压区、气泡、进出场）、`VoiceIntegration.kt`（转写气泡、错误提示），`VoiceRecorder.level` 只给界面显示电平用。
- 测试标签（testTag）原样保留；新增 `home-title`、`settings-back`、`cancel-edit`、`connection-card`、`cancel-connect`、`disconnected-pill`。

### 产品修复（测试整改中发现，原代码也有）

1. 直接输入时弹出键盘，窗口被 `adjustResize` 缩小，终端从 37 行变成 17 行，并触发远端 tmux resize。修复：键盘弹出期间沿用键盘弹出前的高度。
2. 新建会话成功后，打开终端的回调可能在后台线程执行，触发 WebView 线程检查，被误报为“凭据无法读取”。修复：在主线程打开终端。

## 2. 这次实际做了哪些测试

| 层级 | 命令 / 方式 | 结果 |
| --- | --- | --- |
| JVM 单测 | `:core:test :voice:testDebugUnitTest :app:testStandardDebugUnitTest :app:testVoiceDebugUnitTest` | 通过 |
| 构建 + lint | `:app:assemble{Standard,Voice}Debug`、`:app:lint{Standard,Voice}Debug` | 通过 |
| 终端页面 | `npm --prefix web test` | 5/5 通过（终端资源未改） |
| 宿主集成 | `scripts/test_tmux.py`、`scripts/test_ssh.py`、`scripts/tests` | 通过 |
| Android 界面 | `scripts/run_android_matrix.sh`，带 SSH 环境逐类运行 | voice 72/72；standard 65/66（1 项偶发，单独重跑通过） |
| 原代码对照 | 用 `git worktree` 检出 `8b1d212`，在同一台模拟器上跑全套 | 51/8，失败的和改版后是同一批 → 改版没有引入回归 |
| 测试有效性 | 临时改坏两处（交换卡片按钮顺序、去掉胶囊） | 对应测试都失败，已改回 |
| 动画 | 暂停 Compose 时钟后逐帧截图（临时测试，不入库） | 进场、退场、转写过渡正常 |
| 截图图集 | `UiCatalogTest` 各组 | 101 张，人工逐类检查 |
| GitHub CI | Android checks、Voice checks、Release APK | main 与 v0.9.2 全部成功 |

### 走过的弯路（不要再犯）

- 一开始用截图专用的 `mtmux-catalog` ATD 镜像跑回归。这台默认关闭绘制，没有 KVM 时 WebView renderer 还会崩，导致一大批无关的失败。**回归测试一律用 `mtmux-test`。**
- 一次把整套 Android 测试跑完：前面一项崩溃后，后面的用例要么没执行，要么接连失败，分不清原因。**改为逐类运行，每类单独保存结果。**
- 直接跑 Gradle、没有带 SSH 环境：15 项被 assume 跳过，而改动最多的连接和断线部分实际没测到。**界面测试一律通过 `test_ssh.py --android` 运行。**
- 用 grep 匹配 Gradle 输出来判断结果，把 “Task ... SKIPPED” 误当成测试跳过。**结果只看 `test-results.log`，用汇总脚本读。**
- “原代码也失败”只对照过 1 项就下了结论。**必须对同一批用例在原代码上跑过，才能说是旧问题。**
- 模拟器连续运行数小时后，有两项稳定超时；重启后恢复。**结果和预期不符时，先重启模拟器再跑一遍。**

## 3. 固定流程

每次做功能或界面改动都按这个顺序走。命令都在仓库根目录执行，环境变量见 [WebView 测试说明](webview-and-release-runbook.md#本机构建环境)。

### 3.1 开始之前

1. 确认工作区：`git status`。别人留下的未提交文件不要动，也不要一起提交。
2. 确认用户能访问 KVM：`id -nG | grep kvm`，以及 `.tools/android-sdk/emulator/emulator -accel-check`。
3. 启动回归用的模拟器（不要用 `mtmux-catalog`）：
   ```bash
   .tools/android-sdk/emulator/emulator -avd mtmux-test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save &
   export ANDROID_SERIAL=emulator-5554
   bash scripts/collect_webview_diagnostics.sh   # 记录 WebView 版本、绘制设置等环境信息
   ```
   如果之前异常退出、留下了 `.tools/avd/mtmux-test.avd/*.lock`，先确认没有模拟器进程在跑，再删除这些锁文件。
4. 记录基线：改动前先跑一次 `scripts/run_android_matrix.sh standard base-<日期>`。如果已经开始改，就用 `git worktree add /tmp/base <起点提交>` 检出原代码，再以 `/tmp/base` 作为 repo 参数运行，见 3.3。

### 3.2 开发中

- 界面改动先出设计稿（`docs/plan-ui/`），用户确认后再写代码。
- 测试标签只增不删；用户能看到的文字变了，同步修改用这些文字定位的测试。
- 每完成一块，先编译：`./gradlew :app:compileVoiceDebugKotlin :app:compileStandardDebugKotlin`。
- 单个测试类调试：
  ```bash
  python3 scripts/test_ssh.py --android --android-variant standard --test-class 'dev.mtmux.TerminalUiTest#<方法名>'
  ```
- 新功能顺带加测试，加完把实现临时改坏一处，确认测试会失败，再改回。

### 3.3 提交前

```bash
./gradlew --offline :core:test :voice:testDebugUnitTest :app:testStandardDebugUnitTest :app:testVoiceDebugUnitTest \
  :app:assembleStandardDebug :app:assembleVoiceDebug :app:assembleStandardDebugAndroidTest :app:assembleVoiceDebugAndroidTest \
  :app:lintStandardDebug :app:lintVoiceDebug
npm --prefix web test                      # 改了 web/ 时还要跑 bundle 和 test:browser
python3 scripts/test_tmux.py && python3 scripts/test_ssh.py
scripts/run_android_matrix.sh standard change-standard
scripts/run_android_matrix.sh voice change-voice
python3 scripts/summarize_android_results.py .tools/diagnostics/matrix/base-standard .tools/diagnostics/matrix/change-standard
```

判定规则：

| 情况 | 处理 |
| --- | --- |
| 改动后失败、原代码通过 | 回归。修好之前不能提交为“完成” |
| 原代码同样失败、停在同一行 | 旧问题。记录下来，另开任务修；不能算作通过 |
| 单独重跑通过 | 偶发失败。记下用例名和重跑次数；同一用例反复偶发要查原因 |
| NORUN / CRASH | 先看日志，看不出原因就重启模拟器重跑，不能当成通过或跳过 |
| SKIP | 检查是否缺了 SSH 环境；界面测试的跳过数量应该接近 0 |

截图图集需要更新时，在 `mtmux-catalog` 上跑 `UiCatalogTest`（见 `docs/screenshots/current/README.md`）。截图不入库。

提交：只 `git add` 本次改动涉及的文件。提交说明写清产品改动、测试改动和验证结果。测试改动必须说明原来要验证的行为是否保持不变。

### 3.4 发布预发行版

1. 修改 `app/build.gradle.kts`：versionCode +1，versionName 改成新版本号。
2. 写 `docs/releases/<版本>.md`（release workflow 会把它附进 Release 说明），同时更新 `CHANGELOG.md` 和 README 开头的版本说明。
3. 预发行的标记方式：标签带 `-` 后缀（如 `v0.9.3-rc.1`）会自动标为预发行；不带后缀的版本号（如 0.9.1、0.9.2）需要在 `.github/workflows/release.yml` 的 `case` 里加上该标签。以后建议统一用 `-rc.N` 后缀，不再修改 workflow。
4. 推送并打标签（标签名必须等于 `v` + versionName）：
   ```bash
   git push origin main
   git tag -a v<版本> <提交SHA> -m "mTmux <版本>"
   git push origin v<版本>
   ```
5. 等待并核对：
   ```bash
   gh run list --limit 6                      # Android checks、Voice checks、Release APK 全部 success
   gh release view v<版本> --json isPrerelease,isDraft,assets
   gh release list --limit 3                  # Latest 仍是正式版
   ```
   需要确认：预发行标记为 true、不是草稿；附件包含两个 APK 和两个 `.sha256`；证书指纹由 workflow 校验。需要本地验签时，见 WebView 测试说明里的“固定签名与 GitHub 发布”一节。
6. 不覆盖、不删除已有标签；失败时修复后打新标签。

### 3.5 发布之后

- 把真机试用反馈记进 `docs/releases/<版本>.md` 或下一版的计划里。
- 关掉测试用的模拟器；临时的 `git worktree` 用 `git worktree remove` 清理。
- `.tools/diagnostics/matrix/` 下的原始结果保留到下个版本发布，供对照。

## 4. 尚未覆盖

- 真实麦克风录音、ARM 真机上的动画手感、厂商输入法。
- 截图图集还没按 0.9.2 重新生成到 `docs/screenshots/current/`。
- `encryptedPrivateKeysRestoreAndConnectWithOneClick`、`TmuxDoctorUiTest#offlineDiagnosis…` 偶尔超时，原因未查。
