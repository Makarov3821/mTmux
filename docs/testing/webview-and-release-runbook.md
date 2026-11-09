# WebView 测试与签名发布流程

2026-10-31，按当前仓库 workflow、测试源码和本机记录整理。此文固定操作与结果口径，不触发发布，也不更改用户正在进行的 UI 改动。文件预览的实现与验证见[专项记录](terminal-file-preview.md)，发布正文格式见[发布说明规范](../release-notes.md)。

## 哪些测试实际运行 WebView

| 检查 | 实际环境 | 能证明什么 |
| --- | --- | --- |
| `npm --prefix web test` | Node | 协议/纯逻辑；没有浏览器或 WebView |
| `npm --prefix web run test:browser` | Playwright + 桌面 Chromium，加载已打包终端资源 | xterm、桥接替身、粘贴、触摸事件编码等；不能证明 Android WebView 正常 |
| `testStandardDebugUnitTest` / `testVoiceDebugUnitTest` | JVM | 原生业务状态、编码等；不运行 Android WebView |
| `TerminalImeFocusTest` / `DirectTerminalInputTest` / `TerminalKeyboardTest` | Android 专用测试 Activity / 输入组件 | 真实 InputConnection、IME、焦点与键盘组件；特意避开 WebView |
| `TerminalUiTest#terminalRendersBytesAndSurvivesConfigurationPanelAndKeyboard` | Android MainActivity + TerminalView | 真 WebView 执行 JS、终端输出、像素绘制与键盘布局；无需 SSH fixture |
| `TerminalUiTest#terminalRightEdgeFitsChineseAndAscii` | Android MainActivity + TerminalView | 真 WebView 中文/ASCII 行宽与实际画面 |
| `scripts/test_ssh.py --android` 的终端用例 | Android WebView + 临时 SSH/tmux | 真实通道、目标校验与输入/滚动交互；必须检查有没有 skip |
| 本地截图图集 | 原生界面/固定组件，有些终端画面由桌面离线渲染注入 | 界面展示；不是完整 WebView/SSH 回归 |

0.9.1 发布记录中的 7 项 Android 通过结果属于输入组件回归，并非 7 项 WebView 测试。历史确实有 Android WebView 通过记录，见[早期终端验证](../evidence/terminal-android15/README.md)，但不能用旧结果证明当前改动通过。

本次（2026-10-31）重跑 `TERMINAL_TEST_FILE=1 npm --prefix web run test:browser` 通过。上一轮预览链接隔离验证也是桌面 Chromium，不是 Android WebView。

## 当前崩溃证据与未解决的问题

只读检查当前 `emulator-5554` 得到 API 35、x86_64、Android ATD，provider 为 `com.android.webview 124.0.6367.219`，`debug.hwui.drawing_enabled=0`。当前 crash buffer 中，2026-10-31 01:24 的记录先出现 `CrRendererMain` / `libwebviewchromium.so` 的 SIGTRAP，再出现 dev.mtmux 因关联 WebView 未处理 renderer 退出而崩溃。

这能解释部分 “JavaScript did not respond” 的直接链路：renderer 已退出，evaluateJavascript 回调不再到达；仅延长 JS 等待时间不会修好 renderer。尚未确定导致 renderer SIGTRAP 的底层原因，不能仅凭无 KVM 或 WebView 124 就宣称根因已证实，也不能排除改动触发了它。ATD 绘制关闭还会影响真实像素/截图断言，是另一条需要单独处理的环境条件。

用户提供的最新执行结果必须保留边界：9 项依赖 WebView 的 JS 无响应；原始代码对照只完成了其中 1 项且也失败，另外 2 项因崩溃没有执行，因此只对这 1 项建立了同环境基线。

另外 5 项仍作为可能的产品或测试回归处理，不能计为环境跳过或通过：

| 用例 | 现象 | 下一步定向检查 |
| --- | --- | --- |
| `editorChoosesFolderAndCopiesSavedServerAsJumpHosts` | Jump Host 后找不到“从已保存服务器选择” | 展开状态、滚动可达性、真实文案和 semantics；先看节点树再改断言 |
| `homeTaskLightsPersistExpireAndAggregateWithoutStoringText` | 等不到“显示上次缓存” | 卡片副标题是否存在/截断、文本合并、缓存状态是否真正产生 |
| `privacyDisplayPersistsAndProtectsCredentialScreens` | FLAG_SECURE 等待超时 | 新私钥/指纹窗口的实际 Window 及设置/清理时机；属于需优先确认的隐私行为 |
| `LanguageUiTest`：不空闲 | Compose idle 超时 | 看持续动画和真实后台任务；区分不空闲与设备慢，不能全局关闭同步掩盖问题 |
| `LanguageUiTest`：重建后缺少界面 | 切语言、Activity 重建后找不到目标 | ActivityScenario/页面恢复/节点定位及崩溃时间线，和前一项分别记录 |

## 本机构建环境

在仓库根目录运行。以下路径对应本工作区已有工具，其他机器用各自已安装的 JDK 17 / Android SDK 路径替代。CI 用 JDK 17、compile SDK 36、build-tools 35.0.0、Node 22；当前本机桌面验证使用已安装 Node 24，不能称为与 CI 完全一致。

```bash
export JAVA_HOME="$PWD/.tools/jdk-17.0.20.1+1"
export GRADLE_USER_HOME="$PWD/.tools/gradle-home"
export ANDROID_HOME="$PWD/.tools/android-sdk"
export ANDROID_USER_HOME="$PWD/.tools/android-user"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export ADB="$ANDROID_HOME/platform-tools/adb"
adb devices -l
```

所有 instrumentation 安装会改动选中设备的调试 app。使用专用测试设备/模拟器，勿对正在存放个人服务器配置的正式安装直接跑测试。standard/voice 共用包名 `dev.mtmux`，不能并排安装；本机 debug 签名也不能覆盖 GitHub 正式签名。遇到签名冲突应换测试设备，不默认卸载正式版或清除其数据。

## 分层执行测试

### 桌面终端页面

```bash
npm --prefix web ci --ignore-scripts
npm --prefix web test
npm --prefix web run bundle
git diff --exit-code -- app/src/main/assets/terminal
npm --prefix web run test:browser
```

`bundle` 会更新被跟踪的 app assets；diff 有变动时要核对源码与产物是否需要一起提交，不能盲目覆盖用户改动。当前 CI 用默认的本地 HTTP 测试地址。

本工作区 Chrome 对 loopback HTTP 可能返回 `net::ERR_ACCESS_DENIED`，可单独尝试：

```bash
TERMINAL_TEST_FILE=1 npm --prefix web run test:browser
```

这是 file origin 的桌面测试替代结果，需明确标注，不能称为 HTTP origin 或 Android 虚拟 HTTPS origin 验证。Chrome 不在默认位置时设置 `CHROME_BIN`；只有 Node 测试成功并不表示系统已具备浏览器。

### 原生 IME 与真 WebView 分开跑

先显式选设备，避免多设备时误投递。`ANDROID_SERIAL` 同时供 adb 和 Android Gradle Plugin 使用；更换为自己的测试设备序列号。

```bash
export ANDROID_SERIAL=emulator-5554
bash scripts/collect_webview_diagnostics.sh "$ANDROID_SERIAL"
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  :app:connectedStandardDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.mtmux.TerminalImeFocusTest \
  --console=plain --max-workers=2
```

先运行一个无需网络 fixture 的真实 WebView 用例：

```bash
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  :app:connectedStandardDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=dev.mtmux.TerminalUiTest#terminalRendersBytesAndSurvivesConfigurationPanelAndKeyboard' \
  --console=plain --max-workers=2
bash scripts/collect_webview_diagnostics.sh "$ANDROID_SERIAL"
```

一次只选一个定向用例，先确认 provider/renderer 能正常绘制和执行 JS，再扩展。崩溃后其他用例未运行时记为“未执行”，不要因只有一个结果而声明整个测试类失败原因相同。报告位于 `app/build/reports/androidTests/connected/`，原始结果位于 `app/build/outputs/androidTest-results/connected/`，每轮及时复制到 `.tools/diagnostics/` 下，避免下一轮覆盖。

在专用 ATD 上，如果像素断言受绘制关闭影响，可在确认无人共用该设备后记录原值、临时打开绘制，并在测试结束恢复原值；这是测试设备设置，不写入产品代码。启用绘制并不保证修复 renderer SIGTRAP。不要把 `--no-sandbox`、禁 JIT/GPU 或吞掉 renderer 退出当成通过条件。

真实 SSH/tmux 测试使用现有隔离 fixture，依赖本机 tmux、openssh-server、可用 PTY 和 `/run/sshd` 等运行条件：

```bash
python3 scripts/test_ssh.py --android --android-variant standard \
  --test-class 'dev.mtmux.TerminalUiTest#directTypingAndEditedPasteUseRemoteInputWithoutAutomaticSubmit'
```

脚本自动启动临时 loopback sshd、生成临时密钥、使用专用 tmux socket 并 adb reverse。sshd 已配置 SFTP subsystem，可供远端文件预览集成用例使用。直接运行 Gradle 时需要提供 fixture 参数，并核对每项测试实际执行结果与 skipped 数量。

### 如何判断和复测

- provider 不存在/无效：先解决系统 WebView 安装、启用或镜像问题，不靠修改 xterm 解决。
- renderer SIGTRAP：保存 provider、ABI、崩溃时间与测试名；同一提交在有 KVM 的专用镜像或 ARM 真机重跑定向用例，再判断环境与改动的关系。更换 provider/镜像前记录版本，不在共用设备上静默切换。
- 无 renderer 崩溃但 JS 无响应：检查 WEB_READY / WEB_LOAD_FAILED / WEB_SCRIPT_FAILED、页面资源、CSP 和 UI 线程/主线程阻塞。
- JS 正常而像素断言失败：检查绘制设置、窗口可见性、FLAG_SECURE、终端几何和截图采集方式，不能用 xterm buffer 有字替代实际绘制。
- 文案、隐私标志、语言重建断言失败：按具体行为排查，保留失败，不能统一移出发布检查。

测试矩阵至少记录：commit/工作区是否有改动、用例全名、设备/API/ABI、WebView 版本、绘制/KVM 环境、pass/fail/skip/未执行、原始报告路径。WebView 加载成功不是“完整输入回归通过”，构建成功也不是运行测试通过。

## 发布前固定检查

现有 [Android checks](../../.github/workflows/ci.yml) 运行 Node、桌面 Chromium、standard JVM/build/lint、宿主 SSH/tmux 集成；会编译 Android 测试 APK，但不启动模拟器执行完整 Android WebView 测试。

[Voice checks](../../.github/workflows/voice.yml) 默认做下载验证、JVM、构建、lint、模型/权限/对齐；只有手动 workflow_dispatch 才跑指定的模拟器 smoke，也不是全部 TerminalUiTest。

[Release APK](../../.github/workflows/release.yml) 独立执行 JVM 与签名构建、模型/权限/证书/对齐验证并发布。它没有 Node bundle、桌面浏览器、lint 或完整 instrumentation 门禁，也不会自动等其他两个 workflow 成功。因此发布前必须自行核对同一候选提交的检查结果；推标签不是“等所有测试过了才会自动发布”。

建议本地预检命令：

```bash
python3 -m unittest discover -s scripts/tests
python3 scripts/fetch_voice.py
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  :core:test :voice:testDebugUnitTest \
  :app:testStandardDebugUnitTest :app:testVoiceDebugUnitTest \
  :app:assembleStandardDebug :app:assembleVoiceDebug \
  :app:assembleStandardDebugAndroidTest :app:assembleVoiceDebugAndroidTest \
  :app:lintStandardDebug :app:lintVoiceDebug \
  --console=plain --max-workers=2
python3 scripts/test_tmux.py
python3 scripts/test_ssh.py
```

再运行前述桌面页面检查与对应 Android 定向/回归测试。宿主 fixture 以它自身明确执行的用例为准；没给 SSH 环境的普通 core:test 不能替代它。当前 5 项疑似 UI 回归在结论未明前不能写为“全绿”；如交付预发行测试包，需在说明中列出实际失败和验证缺口，正式发布前处理相关风险。

## 固定签名与 GitHub 发布

1. 选定版本，在 `app/build.gradle.kts` 更新 versionName 和递增 versionCode；standard/voice 保持相同包名和版本。写 `docs/releases/<版本>.md` 明确实现与测试边界，在 CHANGELOG 对应版本只列用户变化；运行 `scripts/release_notes.py` 检查生成正文，见[发布说明规范](../release-notes.md)。只提交本次授权的文件，不把工作区其他改动一起扫入。
2. 核对候选提交的检查结果和真实 Android 结果，确认终端 assets 已同步。截图遵循 [截图说明](../screenshots/README.md)，产物保持 Git 忽略，不因发布重新加入跟踪。
3. 签名由 GitHub Actions 中既有 secrets 提供：`MTMUX_KEYSTORE_BASE64`、`MTMUX_KEYSTORE_PASSWORD`、`MTMUX_KEY_ALIAS`、`MTMUX_KEY_PASSWORD`；仓库变量 `MTMUX_CERT_SHA256` 必须匹配现有正式证书。不需要也不应从 GitHub 导出私钥。
4. 需要签名测试 APK、暂不发布时，可手动运行 `release.yml`，其产物在 `signed-release-apks` artifact，不创建 Release。需要当前令牌具备 Actions 写权限；拒绝不能当构建成功，更不能擅自改成正式标签发布。
5. 获得对应发布授权后，对已确认提交打新标签再推送；不覆盖已发布标签。标签必须严格等于 `v` + versionName。当前 workflow 对含 `-` 的标签生成 prerelease，例如 versionName=`0.x.y-rc.1` 对应 `v0.x.y-rc.1`。`v0.9.1` 和 `v0.9.2` 是明确写在 workflow 中的历史特例，不代表未来所有普通版本标签都是预发行。

以下是模板，先替换版本和提交；不作为本次发布指令：

```bash
git push origin main
git tag -a v0.x.y-rc.1 <已核对的提交SHA> -m 'mTmux 0.x.y-rc.1'
git push origin v0.x.y-rc.1
gh run list --workflow release.yml --commit <已核对的提交SHA>
gh release view v0.x.y-rc.1
```

本机过去通过 SSH 推送成功，GitHub HTTPS/API 可能受代理影响。查询失败先重试或等待；必要时用官方 API curl 复核 run/release，不重复推标签、不删除或重建已经成功的 Release。

发布验证需同时确认：run 的 head SHA、结论、Release 的 draft/prerelease 状态、稳定版 Latest、两个 APK 和两个 SHA-256 附件。CI 会验证两 APK 同证书、同包名版本、standard 无录音权限和语音负载、voice 模型/runtime 固定哈希、体积上限及 16 KiB 对齐。

下载后独立核对 SHA 文件与 APK，而不只看网页上的绿色图标；用 `apksigner verify --print-certs` 检查签名，用 aapt2 核对包名/versionCode/versionName。既有正式证书 SHA-256 为：

```text
6b593d67a3a326737a8b0137b1649431156e0d4d382a68b7af1b8046ddbc5127
```

签名附件可以覆盖旧正式安装，但本机无正式签名配置时产出的 unsigned/debug APK 不能替代。若只完成其中一个 APK 的本地完整下载/验签，分别记录，不把另一个 asset 的服务端 digest 当成本地验签通过。

现有 0.9.1 记录：5 个 CI run 通过、Release 为 prerelease，稳定 Latest 保持 0.9.0；两附件服务端 digest 与 SHA 文件一致，standard 本地独立验签/版本/摘要通过，voice 完整本地下载因代理速度推迟。该记录是历史结果，不表示当前未提交的 UI 改动已测试或发布。

## 诊断采集脚本

```bash
bash scripts/collect_webview_diagnostics.sh [设备序列号] [输出目录]
```

不传序列号时使用 ANDROID_SERIAL 或唯一在线设备；多设备时拒绝猜测。默认输出 `.tools/diagnostics/`，已被 Git 忽略。仅保存 commit、provider、API、ABI、fingerprint、绘制设置和过滤后的 crash buffer，不安装 APK、不改 WebView/绘制配置、不清数据或日志、不停止模拟器。crash buffer 可能包含旧崩溃，应对齐测试时间；分享前检查内容。脚本只采证，不判定测试通过。
