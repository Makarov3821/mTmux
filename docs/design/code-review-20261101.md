# 源码审查记录（2026-11-01）

基准提交：`2536851`（0.9.3-rc.3）。范围是源码实现，不含测试和文档。四个子 agent 分别通读 core、存储与配置、终端链路、首页 / 语音 / 发布流程，再由主 agent 对着代码核实主要发现。“核实”一栏写“已核实”的条目，都对照过代码；写“子 agent 报告”的只来自子 agent，未逐行复核。

总体结论：未发现高危漏洞（没有命令注入、主机指纹绕过或凭据泄漏）。问题主要在错误提示映射、几处并发与数据健壮性、发布流程的供应链加固，以及几个过大的函数。

## 1. 值得修

| # | 级别 | 位置 | 原因 | 建议 | 核实 |
|---|---|---|---|---|---|
| 1 | 中 | `app/.../ServerHome.kt:147`、`NewTmuxDialog.kt:69`、`TmuxDoctorDialog.kt:143`；`UiText.kt:101` `errorText()`；`CredentialCipher.kt` `decrypt()` | 凭据解不开时（Keystore 密钥失效、密文损坏），`AEADBadTagException` 落入 `errorText` 的 else 分支，被 `connectionFailureReason` 当成网络问题，提示成连接失败；`require(iv.size == 12)` 的 `IllegalArgumentException` 显示为“验证失败”。只有终端入口 `MainActivity.openTask` 单独捕获并提示“凭据无法读取”，首页刷新、新建会话、Doctor 都没有 | 在 `ServerProfiles.credentials()` 里把加解密异常统一转成 `AppError(err_keystore_unavailable / err_credential_format)` | 已核实 |
| 2 | 中 | `app/.../MainActivity.kt:627-630`（`onResize`） | 每次尺寸变化都单独 `scope.launch(Dispatchers.IO) { current?.resize(c, r) }`。IO 线程池不保证顺序，旋转或改字号连续触发时，远端 PTY 可能停在旧尺寸 | 串行执行（单线程执行器或 `Channel` 只保留最新值），或先合并抖动 | 已核实 |
| 3 | 中 | `.github/workflows/ci.yml`、`voice.yml`、`release.yml` 的 `uses:` | 第三方 Action 使用可移动标签（`android-actions/setup-android@v4`、`gradle/actions/setup-gradle@v5` 等）。`release.yml` 有 `contents: write`，且同一 job 已解码签名密钥；仓库公开，上游标签被劫持时签名密钥会暴露 | 至少把 release 流程中的第三方 Action 固定到 commit SHA | 已核实 |
| 4 | 中 | `.github/workflows/release.yml` 的 “Fetch pinned X-ASR” 步骤（`scripts/fetch_voice.py`） | 约 170 MB 模型的下载脚本与已解码的 keystore 在同一 job、同一环境中运行。下载已固定 SHA-256 和大小，篡改风险低，但出问题时影响范围包含签名密钥 | 拆成不带密钥的 job 下载并校验，再把产物交给签名 job | 子 agent 报告 |
| 5 | 低 | `.github/workflows/release.yml:57` | `echo "Decoded keystore: ... sha256 $(sha256sum ...)"` 把 keystore 文件哈希写进公开日志。不是私钥本身，但属于不必公开的密钥元数据 | 删除该行，需要时在本地对比 | 已核实 |
| 6 | 低 | `app/.../ServerCache.kt:11-15` `read()` | 任务列表在外层 `runCatching` 里整体 `map { RecentTask.decode(...) }`，任一条解析失败，整台服务器的缓存（任务和状态灯）都被丢弃。`ServerProfiles.recent()` 已是逐条容错 | 改为逐条 `runCatching` 后 `mapNotNull` | 已核实 |
| 7 | 低 | `app/.../RecentTask.kt:19` `binding` | `identity.split(':')[2]` 在字段不足三段时越界。目前持久化路径在 `decode()` 时已校验，暂不会触发 | 字段不足时返回 null | 已核实 |
| 8 | 低 | `app/.../DebugLog.kt:77-85` `snapshot()` / `clear()` | 向容量 128 的单线程队列 `submit`，队列满时抛 `RejectedExecutionException`，没有像 `enqueue()` 那样处理，导出或清空日志直接失败 | 捕获该异常，或给控制命令单独通道 | 已核实 |
| 9 | 低 | `app/.../MainActivity.kt:66,165,202`（`closer`） | 旧连接交给 `closer` 线程 `close()`，没有超时；`onDestroy` 只调 `shutdown()`，遇到挂住的 socket 该线程会一直占用 | 给 close 加超时，或明确依赖 JSch socket 超时并写注释 | 已核实 |
| 10 | 低 | `app/.../MainActivity.kt` `latest()`（约 490 行） | 回到底端完成后只比对 `token == wireToken`，没有比对 `client === connection`（写入路径有）。token 含尝试号和 epoch，实际触发概率很低 | 与 `writeInput` 一样同时比对连接对象 | 已核实 |
| 11 | 低 | `app/.../ServerHome.kt` 刷新的 `finally`（`transfer = retain && active && refresh.opening.get() && ...`）与 `open()` 里 `refresh.opening.set(true)` | 首页刷新和点开任务同时发生时，有很窄的窗口让连接进入连接池而不是交给终端，终端只能重新拨号。最坏多一次 SSH 认证，不泄漏连接 | 用锁把“决定连接归属”和“设置 opening”放在一起 | 子 agent 报告，未复现 |

## 2. 安全加固（目前无可利用漏洞）

| 级别 | 位置 | 原因 | 建议 |
|---|---|---|---|
| 低 | `app/.../TerminalView.kt:52` 起的 WebView 设置 | 已关闭文件 / 内容访问、拦截所有跳转与外部请求，CSP 为 `default-src 'none'`。混合内容和权限类回调仍是平台默认值，未来放宽跳转限制时会继承默认行为 | 显式设 `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW`，地理位置、文件选择等回调直接拒绝 |
| 低 | `core/.../Tmux.kt:10`（`resume`）、`:60` 起（`leaveCopyMode(binding)`） | 用 `'${binding.target}'` 手工加单引号，没走 `quote()`。`PaneBinding.parse` 只接受纯数字格式，目前安全，但与其他调用点不一致 | 统一改用 `Tmux.quote()` |
| 低 | `app/.../ServerProfiles.kt:65-71` `credentials()` | 解密后密码和口令成为 `String`，只有原始字节被清零，`String` 留在内存直到回收（JVM 通病） | 接受并写明；要彻底解决需全程改用 `CharArray` |
| 低 | `core/.../ConnectionFailure.kt:9` | `ConnectionFailure` 保留 JSch 原始异常为 cause。现在日志只记类名和行号，安全；以后有人记录完整异常就会写出主机、认证细节 | 在类上注明 cause 不可记录，或对外异常不带 cause |
| 改进 | `app/.../TerminalView.kt` `Bridge.copy()` | 用户主动复制的终端选区可能含敏感输出 | Android 13+ 给 `ClipDescription` 加 `EXTRA_IS_SENSITIVE`，系统预览不显示内容 |

## 3. 可维护性与改进

| 位置 | 原因 | 建议 |
|---|---|---|
| `app/.../TerminalKeyInput.kt`（`TerminalImeSession`、`StickyInputConnection`）、`TerminalKeyStroke.kt:77`（`OneShotInputGate`） | 产品代码没有调用，只有测试在用；实际使用的是 `DirectTerminalInput`（`terminalHardwareKey` 仍在用）。输入法代码留两套容易改错地方（已核实） | 删除死代码和对应测试，保留 `terminalHardwareKey` |
| `app/.../DirectTerminalInput.kt` `delete()`、`TerminalDraft.kt` | 大段注释掉的实验代码 | 删除，需要时从 Git 历史找回 |
| `app/.../MainActivity.kt` `App()`（约 700 行）、`connectTask()`（约 120 行） | 几十个相互牵制的状态变量，各处零散的 token / epoch 判断由此而来 | 抽出连接状态对象（ViewModel），浮层拆成独立组件 |
| `app/.../ServerHome.kt` `ServerHome()`（约 500 行） | 刷新、连接转交、分组渲染、对话框混在一起，第 1 节 #11 这类并发问题难以审查 | 先把刷新与连接转交抽成控制器 |
| `app/.../ServerEditor.kt`（约 200 行的组合函数） | 校验、跳板导入、文件夹、验证流程混在一起 | 拆分认证字段校验、跳板编辑、保存决策 |
| `core/.../TmuxDoctor.kt`、`TmuxConfigBootstrap.kt` | 大段 shell 脚本写在 Kotlin 字符串里。安全上很细（`set -f`、`umask 077`、`ln -T`、inode + 哈希回执），但一个转义写错就会改变行为 | 移到 `.sh` 资源文件，并对生成的脚本加单元测试 |
| `core/.../Tmux.kt` `discover`、`TmuxPreparation.kt`、`TaskCatalog.kt` | tmux 字段格式和查询范围（`-a` / `-s -t`）的拼接各写一份，改一处漏一处会悄悄解析出错 | 集中成一个格式常量和一个范围构造函数 |
| `core/.../AgentSignal.kt` `inspect()` | 每次调用都重新编译多个 `Regex`，首页每次刷新对每个 pane 都调用。已确认无 ReDoS | 把正则提成常量；按 Agent 拆分逻辑 |
| `MainActivity.submitBytes`（64 KiB）、`TerminalWriteQueue`（64 KiB）、`web/protocol.js` `validatePaste`（32 KiB）；`PrivateKeyPasteDialog`（字符数）与 `ServerEditor` 文件导入（字节数） | 同类上限散落多处、单位不一 | 统一成命名常量并注明用途 |
| `core/.../SshClient.kt` `exec` 输出上限 512 KiB 与 `discoverTaskSnapshots`（8 个 pane × 24 KiB，Base64 后约 262 KiB） | 两者之间的约束没写出来；调大批量或单 pane 上限会让每次刷新失败 | 写注释或加断言 |
| `core/.../ConnectionFailure.kt:10-27` `connectionFailureReason()` | 靠英文错误消息分类。JSch 消息是写死的英文，目前可用 | 能按异常类型判断的优先按类型 |
| `web/terminal.js`（`terminal._core.viewport.scrollBarWidth = 0`） | 使用 xterm 私有字段；版本已锁定在 5.5 | 升级 xterm 时重新验证终端宽度 |
| `app/.../ScrollSpeed.kt` `savedScrollSpeed()` | 读取旧值时顺手写盘 | 分成单独的迁移步骤 |

## 4. 已确认没问题的部分

- SSH 主机信任：每一级主机单独核对指纹，指纹按完整跳板路线区分；经跳板转发时用 `setHostKeyAlias` 核对真实目标。没有回退直连，密钥变化时没有一键信任。
- 远端命令统一 shell 转义；会话名拒绝 `:`、`.`、控制字符，最长 128 字节。未发现注入。
- 凭据：AES-GCM，每次随机 IV，AAD 绑定服务器 ID、地址和跳板路线；解密时不会重建密钥。SharedPreferences 中的明文只有非敏感元数据。删除服务器会清掉凭据、缓存、最近记录、Doctor 记录和不再被引用的主机信任。
- Manifest：禁用备份和设备迁移，禁止明文流量，只有启动页对外，调试 Activity 不对外。
- 调试日志只记事件码和数字，不记主机、命令、终端内容；导出由用户选择位置。
- 终端：远端输出调不到 JS 桥接方法，所有写入在主线程重新核对 token；OSC 52 剪贴板、链接等转义功能未启用。`web/` 与 `assets/terminal/` 下三个文件逐字节一致。
- 语音：仅在用户按下时录音，`AudioRecord` 在所有路径释放，音频和转写不落盘；模型在构建和运行时都校验 SHA-256，解压只认固定文件名。standard 版无录音权限和语音代码。
- CI 用 `pull_request`（不是 `pull_request_target`），权限只读。

## 5. 排除的报告

- `core/.../SshClient.kt` `exec()` 超时被认为重复计算了建立通道的时间。核对后，`overallDeadline` 本就是包含建立通道在内的总预算，符合设计，不算问题。

## 6. 建议顺序

1. 第 1 节 #1、#2、#6，加上删除第 3 节的死代码：改动小、风险低。
2. 下次发版前：第 1 节 #3、#4、#5（固定 Action、拆分 release job、删哈希输出）。
3. 之后再排：拆分 `MainActivity`、`ServerHome`、`ServerEditor`，并顺带处理 #9、#10、#11。
