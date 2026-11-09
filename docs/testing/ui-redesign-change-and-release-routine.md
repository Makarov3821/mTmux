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
2. 写 `docs/releases/<版本>.md` 保存实现与验证结果，在 `CHANGELOG.md` 对应版本逐条说明用户变化；Release workflow 按[发布说明规范](../release-notes.md)直接提取该列表。README 维护产品介绍，发版时核对其内容是否仍准确。
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

## 5. 流程复审补充（2026-11-01）

以下为对照 [WebView 测试与发布操作说明](webview-and-release-runbook.md) 后增加的操作要求。前面的历史结果、原规则和脚本均保留；需要删改的表述或脚本问题另行汇报，不在本轮替换。

### 5.1 固定实际测试对象与设备

- 每轮记录产品提交、测试代码提交、未提交改动、完整用例列表、variant、设备序列号、API/ABI、WebView provider/版本、输入法、绘制和加速状态。当前脚本的 context 只截取前 20 条 git status，不能单独证明完整工作区或实际 APK 对应关系；另外保存完整 status 与相关文件摘要，不把凭据或未脱敏配置写入证据。
- 设备名称 `mtmux-test` 是本机约定，其他机器的等效专用设备或 ARM 真机也可用于回归；关键是实际系统镜像、绘制、provider 与加速条件。`id` 组列表不足以单独证明进程能使用 KVM，应结合 accel-check 和启动日志确认。
- 启动后先检查 `adb -s "$ANDROID_SERIAL" get-state`、`adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed`，再核对 `adb -s "$ANDROID_SERIAL" emu avd name`（仅模拟器）。序列号必须是本次实际设备，不默认认为新启动的一定是 5554；启动等待应有超时。禁止多个 agent 同时在同一设备安装、运行测试、force-stop 或重启。
- standard/voice 共用包名，测试会替换调试安装；使用专用设备，不卸载或清除存有个人配置的正式安装。用例影响本地偏好和服务器列表时，明确测试数据范围及清理方式。
- 基线 worktree 与当前仓库不要并行复用同一模拟器、输出目录或构建目录。旧仓库缺工具时显式使用当前工具路径；不能把新模型/新测试强灌到旧基线后仍称为原始版本测试。

### 5.2 先采证，再重启和重跑

每轮开始，以及异常结束后、重启/清日志之前，运行只读采集并指定不同目录：

```bash
bash scripts/collect_webview_diagnostics.sh "$ANDROID_SERIAL" .tools/diagnostics/matrix/<唯一轮次>/env-before
# 运行本轮矩阵或定向测试后
bash scripts/collect_webview_diagnostics.sh "$ANDROID_SERIAL" .tools/diagnostics/matrix/<唯一轮次>/env-after
```

同时保留对应 console、testlog、XML/HTML 报告及开始/结束时间。crash buffer 可能包含旧记录，必须与这次测试时间对齐；单个 SIGTRAP 日志不证明所有超时都由同一原因造成。没有 renderer 崩溃的 JS 超时，继续检查页面初始化、资源、桥接和线程阻塞。

如需重启，只重启本轮独占的模拟器，记录理由、环境变化和轮次；先保存首次失败，重跑另建目录，不覆盖它。不要连续重跑直到出现一次 PASS 后删掉失败记录，也不要为规避问题加入产品禁 JIT/GPU、关闭同步或吞异常的开关。

诊断与截图应先脱敏；`.tools/` 和生成图集继续 Git 忽略。当前跟踪的截图操作入口是 [docs/screenshots/README.md](../screenshots/README.md)，不是依赖可能未生成的 current 子目录说明。

### 5.3 矩阵结果要检查完整性，不能只看汇总成功

当前 `run_android_matrix.sh` 是逐类执行器，`DONE` 只表示循环走到了末尾；它不会汇总失败后返回失败状态。`summarize_android_results.py` 是报告工具，当前也没有“发现非 PASS 就非零退出”的门禁。不得把这两个脚本的退出码或 `DONE` 当成全绿。

2026-11-01 的隔离输入验证发现当前汇总器还有两种边界：已有 test-results.log 但只有 runner 启动失败、没有单项状态时，会返回空结果；console 中 rc=1 而 log 有一项 PASS 时，仍会报告该项 PASS，不会自动说明整轮失败。该验证使用临时目录，没有运行或改变设备测试，也没有修改脚本。

因此每轮至少交叉核对：

1. 预期类/方法清单与实际类/方法清单一致。不存在目录、空目录、无测试、启动失败、进程崩溃、超时或类只执行一部分，均不能算通过；新增测试与旧基线没有的测试分别标识，不因旧版 ABSENT 直接推断产品回归。
2. 每类 console 末尾的 `rc=`、Gradle/instrumentation 结束状态、testlog 单项状态和 XML 统计相互匹配。单项 PASS 与整轮失败可同时存在，两者都记录；`Task ... SKIPPED` 不是 JUnit skip，真正的 IGNORED/SKIP 逐项写原因。
3. 比较总数、通过、失败、错误、忽略、未执行和重复项；按设备分别核对。当前汇总器只读取匹配到的第一份设备 log，不支持据此声称多设备都通过。
4. 每轮使用新的 label，例如 `change-standard-20261101-01`。当前执行器复用 label 会覆盖同名类并可能残留旧类 console；预先拒绝非空轮次目录，不把不同提交/轮次混成一次结果。当前脚本会清前一类的构建结果，仍须保留本轮首次原始报告，不能仅保存最后一次重跑。
5. 若测试和产品在执行期间继续被修改，停止将这一轮作为单一候选提交的发布证据，重新记录一致的候选版本后验证。

汇总脚本仍可用于人工浏览和差异比较；增加严格失败退出、清单完整性与 runner 失败识别等脚本改动，留待用户决定，本轮不改。

### 5.4 补齐桌面页面、模型与 APK 检查

首次构建或依赖缓存不完整时，先准备固定输入，再使用前述 offline Gradle 命令：

```bash
python3 -m unittest discover -s scripts/tests
python3 scripts/fetch_voice.py
```

offline 因依赖缺失失败要记为环境未准备，不是产品测试失败或测试通过；联网下载后重跑，不能修改固定模型哈希绕过。

发布候选的终端页面除 Node 测试外，还需验证实际打包资产。以下 bundle 会写入跟踪目录，应在候选版本上执行并检查生成差异：

```bash
npm --prefix web ci --ignore-scripts
npm --prefix web test
npm --prefix web run bundle
git diff --exit-code -- app/src/main/assets/terminal
npm --prefix web run test:browser
```

本机 localhost HTTP 被拒绝时，可用 `TERMINAL_TEST_FILE=1 npm --prefix web run test:browser` 作为补充，并明确记录为桌面 file origin；不能替代 CI 默认 HTTP 路径或 Android WebView 验证。Node、桌面浏览器、真实 WebView、原生输入组件和离线截图的结果分别列出。

两个 Release APK 需要验证模型/runtime 固定输入、standard 的负载与权限、voice 的模型与 ABI、包名/版本一致、签名证书、体积和 16 KiB 对齐；CI 中已有这些检查。当前 SSH fixture 已配置 SFTP subsystem，文件预览测试仍需逐项核对实际执行的读取、取消和缓存场景。

### 5.5 基线结论和偶发失败的表述

“同一行失败”是定位线索，不足以独立证明同一个根因。对照至少保证同设备环境、等价测试步骤、同配置/数据/权限及相同可观察行为；必要时补断言保持原需求。原版和新版分别使用不同测试时，要写明变化和共有覆盖范围。

未引入新增失败，只能表述为“在本次已执行用例与环境中未发现新增失败”，不推导为完整产品无回归。新增用例、真机麦克风、厂商 IME、后台/熄屏恢复等未覆盖项继续保留。

偶发失败报告保留首次结果和全部重跑结果，写清用例、轮次、失败率、前后设备状态；一次重跑通过不能把原始矩阵改成全绿。对隐私窗口 FLAG_SECURE、跨服务器/旧连接输入、自动发送等关键行为，不因旧版也失败就忽略。预发行如仍有未解决问题，应在版本记录列明影响、当前证据和验证缺口；正式版发布前处理相关风险。

### 5.6 发布核对绑定到同一个候选提交

当前 Release APK、Android checks、Voice checks 是独立 workflow。Release APK 不会等待另两个成功，也不会运行完整 Android instrumentation；标签一推就可能先发布。因此先核对候选提交所需检查，再在已获授权的发布范围内推标签，不能依赖“先推标签、之后发现别的 CI 红了再说”。

以具体 SHA 查询，不能只看最近 6 次运行：

```bash
candidate_sha=$(git rev-parse HEAD)
gh run list --commit "$candidate_sha" --limit 50 \
  --json databaseId,workflowName,event,headSha,status,conclusion
```

逐条确认候选提交、workflow、触发来源及结论。Voice checks 有 paths 过滤，没触发时说明原因；涉及 voice 的变动不能拿旧提交成功替代，需要按候选提交补验证。提交或版本调整后，记录哪些检查针对最终 SHA，哪些基于仅版本号不同的同一产品内容，不混称为全都测过。

已获授权且暂不发布、仅需要正式签名测试 APK 时，可手动运行 `release.yml`，它上传 `signed-release-apks` artifact 而不创建 Release；当前令牌需要 Actions 写权限，权限不足不算构建成功，不擅自替换为正式标签。

发布后确认 Release 的标签指向候选 SHA、draft/prerelease、稳定 Latest 与四个附件；尽量下载两 APK 及 SHA 文件，分别执行摘要、apksigner 证书和 aapt2 包名/版本核对。只下载一个时分别说明，不把另一个的服务端摘要当成本地验签。

本机 debug / unsigned APK 无法代替正式签名覆盖安装；GitHub secrets 不需要导出到本地。代理导致 API 查询/下载失败时先重试或等待，保留失败证据，不重复创建/覆盖已发布标签。清理只针对本轮拥有的模拟器和确认干净的 worktree；原始结果超过最低保留周期前，不因下一版发布就自动删除仍关联未解决问题的证据。
