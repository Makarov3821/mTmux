# P0 技术验证报告

日期：2026-09-23。状态：**原型与本地自动化验证已完成，Android 真机与实际 Agent 验收待执行，P0 尚未整体关闭。** 用户计划稍后使用 Android 手机连接自己的测试服务器。

## 当前交付

- 可构建的 Kotlin/Compose Android 工程及 debug APK。
- SSH 密码/导入密钥入口、显式主机密钥信任、tmux ID 发现、共享 session attach、普通 SSH PTY。
- 本地 xterm.js 终端、原始字节传输、渲染背压、尺寸调整、中文输入框、预览粘贴、独立按键。
- 连接代际隔离、断线不重放、过期输出过滤；粘贴回调进入发送队列前暂时禁用 Enter。
- README、依赖声明、真机清单、隔离集成测试及 CI 工作流。

## 组件决策

| 层 | P0 选择 | 理由与剩余风险 |
| --- | --- | --- |
| 界面 | Kotlin 2.2.21 + Compose BOM 2025.10.01 | 原生配置和 IME 编辑；版本固定，未追求最新依赖 |
| SSH | mwiede/JSch 2.28.7 + BC 1.83 | 有维护的 SSH 实现、PTY 与现代密钥支持；JVM 及 Android 15 未加密 Ed25519 握手已测；加密私钥与其他系统仍待验证 |
| 终端 | xterm.js 5.5.0 + addon-fit 0.10.0，本地 WebView | 不自行实现 VT；许可证适合做候选；触摸选区、IME、tmux 历史与 WebView 生命周期是实测重点 |
| 构建 | AGP 8.13.2 / Gradle 8.13 / JDK 17 | 已实际编译，wrapper 固定官方分发 SHA-256 |
| Android | compile/target 36，min 26 | minSdk 是暂定范围，不等于 Android 8 已通过实机兼容验证 |

SSH 私钥、密码和草稿仅保留在进程内存；P0 尚未实现专用密钥生成或凭据加密持久化，不应提前承诺 Keystore 密钥兼容性。主机公钥信任记录可以持久保存，不保存终端历史。自动化测试依赖不进入 APK。

## 已执行验证

环境：Linux x86_64、本机 tmux 3.4、JDK 17；SSH 使用独立 OpenSSH 服务，只监听 loopback 临时端口。tmux 使用临时目录中的独立 socket，不修改现有服务或会话。

| 检查 | 结果 | 证据与边界 |
| --- | --- | --- |
| Kotlin 核心单元测试 | 6 项通过 | quoting 实际经过 shell round-trip、非法目标/路径、元数据破坏、主机信任、连接代际、配置校验 |
| 真实 SSH 集成测试 | 4 项通过 | 未知/变化主机密钥拒绝、明文及加密 OpenSSH Ed25519、stdout/stderr/退出码、PTY resize、中文往返、普通 shell 草稿与回车执行、断开后拒绝旧 token；不包含密码认证 |
| 真实 tmux 行为测试 | 4 项通过 | 共享焦点、smallest 尺寸变化、断开保活、不寻常名称不干扰 ID 记录 |
| xterm 解析器测试 | 4 项通过 | 中文 UTF-8 分片、ANSI、alternate screen、粘贴校验、12,000 行输出缓存限制 |
| 实际终端页面浏览器测试 | 通过 | 本地打包页面可运行，桥接回调、中文、离线/过期连接输入拒绝、bracketed paste、粘贴完成顺序、容器 resize；桌面 Chrome，不能代替 Android WebView |
| Android 15 屏幕与 SSH 流程 | 2 项通过，无跳过 | 480dp / 360dp 模拟器：实际文字像素、欢迎语、命令输出、配置切换与键盘；见显示回归证据 |
| Android debug 编译 | 通过 | `app/build/outputs/apk/debug/app-debug.apk` |
| Android lint | 0 错误，7 个版本更新提示（含新增设备测试依赖） | 固定依赖版本；报告在 `app/build/reports/lint-results-debug.html` |
| 原生库及 APK 页面布局 | 当前 debug 包检查通过 | Compose 引入 `libandroidx.graphics.path.so`；四个 ABI 的 ELF LOAD 对齐均为 16 KiB，APK zipalign 16 KiB 检查通过；仍需 16 KiB Android 环境实际安装运行 |
| CI | 已配置，未在远端运行 | `.github/workflows/ci.yml`；不能把本地结果记为 CI 通过 |

重新执行方式见 README。JUnit XML 与 HTML 报告位于 `core/build/test-results/test/` 和 `core/build/reports/tests/test/`；普通无 fixture 的 `:core:test` 会跳过 4 项 SSH 集成测试，`scripts/test_ssh.py` 则执行全部 10 项且不跳过。

## 多客户端结论与架构门槛

1. 普通 attach 不隔离焦点：两个客户端接入同一 session，选择 window/pane 后看到相同的目标 ID。P0 故意保留此行为以便在真实工作流中复现，UI 明确提示“共享焦点”。
2. `window-size=smallest` 时，小尺寸客户端影响共享 window；测试把手机 PTY 从 50×20 改为 42×12，远端 window 随之变化。此结论只针对测试策略，不代表用户所有配置都这样表现。
3. 断开一个客户端后，另一个客户端仍存活，远端 pane ID 和 PID 不变。P0 不执行 detach-other、kill-session、exit 或 Ctrl-C 来实现退出。
4. 不在用户的全局 tmux 配置里静默设置窗口尺寸或焦点策略。

**进入 P1 前必须解决目标投递方案。** 当前按 session 分组的草稿和普通 PTY 输入不是 pane 级安全投递。候选方案需要在确定最低 tmux 版本后验证：独立客户端/会话策略、直接针对稳定 pane ID 的输入通道及相应的重启身份核对。仅增加一次发送前查询不能消除查询与发送之间的焦点竞争；不得据此宣称已经隔离。

## 未通过或待验证的门槛

| 项目 | 当前状态 / 下一步 |
| --- | --- |
| Android 真机安装与密码认证 | 按 device-checklist D01 执行；当前没有连接真机 |
| Android 私钥及最低系统版本 | Android 15 模拟器未加密 Ed25519 已通过；加密私钥、真机和最低系统版本待测 |
| Codex / OpenCode 等真实 TUI | 尚未连接用户 Agent，不记录虚构兼容结果 |
| 中文 IME、语音、触摸、复制和 tmux 滚动 | 桌面解析器通过；手机行为待测 |
| 前后台、网络切换、输入瞬间断网 | 核心隔离和离线 JS 已测，手机端需端到端验证 |
| 独立 pane 焦点与目标投递 | 未实现；P1 架构门槛 |
| 服务重启后的目标身份 | 当前 ID 发现不足以排除重启后复用；P1 需重新核对，P0 不自动重连 |
| 最低 tmux 版本 | 仅验证 3.4；暂不声称支持更早版本 |
| 完整许可证与发布审查 | 已保存主要直接依赖许可证；传递依赖、商店材料和最终发布仍待完成 |

## 参考

- [tmux 手册](https://man.openbsd.org/tmux)：session/window/client 共享行为、窗口尺寸和 PTY。
- [JSch 维护分支](https://github.com/mwiede/jsch)：认证、算法和 Bouncy Castle 说明。
- [xterm.js API](https://xtermjs.org/docs/api/terminal/classes/terminal/) 与 [编码说明](https://xtermjs.org/docs/guides/encoding/)：按字节写入、UTF-8、onData、paste。
- [Android 备份规则](https://developer.android.com/about/versions/12/backup-restore#xml-changes)：显式排除云备份和设备迁移。

## 0.0.2：修正命令发送与终端可见性

用户反馈：输入框写好命令后按输入法回车或上方 Enter，看不到执行结果。确认旧版文本框是本地草稿，而快捷 Enter 只给远端发送回车，未包含草稿，且缺少发送反馈。

已修改：

- 明确标为“输入命令或回复”，提供“发送并回车”和“仅粘贴”。有草稿时快捷 Enter 走完整草稿发送，无草稿时仍发送单独回车。
- 使用 xterm 实际粘贴编码，将文本和可选回车合为一次有序写入。写入成功后清空对应草稿；若发送过程中用户编辑了草稿，不删除新内容；失败保留草稿，不自动重放。
- 多行仍需预览和 bracketed paste；输入法回车负责换行，不隐式执行。
- 配置迁入独立面板，连接时收起输入法；工作区在小窗口中滚动，保留终端高度；提供“收起键盘”和未收到输出的提示。
- 增加解析确认字节数（旧标签“已显示”不准确，0.0.3 更正为“已解析”）与 WebView 加载/运行错误提示，便于区分 SSH 通道建立、收到输出和页面异常。
- 增加普通 SSH shell 执行回归、浏览器草稿原子发送回归，以及 Android 页面/真实 shell 端到端测试。当时仅编译测试 APK；软件模拟器启动失败，设备用例未执行。当时“缺少硬件虚拟化”的判断有误：沙箱内看不到 `/dev/kvm`，在获准的宿主执行环境中可以使用 KVM。0.0.3 已使用硬件加速 Android 15 模拟器实际执行回归。

## 0.0.3：复现并修复空白终端

用户反馈 Android 15 上终端为 42×1、已解析 1254 字节但没有欢迎语或提示符。模拟器复现：原生 WebView 为 464×479，网页视口也为 464×479，而百分比高度的终端容器为 464×0，xterm 因此退化为 52×1。SSH 和解析确认成功不能证明文字已经绘制。

修复：将终端容器改为 `position: fixed; inset: 0`，直接填满 WebView 视口；保留 ResizeObserver 自动适配键盘及尺寸变化。工具栏字节统计更名为“已解析”，避免误导。

回归测试使用 Android 15 + 本地临时 SSH：在输入任何命令前检查欢迎语的真实屏幕像素；同时验证中文输出、配置面板切换、软键盘收起后的可见输出、发送命令的返回值和草稿清空、断开后草稿保留。检查容器高度与视口相符且行数大于 1。截图仅包含合成测试内容，生产防截图逻辑不变。

修复前两项像素测试失败；修复后在 480dp 和 360dp 宽度模拟器通过。证据与截图见 [Android 15 显示回归](evidence/terminal-android15/README.md)。桌面浏览器、4 项 xterm 解析测试亦通过。仍需用户手机覆盖安装后确认，不能据模拟器结果关闭整个 P0 验收。

## 0.0.3 手机复测反馈

用户反馈“现在似乎可以了”，终端空白问题获得初步正向确认；未据此宣称完整真机或 Agent 验收通过。用户同时指出：断开后旧内容造成仍连接的错觉、连接配置不能保存、tmux 列表缺少自定义名称、终端无法滑动查看历史。

现有断开实现会使输入 token 失效并关闭 SSH，但保留终端缓冲区；残留文字本身不能证明连接仍然存活，状态反馈需要改善。发现接口当前仅请求稳定 ID，缺少名称字段。以上已拆成 [P1 首批任务](../plan.md)，包括具体行为与验收；历史滚动仍是 P0 组件验证的未完成项。本次只更新计划与反馈记录，未修改 APK。
