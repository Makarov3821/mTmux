# 0.9.3 完整自动化测试记录

本轮以 versionName `0.9.3` / versionCode 44 为发布候选，完整检查普通版与语音版。首轮候选为 `4fe2350`，测试同步调整为 `f890733`、`2587af5`；SSH 交接的主线程修复为 `9ded6a5`。最终以该修复重新执行完整矩阵。仅在必需检查完成并通过后推送正式标签。

## 范围与环境

- 专用 `emulator-5554` / `mtmux-test`，Android 15 / API 35 google_apis x86_64，Google WebView 124.0.6367.219。设备环境采集在 `.tools/diagnostics/release-093/env-before/`。
- standard 与 voice 全部功能测试类、`UiRedesignComponentTest`；voice 另含 `VoiceProductTest` 的真实中英文 X-ASR 推理、取消、旧模型迁移及录音资源释放。
- `UiCatalogTest` / `UiTerminalCatalogTest` 是显式 opt-in 的文档截图生成器，不计作功能回归，也不因排除它们宣称零跳过。
- JVM：core、voice 与两版 app；Python 资产/下载测试；真实宿主 SSH/SFTP/tmux 集成；Node 和实际打包页面的桌面 Chromium；两版 Debug/Release、测试 APK 编译、lint 与模型/runtime/权限/大小/对齐检查。
- 真实 SSH/tmux 与 Android 测试使用隔离 loopback sshd、临时密钥与专用 tmux socket；为允许 PTY 在沙箱外执行。不访问用户保存的服务器、不修改默认 tmux。
- 逐类执行 Android 测试，保存每类原始 console、XML、单项日志，并与预期方法清单核对：standard 为 12 类 78 项，voice 为 13 类 84 项。脚本 `DONE` 和退出码本身不作为通过证据；失败及重跑分别保留。
- `DirectTerminalInputTest` 中用户要求暂停的两项长按退格实验保持注释，不计入当前启用的 78/84 项。没有为凑齐计数重新启用它们。
- 本机没有连接 ARM 实机；不将模拟器录音、输入法和布局验证称为真实麦克风质量或所有厂商输入法验收。没有改动独立 Audio Lab。

## 结果

| 检查 | 结果 |
| --- | --- |
| Python 下载/资产测试 | 7/7 |
| Node 终端与链接测试 | 13/13 |
| 实际打包页面 Chromium 回归 | 通过，file origin（`TERMINAL_TEST_FILE=1`） |
| core（真实 SSH/SFTP 夹具） | 77/77，0 跳过 |
| app standard / voice JVM | 各 44/44 |
| voice engine JVM | 4/4 |
| 两版 Debug、Release、instrumentation APK 编译 | 通过 |
| 两版 lint | 通过 |
| 宿主 tmux 与 SSH/PTY 集成 | 通过 |
| 模型与运行库锁文件、两版权限/载荷分离 | 通过 |
| 两版 APK 16 KiB ZIP 对齐 | 通过 |
| standard Android 功能测试 | 最终完整矩阵 78/78，0 失败 / 0 跳过 |
| voice Android 功能测试 | 最终完整矩阵 84/84，0 失败 / 0 跳过；含真实 X-ASR 中英文推理 |

最终轮构建输出、169 项 JVM 计数、源码树哈希和逐阶段命令保存在 `.tools/diagnostics/release-093/final/`。最终 Android XML 和各类 console 保存在 `.tools/diagnostics/matrix/full-093-{standard,voice}-20261102-final/`。

独立核对 `final/final-proof.json` 确认所有预期类和方法均实际执行、每类 runner 成功、XML 无失败/错误/跳过，且最终两版日志均未发现 WebView 跨线程调用。最终轮基于 `9ded6a5` 的产品与测试源码；后续发布记录提交只修改文档，通过 `app`、`core`、`voice`、`web`、`scripts`、`.github` 树哈希核对一致。

启用的 Android 功能测试清单如下；两版分别执行相同的公共用例。

| 测试类 | 每版用例数 |
| --- | --- |
| DirectTerminalInputTest | 5 |
| LanguageUiTest | 2 |
| PreviewInteractionTest | 7 |
| ServerManagementUiTest | 3 |
| SshRetentionUiTest | 2 |
| TerminalImeFocusTest | 2 |
| TerminalKeyboardTest | 2 |
| TerminalUiTest | 37 |
| TmuxDoctorUiTest | 5 |
| UiRedesignTest | 6 |
| UiRedesignComponentTest | 4 |
| UiRefreshTest | 3 |
| VoiceProductTest | 6，仅 voice |

## 失败记录与修复过程

所有原始失败均保留在 `.tools/diagnostics/`，本地诊断目录和截图不提交到仓库。

| 执行轮次 | standard | voice | 后续处理 |
| --- | --- | --- | --- |
| `full-093-*-20261101` | 76 通过 / 2 失败 | 82 通过 / 1 失败 / 1 未执行 | Markdown 返回后点击、私钥欢迎文字检查和语言重建等待 |
| `full-093-*-20261102-round2` | 76 通过 / 2 失败 | 82 通过 / 2 失败 | 预览等待线程错误、任务窗口自动命名、Jump Host 导入等待 |
| `full-093-standard-20261102-final-classes` | 预览 7/7；终端 36/37 | 未执行该阶段的 voice | 私钥第二次连接发现 WebView 跨线程调用，转为产品修复及重新全量 |
| `fix-093-standard-handoff-main-20261102` | 私钥恢复定向 1/1（包含普通/加密两种密钥） | 留待最终完整矩阵 | 主线程修复后的定向验证通过 |

- 语言切换：直接切换系统 locale 后，测试线程卡在 Espresso/Compose 等待。保留截图、线程栈和 runner 输出后终止该次 instrumentation；该类另一项未执行。测试现在先等待新语言的 Activity 恢复并绘制首帧，再进入 Compose 查询；两版第二轮该类均 2/2。
- Markdown 返回：等待恢复文档及图片加载后，再等待 WebView 可见帧才计算点击坐标。新增等待曾误在主线程内调用测试线程专用查找函数，已修正为先获取 WebView，再在主线程请求帧；普通版整类复测 7/7。
- 任务探测：两次读取之间，tmux 自动将测试窗口从 `bash` 改名为 `sleep`。测试用 `new-session -n status-fixture` 固定窗口名，完整对象、状态及焦点断言保留。
- Jump Host：语音版一次等待“从已保存服务器选择”超时，其他已完成轮次通过。补充分组弹窗退出、IME 收起以及开关从关闭到开启的明确断言；新增失败节点树和截图采集。原始超时的底层原因尚未单独证实。
- 私钥恢复：欢迎文字检查改为完整终端快照，避免假定其总在第 0 行。后续整类复测又在第二次连接就绪前失败；logcat 明确记录 `ServerHome.open → openTask → connectTask → disconnect → TerminalView.connection` 在 `DefaultDispatcher-worker-4` 调用 WebView。交接协程显式指定 `Dispatchers.Main.immediate`，并增加连接阶段失败诊断。修复后重新执行两版完整矩阵，未沿用旧产品代码的结果作为最终 Android 通过证据。

首轮/第二轮核对报告分别在 `.tools/diagnostics/release-093/android-audit-final.json`、`android-audit-round2.json`；最终轮使用独立的 `final/` 目录及 `full-093-*-20261102-final` 标签，避免覆盖历史失败。

## 远端发布核验

本地通过后推送最终提交，等待该提交的 `Android checks` 与 `Voice checks` 成功，再推送正式标签 `v0.9.3`。由现有 `Release APK` 工作流使用 GitHub 签名证书构建两份 APK；正式版入口为 [0.9.3 Release](https://github.com/Makarov3821/mTmux/releases/tag/v0.9.3)。

发布后下载 standard / voice APK 及各自 `.sha256`，核对 GitHub asset digest、SHA-256 文件、既有签名证书、包名 `dev.mtmux`、版本 `0.9.3` / 44、权限与模型载荷、16 KiB ZIP/ELF 对齐，以及 APK 内被跟踪静态资产与源码一致；记录保存在本地 `final/published-verification.json`。本机生成的 unsigned Release APK 用于构建验证，用户分发使用 GitHub 正式签名文件。
