# mtmux

Android 上的 SSH + tmux Agent 控制端。目标是离开电脑后，打开手机查看远程 Agent 输出、回复并继续工作；不在服务器安装常驻服务。

**当前正式版为 0.8.4（应用 ID `dev.mtmux`，名称 mTmux）：终端采用无边底栏，支持 Ctrl / Shift / Alt 粘滞组合键和循环翻页；保留六套浅深主题、1×～10× 远端滚动倍率及 standard/voice 双版本。voice 内置离线 Whisper base q5_1，尚未完成 ARM 真机质量/性能验收。** 完整产品范围见 [plan.md](plan.md)，验证结果和剩余门槛见 [P0 报告](docs/testing/p0-report.md)。

0.8.5-rc.2 / code34 预发行：按住录音与上滑取消、Whisper base q8、可关闭的离线标点恢复及轻量 SRF 对照构建。两种模型组合的试验、实际包体与设备验证范围见 [模型记录](docs/testing/voice-models-20261023.md)；当前默认构建为 q8 + CT，0.8.4 已发布 APK 仍为 q5；预发行两个语音包经 GitHub 原证书签名后可直接覆盖升级，见 [发布记录](docs/releases/0.8.5-rc.2.md)。

版本功能清单见 [CHANGELOG.md](CHANGELOG.md)；本轮详细记录见 [0.8.4](docs/releases/0.8.4.md)。

0.8.4 终端底栏：发送合入输入框，工具与快捷回复在各自区域内居中，voice 版中央使用主题配色的圆形声波按钮；快捷键新增 Ctrl / Shift / Alt，点击高亮后作用于下一次按键并释放，四页双向循环。详见 [键盘验证](docs/testing/terminal-keyboard.md) 和 [底栏效果图](docs/design/mockups/terminal-dock-v3.png)（布局示意，非实机截图）。

0.8.3 UI 改版：服务器卡片与多 pane 层级、六套浅深主题、分组设置和统一终端工具/弹窗；远端滚动倍率提供 1×～10× 十档。默认 1×，设置与工具抽屉同步保存；通过增加真实滚轮事件加速，无需修改 tmux 配置。详见 [实现与验证](docs/testing/ui-refresh-report.md)。

## 原型能做什么

- 使用密码或导入私钥连接 SSH；首次连接展示 SHA-256 主机指纹，确认后保存信任记录，密钥变化时拦截。
- 保存多个服务器的名称、地址、端口、用户名、认证方式和 tmux 路径，支持编辑、删除；密码、私钥及其口令使用 Android Keystore + AES-GCM 加密保存在本机。在独立配置页点击“保存”后回到首页，下次点击服务器下的 tmux 任务或右侧 `>_` 即可连接。
- 首页支持本机文件夹分组：新建、重命名、折叠及移动服务器，删除文件夹后服务器回到未分组。搜索覆盖别名、地址、用户名、文件夹和缓存 tmux 名称；支持添加顺序、名称、最近使用排序，排序偏好与分组持久保存。
- 首页每个 tmux 名称前增加状态点：红色可能需要回答/处理，黄色有运行提示，绿色表示本轮结束或 Agent 已就绪；灰色表示未知、过期或刷新失败。刷新列表时保留同一任务尚未过期的旧颜色，分批读取后再替换；每个 pane 分别记录采样时间，读取失败或身份变化显示灰色。颜色随既有首页刷新更新，不增加网络轮询；本机每分钟检查是否超过 30 分钟并将过期颜色置灰。颜色依据上次刷新时对应 pane 的终端标题与画面推测，并非实时状态；没有输出或仅有 shell 提示符不会判为完成。多 pane 窗口优先红、其次黄，全部结束/就绪才绿。
- standard 终端发送按钮位于输入框右侧，下方工具和快捷回复各占半栏并居中；voice 版中央增加圆形声波按钮，两侧工具和快捷回复在剩余空间内居中。voice 内置 whisper.cpp 1.9.5 multilingual base q5_1，音频和转写完全在本机处理。停止录音后只把结果填入可编辑草稿，已有草稿时选择追加/替换，绝不自动发送。
- 终端底部快捷回复先填入草稿，已有草稿时选择追加/替换/取消，发送仍需用户点击；支持本机添加、编辑、删除（最多 12 条，每条 1000 字）。
- 设置支持界面语言：跟随系统、简体中文、English；系统语言无对应资源时显示英文，Android 13+ 与系统“应用语言”设置联动。切换语言会重建页面并回到设置，不触发首页刷新；服务器别名、文件夹、已保存快捷回复、tmux 名称和终端内容不翻译。
- 设置支持深色、浅色和跟随系统，跨重启保存，默认沿用深色。文字、图标、表单、终端 ANSI 调色板、光标、选区和复制页一同适配；切换主题不重建终端，跟随系统切换不重建 Activity。
- 连接过程中显示目标服务器或跳板序号、连接认证及 tmux 检查进度；失败区分地址解析、端口拒绝、超时、认证、私钥及转发问题，不展示库异常原文或凭据。
- 启动显示服务器单列及上次缓存的 tmux 子项；冷启动或从后台回到首页时，仅对展开文件夹及未分组中距离上次嗅探至少 5 分钟的服务器自动刷新；每台服务器右侧可随时手动刷新。文件夹展开/折叠状态跨重启保存，折叠文件夹跳过自动刷新；文件夹右侧圆形箭头可手动刷新全组，包含搜索隐藏的服务器，不受间隔和折叠限制。从终端返回时，仅刚使用的服务器跳过时间间隔，仍受折叠文件夹限制；其他服务器遵守正常自动策略。从设置返回、仅修改本地展示后返回，以及展开文件夹本身不触发额外刷新。刷新失败保留缓存并显示原因；只缓存名称、目标身份及状态枚举，不保存终端输出。
- 高级选项支持多级 Jump Host，各级独立认证、排序与主机指纹校验；发现、SSH shell、tmux 和重连均经过同一链路。
- 进入 tmux 时合并身份核对、名称读取与会话鼠标设置；首页先显示带身份的任务列表，再以最多两个并行批次补状态。设置中可选择空闲 SSH 链保留数量：无 / 少 / 中等 / 多 / 很多分别为 0 / 1 / 2 / 3 / 5 条，默认中等；刚刷新成功的连接在前台保留一分钟，空闲时停用应用心跳，不额外轮询任务。降低档位立即关闭超额连接，退后台清空。刷新中点击任务会停止派发新批次，等在途状态命令结束后独占转交同一条 SSH；凭据、路由或主机信任变化时不复用。
- 保存最近 10 个成功进入的任务，首页对应子项标注上次任务；点击核对身份后回到原 pane。断线或连接失败后点“重连最近任务”优先重试本次选择的任务，包括首次连接失败的任务，不会跳回上一个成功任务；失败尝试不写入成功历史。不自动连接、不重放输入；原任务已结束或重建时必须重新选择。普通 shell 明确作为新连接。
- 查询默认 socket 中的 session/window/pane，展示会话名称、窗口名称和索引，按 session 接入 tmux；也能打开普通 SSH shell。
- 使用本地打包的 xterm.js 显示 UTF-8、ANSI 和交互式 TUI，发送 PTY resize。中文标点使用固定网格间距，避免浏览器标点压缩影响宽度测量、导致右侧半字被裁切；验证记录见 [0.6.6 显示修复](docs/releases/0.6.6.md)。
- 提供“输入命令或回复”文本框、“发送回车”及工具内的“仅粘贴”；多行发送先预览，快捷键栏左右滑动循环切换常用键、F1–F6、F7–F12、修饰键四页，每页六键等宽铺满；Ctrl / Shift / Alt 可组合点亮，作用于下一次软键盘或实体按键后释放，原有草稿保持不变；另有字号和复制选区。
- 保留当前进程中的草稿，按服务器、跳板路由、用户名及 tmux 服务/窗口/pane 身份隔离；前后台切换断开 SSH，手动重连，不重放输入。
- 主终端支持真实鼠标触控：上下滑动对应滚轮、轻点对应左键、长按后松开对应右键；按 xterm 协商的鼠标协议发送。tmux 使用服务器现有滚轮绑定，鼠标 TUI 会收到事件；未启用鼠标的普通 shell 滚动手机本地历史（最多 3000 行），不会把滑动擅自变成方向键。
- tmux 实时滚动读取服务器历史，保留现有 `history-limit`；顶部“回到底端”核对当前 pane 后退出 copy-mode，普通 shell 回到本地历史底端。历史快照入口已移除；Agent 自己内部的历史状态由该程序控制。
- 返回首页关闭手机当前连接，远端 tmux 任务继续；在终端输入 `exit` 或 detach tmux 等正常结束时自动回到首页；网络异常或退后台保留最后输出并标记“已断开 · 以下为历史内容”。草稿仍可编辑，但不能向断开的连接发送。

**当前仍使用普通 tmux attach，共享显示焦点和尺寸。** 回复、粘贴与快捷键绑定到界面显示的 pane，焦点改变时拒绝发送并保留草稿；不会跟随电脑切换而发到其他 pane。鼠标点击和滚轮仍直接操作所见终端。独立显示焦点与尺寸策略尚未实现，普通 shell 不保证断线后进程继续运行。

## 构建

需要 JDK 17、Android SDK（Platform 36、Build Tools 35.0.0）、可访问 Maven/Google/Gradle 仓库的网络。原型 minSdk 26、target/compileSdk 36；最低可用 Android 版本仍需真机验证。

```bash
# 指向你本机的 SDK；local.properties 不提交
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

# standard：无需 NDK/模型
./gradlew :core:test :app:assembleStandardDebug :app:lintStandardDebug

# voice：固定源码、base q8 与标点模型（校验 SHA-256），需要 NDK 28.2/CMake 3.22.1
python3 scripts/fetch_voice_benchmark.py deps
python3 scripts/fetch_voice_benchmark.py base
python3 scripts/fetch_voice_punctuation.py
./gradlew :voice:testDebugUnitTest :app:assembleVoiceDebug :app:lintVoiceDebug
```

APK 输出：`app/build/outputs/apk/standard/debug/app-standard-debug.apk` 与 `app/build/outputs/apk/voice/debug/app-voice-debug.apk`。0.8.4 已发布 voice 约 74.6 MB；当前未发布 q8 + CT 的未签名 Release 约 196.5 MB，SRF 对照约 136.7 MB，均含 arm64-v8a/x86_64；standard 约 12.2 MB。两个 flavor 使用同一应用 ID/版本/签名，只能安装一个，可相互覆盖并保留数据。

终端资产已经打包进源码，无需运行时联网下载。修改 `web/` 后使用 Node.js 22+ 重建并测试：

```bash
cd web
npm ci --ignore-scripts
npm test
npm run bundle
```

`web/package-lock.json` 固定依赖；`app/src/main/assets/terminal/` 是对应打包结果。勿只修改生成文件。

本次开发环境的 JDK/SDK/Gradle 缓存放在 `.tools/`，不属于可移植源码。若在当前工作区继续开发，可使用：

```bash
export JAVA_HOME="$PWD/.tools/jdk-17.0.20.1+1"
export GRADLE_USER_HOME="$PWD/.tools/gradle-home"
./gradlew :core:test :app:assembleStandardDebug :app:lintStandardDebug
```

### 语音 V0 基准（不进入正式 APK）

独立 `:voice-benchmark` 模块固定 whisper.cpp 1.9.5，可比较 multilingual tiny q5、base q5/q8；当前运行脚本默认比较 base q5/q8。需要 Android NDK 28.2.13676358、CMake 3.22.1 和 arm64-v8a/x86_64 测试设备；源码、模型和 WAV 下载到被忽略的 `.voice-benchmark/`，不会提交或打进 mTmux APK：

```bash
python3 scripts/fetch_voice_benchmark.py all
python3 scripts/fetch_voice_benchmark.py verify
./gradlew :voice-benchmark:testReleaseUnitTest :voice-benchmark:assembleRelease :voice-benchmark:lintRelease
python3 scripts/run_voice_benchmark.py --models tiny,base --language en --threads 4 --repeat 3
```

当前 x86_64 模拟器结果只证明 Android native 链路可用，不能替代 ARM 真机或中文质量判断；指标、固定 SHA-256 和限制见 [语音 V0 报告](docs/testing/voice-v0-report.md)。该 benchmark 无录音/网络权限，不修改产品终端。

## 手机上怎么试

1. 使用 Android 8.0+ 手机覆盖安装 debug APK；首页为服务器列表。已有直连配置和凭据会继续保留；没有嗅探记录时首次进入会刷新任务列表。
2. 右上角 `＋` 添加服务器：填写别名、IP/域名、端口、用户名，选择密码或导入私钥；加密私钥填写口令。点击顶部“保存”后关闭表单并回到首页，无需先连通服务器。下次无需重复填写凭据。0.1.0 及更早版本需补充并保存一次凭据。
3. “高级选项”中可选择服务器所属文件夹。使用跳板时开启 Jump Host，按“手机 → 跳板 1 → 跳板 2 → 目标服务器”填写，或点“从已保存服务器选择”复制已有服务器（含其跳板链）为跳板；各级可以使用不同密码或私钥，也可调整顺序。手机只需能访问第一级，后续地址由前一级访问；服务器需允许 SSH TCP 转发。高级选项还提供 tmux 路径及会话鼠标开关。
4. 首页先显示缓存，冷启动或从后台回到首页，展开文件夹和未分组中的服务器距离上次嗅探至少 5 分钟才自动刷新（每台独立记录，失败也计入，重启保留）；右侧圆形箭头可随时单独刷新。有别名时只显示别名，更新时间放在名称旁；无别名显示用户名和地址。首次连接各级主机会显示“需确认主机指纹”，点击该提示，通过可信管理渠道核对指纹后信任；逐级继续刷新。变化的主机密钥会被拦截，不静默信任，也不绕过跳板。
5. 点击服务器下的“会话 / 窗口”连接 tmux；窗口有多个 pane 时先展开选择。右侧 `>_` 打开普通 SSH shell，无需先发现 tmux；`⋮` 提供编辑、删除。点服务器名称可折叠/展开子项。首页搜索框右侧“▱＋”新建文件夹，服务器 `⋮ → 移动到文件夹` 分组；文件夹 `⋮` 重命名/删除。新服务器默认未分组。排序在各文件夹内生效，文件夹保持创建顺序、未分组排最后。搜索不发起网络请求，匹配缓存任务时临时展开，清除后恢复折叠状态。
6. 在终端的“输入命令或回复”中输入 `pwd`，点“发送回车”提交；成功写入后清空输入框，结果在终端显示。输入法回车只换行。“仅粘贴”不提交；有草稿时快捷 Enter 等同发送并回车。多行先预览，且仅在目标程序启用 bracketed paste 时允许。“已发送”不等于远端执行成功。
voice 版点击底部麦克风后才申请权限；再次点击“停止”后本机转写。空草稿直接填入，有内容时选择追加/替换；结果可编辑且不会自动发送。设置“自动检测语言”默认关闭：关闭时使用当前界面语言，开启时由 Whisper 检测。
7. 终端滑动对应鼠标滚轮，轻点左键，长按松开右键；tmux 沿用服务器的滚轮绑定与历史上限。顶部只显示返回、服务器与 tmux 名称和“回到底端”。“工具”提供复制、仅粘贴、回复目标/重新绑定、字号和滚动设置；首页右上角设置也可调整字号、滚动速度和触控方式，偏好保存在本机。
键盘弹出和收起只改变可见区域，终端网格保持不变，不因此触发远端 resize 或重放输出。底部暂时被键盘遮挡的内容，可通过顶部“回到底端”显示；旋转屏幕或修改字号仍会正常调整终端尺寸。回复框固定高度，较长草稿在框内滚动。

8. 高级选项中的会话鼠标默认开启，只设置所选 session 的 `mouse on`，不修改 `.tmux.conf`、全局历史上限或滚轮绑定；同一 session 的电脑端也会受影响，断开后不还原。
9. “工具”中可查看“回复目标 %…”：电脑或手机切换 pane 时原草稿不会自动换目标。切回原 pane 可继续发送；要回复新 pane，先清空草稿，再点“使用当前 pane”。copy-mode、synchronize-panes 或进程重建也会拦截，见 [回复目标修复](docs/releases/0.1.3.md)。显示焦点与尺寸仍和电脑共享。
10. 终端左上角返回按钮或系统返回键回到首页，关闭本应用的交互连接，只显示已有缓存，不触发自动刷新，远端 tmux 任务仍保留。网络异常或退后台保留最后输出并标注为历史，禁用发送。
11. 终端“工具 → 选择复制”打开当前已加载文字的静态副本，长按后拖动系统选区手柄，使用系统菜单复制，也可点击“复制全部”。新输出不会打乱选区；返回终端继续操作。仅包含手机已加载的当前缓冲区，最多保留最近 20 万字符，更早的 tmux 历史需先滚动到对应位置再打开。
12. 断线后点“重连最近任务”，或回首页选择目标。重连核对主机密钥和原服务/pane 身份，不自动发送旧草稿；目标结束或重建时要求重新选择；返回首页后可手动刷新以发现新目标。首页刷新读取任务元数据和用于状态判断的当前 pane 画面，仅保存状态枚举，不会自动 attach 或重放输入。普通 shell 每次都是新连接。
13. 删除服务器会删除本机配置、加密凭据、缓存和最近记录，不影响远端任务；只有其他配置未引用的各级主机信任记录才会删除。修改地址、账号、tmux 路径或跳板链后旧任务缓存失效，改别名保留缓存。
14. 按 [真机验证清单](docs/testing/device-checklist.md) 测试键盘、横竖屏、电脑同时 attach、网络切换和返回前台。真实 Agent 的使用手感仍需要手机复测。

没有 tmux 会话时可用 `>_` 打开 shell 创建，例如 `tmux new-session -s work`。发现失败时检查 tmux 安装、默认 socket 权限及非交互 SSH 的 PATH；可在高级选项填写 tmux 绝对路径。离线不影响编辑和保存配置，缓存中的旧任务连接前仍会重新核对身份。

## 测试

```bash
# Kotlin：shell quoting、元数据解析、主机信任、连接代际隔离
./gradlew :core:test

# 真正的 xterm 解析器：UTF-8 分片、ANSI、alternate screen、粘贴与缓存
(cd web && npm test)

# 实际本地页面与 JS 桥接；需要桌面 Chrome，可用 CHROME_BIN 指定路径
(cd web && npm run test:browser)

# 本机 tmux，多客户端 PTY；只操作临时目录中的独立 socket
python3 scripts/test_tmux.py

# 本机 OpenSSH：临时密钥、loopback 临时端口，不改 ~/.ssh
# 需要 openssh-server、ssh-keygen；部分 Linux 环境需预先存在 /run/sshd
python3 scripts/test_ssh.py
```

SSH 集成用例在普通 `:core:test` 中会明确跳过，只有 fixture 启动后才执行。`scripts/test_ssh.py` 测试未加密和加密 OpenSSH Ed25519 私钥、未知/变化主机密钥、命令 stdout/stderr/退出码、PTY 尺寸、中文输入、普通 shell 执行，以及无 UTF-8 locale 的 tmux 中文/特殊字符名称、链接窗口所属会话和连接前历史；还覆盖两级跳板、每级主机身份、跳板失败清理和不直连回退；密码认证仍需真机测试。测试脚本结束时清理自己创建的服务和密钥，不连接你的生产服务器。

CI 配置见 [.github/workflows/ci.yml](.github/workflows/ci.yml)，默认验证不带语音的 standard；[voice workflow](.github/workflows/voice-benchmark.yml) 获取固定 base 模型，构建 standard/voice 与 benchmark、检查权限/模型/体积/16 KiB 对齐；手动运行该 workflow 时另执行 Android 15 模型/UI smoke。本地测试结果不等于 CI 已实际运行，也不等于 Android 真机兼容性通过。

### 签名发布（GitHub Release）

[.github/workflows/release.yml](.github/workflows/release.yml)：推送 `v*` 标签时获取固定 Whisper 源码/base 模型，用固定密钥签名并校验同证书/包名/版本，然后同时发布 `standard` 与 `voice` APK 及各自 checksum。带 `-` 后缀的标签（如 `v0.8.0-dev`、`v1.0.0-rc.1`）为 pre-release，`v0.8.0` 这类为正式 release。标签须与 `versionName` 一致，`MTMUX_CERT_SHA256` 必须存在且匹配；在 Actions 页手动运行只检查并上传 artifact，不发布。

```bash
# 一次性：生成密钥（离线备份，切勿提交），本地验证并取得公开证书指纹
keytool -genkeypair -v -keystore mtmux-release.jks -alias mtmux -keyalg RSA -keysize 4096 -validity 10000
scripts/verify_release_signing.sh mtmux-release.jks mtmux

# 写入仓库 Secrets / Variables（值不会显示在日志中）
base64 -w0 mtmux-release.jks | gh secret set MTMUX_KEYSTORE_BASE64
gh secret set MTMUX_KEYSTORE_PASSWORD
gh secret set MTMUX_KEY_ALIAS --body mtmux
gh secret set MTMUX_KEY_PASSWORD
gh variable set MTMUX_CERT_SHA256 --body <脚本输出的指纹>

# 发版
git tag v0.8.4 && git push origin v0.8.4
```

release 与 debug 签名不同，已装 debug 版需卸载后才能安装 release 版（本机配置和凭据会丢失）；每次发版需递增 versionCode。


Android 页面回归测试（连接测试设备或模拟器后）：

```bash
# 实际屏幕、配置保存/删除、键盘与触摸历史；SSH/tmux 用例无 fixture 会跳过
./gradlew :app:connectedStandardDebugAndroidTest

# voice flavor（需要先获取固定源码/base 模型）
./gradlew :app:connectedVoiceDebugAndroidTest

# 加上真实 SSH 欢迎语的屏幕像素 → 输入命令 → 点击发送 → 输出与清空的流程
# adb 需要在 PATH 中，或用 ADB 环境变量指定路径；只用临时 fixture 密钥
python3 scripts/test_ssh.py --android
```

0.8.0 的 Android 回归：standard 完整 36 项、voice 完整 41 项均通过；voice 额外覆盖内置模型提取/校验/转写、取消、AudioRecord 和语音 UI，且不自动发送。原有 30 项固定中文界面运行，另有 2 项英文测试覆盖默认英文、窄屏底栏、英文错误提示、应用内切换到中文不触发刷新及无资源语言回退英文。原有用例覆盖隐私展示与截图策略、终端中文标点右边界、私钥粘贴与日志导出、首页任务灯、缓存/过期/多 pane 汇总、真实 tmux 只读采集、底部按键顺序、主题持久化与跟随系统、快捷回复不自动发送与草稿保护、原生手柄、选区与剪贴板、静态副本、目标/跳板分类错误、文件夹/搜索/排序持久化与凭据缓存保留、快捷键三页等宽/翻页和 F1–F12 实际字节、普通历史/全屏终端与真实 tmux 的键盘锚点、像素/键盘、断开行为、密码加密恢复、明文及加密私钥保存后直接认证、密文篡改/错目标拒绝、本地滚动、真实 tmux 滚动和点击、切换 pane 后草稿拦截及显式重新绑定、最近任务持久化/后台重连/旧任务拒绝和不重放、全屏鼠标 TUI、名称与历史，以及首页 30 分钟节流边界与持久化、自动/手动刷新、离线缓存、设置/编辑和两级跳板下的 SSH/tmux；没有 SSH fixture 时，依赖远端的用例明确跳过。测试 fixture 使用用户提供的 `mouse on`、`history-limit 500000` 和滚轮绑定，但仅写入临时 tmux 配置与独立 socket。从 0.6.7 起设备测试直接使用产品截图策略，不再临时取消窗口保护；普通终端像素测试使用默认开启的隐私展示。截图由 Gradle 收集到 `app/build/outputs/connected_android_test_additional_output/`。设备测试不注入产品后门；测试进程通过反射连接临时服务，测试结束清理端口映射和服务。

各版本的修复原因、验证结果与截图见 [CHANGELOG](CHANGELOG.md) 及 [docs/releases/](docs/releases/)。

## 架构与限制

```text
app/       Compose 配置与输入界面、受限本地 WebView、显式主机信任
core/      JVM 可测试的 SSH、tmux 命令/解析、连接代际与主机指纹校验
web/       xterm.js 桥接与测试（不实现 SSH 或终端解析器）
scripts/   隔离 tmux / OpenSSH 集成测试、语音 V0 获取/运行脚本
docs/      版本详细记录、设计计划、P0/V0 报告与真机清单、测试证据（见 docs/README.md）
voice/     voice flavor 的离线模型、JNI、AudioRecord 与草稿决策（standard 不依赖）
voice-benchmark/  独立 whisper.cpp Android 基准，不被正式 app 依赖
```

- SSH 使用 JSch 维护分支；终端使用 xterm.js。终端页只加载本地资产，阻止外部资源和页面跳转；原始 SSH 字节经 Base64 传递给终端，不插入 HTML，不提前按字符串解码。
- 密码、导入私钥文件和口令以 AES-GCM 密文保存在应用私有存储，包装密钥由 Android Keystore 管理；没有明文回退、云同步或额外生物识别弹窗。草稿与终端历史不落盘。服务器资料与主机信任记录也在应用私有存储。没有账号、分析 SDK 或终端日志上传。standard 不声明录音权限；voice 只在用户主动点击后请求麦克风权限，音频和转写不落盘、不写日志、不上传。截图采用下述分级隐私策略；禁用应用备份与设备迁移。卸载/清除数据会失去配置与凭据；系统密钥不可用时提示重新输入，不会自动忽略错误或覆盖旧密文。
- 界面文本在 `app/src/main/res/values/strings.xml`（英文，默认）与 `values-zh/strings.xml`；两份需同时修改。core 只输出错误代码与结构化进度，由界面层翻译；诊断日志字段保持英文且不随语言变化。
- 主机密钥变化后没有“一键忽略”入口。先核对服务器变更，再通过 Android 设置清除本应用数据以重建信任；这会删除所有已信任主机记录。
- 不支持设备专用 SSH 密钥生成、SSH config 导入、自定义 socket、后台保活、Agent 执行确认和自动重连。Agent 状态仅为首页刷新快照的启发式状态灯，不是实时事件。
- 复制选区是用户显式操作；系统输入法、剪贴板有各自的数据处理行为。voice 内置 Whisper 路径离线运行，不调用系统/云语音服务；真实设备的速度、温升和识别质量仍待验证。
- minSdk、tmux 最低版本、真实 Agent TUI、触摸选区与输入法体验仍需按设备清单验证。

组件与许可证说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。


## 使用说明：私钥与问题反馈

### 导入私钥

添加或编辑服务器，在“认证方式 → 私钥”选择“选择文件”或“粘贴私钥”。粘贴弹窗中可长按粘贴，也可点“从剪切板粘贴”；默认隐藏内容，可临时显示。应包含完整的 BEGIN / END 行，支持 OpenSSH/PEM 文本私钥，不接受公钥，最大 64 KiB。这里只检查文本封装，私钥是否可解密/可登录仍由 SSH 连接验证。

点击“导入”后填写下方可选的私钥口令，再保存服务器。取消弹窗不替换原私钥；仅导入而未保存服务器不会落盘。各级 Jump Host 的认证区使用同样入口。凭据继续由 Android Keystore 保护后保存；应用不会自动读取或清空剪切板，不把私钥写进调试日志。

### 生成调试日志

应用从进程启动起自动记录少量运行事件。复现问题后进入“设置 → 调试日志 → 生成日志”，选择保存位置，把生成的 `.txt` 文件连同操作步骤、发生时间、预期/实际表现一起反馈。点击生成时已截取快照，之后发生的操作需重新生成。没有自动上传。

记录内容包括应用/Android/WebView 版本、启动时间、前后台切换、SSH 连接阶段与错误类别、首页探测批次、终端行列/可见高度、首次渲染、累计输出字节数、输入被阻止/失败、私钥导入方式，以及 voice 权限结果、录音开始/停止、录音秒数、转写耗时和固定错误事件；另含标点处理阶段、耗时与回退标记，以及异常类型和有限代码调用位置。服务器仅用本地配置标识的数字摘要关联，不记录别名、地址、用户名、密码、私钥、私钥口令、剪切板、命令、终端正文、语音音频、转写文字、识别语言或原始异常消息。

连接性能日志 v2（导出头 `build=connection-perf-v3`）细分 DNS、TCP、服务器 banner、密钥交换、认证、SSH 就绪、tmux 列表/准备/状态读取、通道打开、命令等待、终端打开和首批字节；每段记录开始、结果和单调时钟耗时，含尝试编号、跳板序号、操作/父操作编号、退出码及字节数。取消尝试单独记为 `CONNECTION_CANCELLED`，复用记为 `SSH_REUSED`；刷新交接请求与结果记为 `REFRESH_HANDOFF_REQUEST` / `REFRESH_HANDOFF`。字段含义、跳板计时边界和复测步骤见 [连接性能说明](docs/testing/connection-performance.md)。原始日志可放在被 Git 忽略的根目录 `debug-log/`。

内部日志是两个各最多 5 MiB 的滚动文件（总计不超过 10 MiB）；通常能覆盖本次启动以来的过程，但事件较多时会覆盖最早记录，不能保证无限期保留完整历史。队列也有上限，丢弃计数会写入导出文件。日志可跨重启保留，设置中的“清空日志”会删除历史，之后继续记录。用户主动导出到外部的文件由用户管理，不受内部轮转删除影响。

Java/Kotlin 未捕获异常会尽力补写一条崩溃记录；强制结束进程、系统杀进程、原生崩溃或存储故障不保证留下最后一条日志。日志不含远端正文，需要排查具体 Agent 输出时仍需另附脱敏片段。

### 首页任务状态灯

红：可能需要回答或处理；黄：正在运行；绿：本轮结束或 Agent 已就绪；灰：未知、过期或刷新失败。颜色来自上次刷新采集到的标题/画面，不是实时监控，也不保证任务目标成功。多 pane 优先红、其次黄，全部结束/就绪才绿。具体 CLI 版本/主题可能影响识别；手动刷新可获取新快照。此说明保留在文档，设置页不再展示，可作为后续 Wiki 的基础。

## 隐私展示与截图

设置中的“隐私展示”默认开启，升级也默认开启，选择保存在本机：

- **开启**：首页、终端标题、移动/删除提示与发送确认隐藏自动展示的用户名、IP、域名和端口。有别名显示别名，无别名显示持久的“服务器 N”编号；删除其他服务器、排序和重启不会重排已分配编号。旧版在别名留空时自动保存的“用户名@地址”也按无别名处理。普通页面允许截图和录屏。
- **关闭**：恢复显示用户名和地址，有别名时同时显示别名；整个应用禁止截图和录屏。
- **始终保护**：添加/编辑服务器（含多级跳板）、私钥粘贴窗口、完整连接详情和主机指纹核对始终禁止截图。关闭嵌套窗口不会提前解除外层凭据页保护。
- **最近任务预览**：Android 13+ 使用系统接口禁用预览截图；应用暂停时同时启用安全窗口标志，为旧系统提供保护。旧版 Android 与厂商最近任务实现仍需真机验证。

隐私展示只隐藏应用自动展示的连接字段；自定义别名、文件夹名、tmux 名称、搜索输入以及终端输出/草稿/复制内容不自动脱敏。它们可能包含地址或其他敏感内容。凭据仍使用原有本机加密，日志规则不变。历史版本报告中的“全局防截图”和“测试临时取消保护”描述仅适用于当时版本。

## 许可证

项目自有代码采用 **GNU General Public License v3.0 only**，SPDX 标识为 **GPL-3.0-only**，仅授权 GPL 第 3 版，不包含“或任何后续版本”。完整许可文本见 [LICENSE](LICENSE)；软件不提供担保。

第三方组件保留各自许可证及版权声明，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。APK 内随附本项目许可文本和许可声明。许可证标识含义见 [SPDX 官方说明](https://spdx.org/licenses/GPL-3.0-only.html)。
