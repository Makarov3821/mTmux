# tmux Doctor 验证记录

日期：2026-10-29。测试使用本地临时文件、专用 tmux socket、一次性 loopback OpenSSH 和专用 Android 15 软件模拟器，没有连接用户真实服务器。

- [unit-tests.json](unit-tests.json)：核心套件 72 项，其中执行 49 项、23 项为未提供 SSH fixture 时的按需跳过；失败和错误均为 0。包含 9 项 Doctor 核心测试。App standard 单元测试执行 38 项，全部通过。
- [ssh-tests.json](ssh-tests.json)：额外执行 2 项一次性 OpenSSH 集成测试，均通过，验证同链诊断的一次握手及明确命令超时后继续使用连接。
- [android-ui.txt](android-ui.txt)：4 项原生界面测试通过，覆盖邀请跳过、版本提示、主页创建限制和过期恢复、断网时保留服务器且不误报 tmux 缺失。
- [touch-row-ui.txt](touch-row-ui.txt)：更新后的本地触摸关闭/开启提示与版本行已定向重查，通过。
- [android-ssh-flow.json](android-ssh-flow.json)：完整指纹确认及保存 → Doctor 邀请 → 实际诊断通过；日志断言只有一次 TCP 握手，Doctor 使用 SSH_REUSED。
- [baseline-3.2a.json](baseline-3.2a.json)：使用官方 Ubuntu tmux 3.2a 二进制，独立 socket 下真实探测回归通过，覆盖默认绑定、任务格式及自定义绑定内容不进入报告。不是全部 Android 或发行版功能的认证。

[debug-builds.json](debug-builds.json) 记录本地调试 APK 的大小与 SHA-256（并非正式发布包）。standard、voice 调试 APK，两个 flavor 的 Android 测试 APK，以及 standard/voice lint 构建通过。用户界面提供中英文资源，Doctor 不引入新模型或远端插件。

核心配置测试验证实际临时目录中的排他创建、不同初始化请求竞争、既有/XDG 配置和符号链接保护、修改后拒绝撤销，以及撤销备份保留。配置写入测试未触及真实 HOME 配置。原始 JVM 报告位于 `core/build/test-results/test`；App 报告位于 `app/build/test-results`。由于定向重查会替换 Gradle 输出，本目录保留每次对应范围的摘要。

实现范围见 [使用说明](../../testing/tmux-doctor.md)。静态诊断不能承诺每个 Agent 的内部滚动行为，已有复杂配置只提供建议。
