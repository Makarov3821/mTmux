# 连接性能与日志 v2

本轮应用版本为 `0.8.1` / code 29，使用导出头 `build=connection-perf-v2` 区分新日志。没有 Android 设备连接，UI 行为仍需手机复测。

## 行为变化

- 进入 tmux：身份校验、目标名称和鼠标设置合为一个准备请求；随后打开 PTY、再次核对身份，共三个 SSH 通道。只读取选定目标的标签，不扫描其他 session。
- 首页：一次列表命令读取带完整进程身份的任务，先展示灰色状态；每批最多八个 pane、每台服务器最多两批并行获取画面状态。回调按完成顺序更新，最终结果维持发现顺序。
- 连接复用：前台最多两条空闲连接，20 秒自动过期。复用依据配置、加密凭据版本和各级作用域内的主机信任摘要；退后台关闭，关闭后晚到的刷新结果不能重新入池。借出的连接归终端独占。
- 取消：先标记取消、关闭已登记的 socket 和 DNS 等待，再清理 SSH 链。不会重新激活已取消的 client；旧尝试不再被记为新连接失败。平台 DNS 调用本身可能无法中断，但调用方不必继续等待，解析工作线程和队列都有上限。

## 复测步骤

1. 覆盖安装新 debug APK，使用原有 flavor 保留语音能力；不必卸载。
2. 设置 → 调试日志 → 清空日志，避免旧版历史干扰；不清空也可以按新的 `START` 和 `processStartedAt` 划分。
3. 手动刷新一台服务器，观察任务名称是否先出现、状态点随后补齐。立即进入任务可覆盖复用路径；等待超过 20 秒再进入可覆盖重新握手路径。
4. 连接慢时返回首页，再点同一任务重试；检查旧尝试结束是否及时、新尝试是否成功。
5. 操作结束后生成日志，保存 `.txt`；导出只包含点击生成前的事件。原始文件放在根目录 `debug-log/`，该目录不进入 Git。

## 字段

`SSH_TRACE` 只接受枚举和数字。`source=CONNECT` 为终端连接，`source=REFRESH` 为首页刷新；`a` 关联原有事件，`attempt` 为当前进程唯一尝试编号，跨页面重建继续递增；`op` 为当前进程唯一操作编号，`parent` 关联同一命令的通道打开和等待过程。`hop=0` 为目标服务器，正数为跳板序号。

`result=START/OK/FAILED/CANCELLED`；结束事件的 `elapsedMs` 使用单调时钟，避免校时影响。`monoMs` 为事件的单调时钟时间，`exit=NA` 表示该事件没有命令退出码；`bytes` 只记录数量，`reason` 为固定错误类别。非零远端退出码会将命令总事件标为 `FAILED`，命令等待本身仍可能成功完成。

| phase | 计时边界 |
| --- | --- |
| `KEY_LOAD` | 本机导入认证私钥（密码认证时几乎为零） |
| `DNS` | 客户端地址解析及本地解析队列等待 |
| `TCP_CONNECT` | 客户端 TCP 建立 |
| `SERVER_BANNER` | TCP 建立后等待 SSH 版本信息 |
| `KEY_EXCHANGE` | 协商密钥、核对主机身份，直到收到 NEWKEYS |
| `AUTH` | NEWKEYS 后到认证和连接初始化完成 |
| `SSH_CONNECT` / `SSH_READY` | 完整 SSH 链耗时 / 链已可用 |
| `JUMP_FORWARD` | 注册本地跳板转发端口 |
| `SSH_REUSED` | 复用已认证传输，省略本轮握手 |
| `TMUX_CATALOG` / `REFRESH_LIST` | 任务列表命令耗时 / 列表已可展示 |
| `TMUX_PREPARE` | 身份、标签及鼠标设置的合并请求 |
| `TMUX_STATUS` | 一批 pane 状态读取 |
| `CHANNEL_OPEN` / `COMMAND_WAIT` | 命令通道打开 / 等待命令结束 |
| `TERMINAL_OPEN` / `TMUX_VERIFY` | 打开 PTY / 接入后身份校验 |
| `FIRST_BYTE` / `FIRST_RENDER` | 开始读取到首批字节 / xterm 首批输出解析完成（不是屏幕像素绘制时间） |
| `CANCEL_REQUEST` / `CONNECTION_CANCELLED` | 请求取消 / 旧连接协程完成取消收尾 |

跳板之后的 hop 连接本地转发端口，所以其 `DNS/TCP_CONNECT` 不是远端目标的 DNS/TCP 细分耗时。由跳板访问下一台服务器、转发建立及目标 banner 等等待可能体现在该 hop 的 `SERVER_BANNER` 中。主机地址、原始库日志、命令、stdout/stderr、凭据和终端正文均不写入日志。

## 已验证和限制

- 29 个 core 单元测试和 9 个真实 loopback OpenSSH 集成用例通过，覆盖固定阶段日志、非零退出码、两级跳板、特殊字符标签、重建目标拒绝、列表先回调、两批并行、连接池到期/容量/后台清理，以及取消连接。
- app 单元测试 3 项通过；standard Android 测试源码编译、standard/voice debug 构建和 lint 通过（均为 0 个 lint 错误）。两个 APK 使用相同 debug 证书，验签成功，均含 `connection-perf-v2` 日志标记。
- 无 banner 对端的取消用例要求 2 秒内退出，实测不足 1 ms（毫秒取整为 0）；不是手机网络的性能承诺。
- 10 pane 状态读取的两次本机结果为 147、157 ms，三个命令通道，不含握手；初始实现单次 307 ms，四个通道。
- 完整 OpenSSH 套件里的六个交互 PTY 用例与修改前同样失败；这不计为本轮通过。Android UI 用例只做编译检查，尚未在设备上执行。
