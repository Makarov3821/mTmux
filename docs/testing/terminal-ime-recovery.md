# 终端直接输入在失焦后恢复

纳入 0.9.1 预发行版的改动；已发布的 v0.9.0 附件保持原样。

## 现象与判断

用户反馈新建 shell / tmux 起初可以直接输入，切换到其他应用打字后返回也会失效，但编辑卡片仍能插入同一终端。编辑卡片和直接输入共用后面的 SSH/pane 发送路径，所以这次先排查原生直接输入端点的 InputConnection 生命周期。此前历史模式是另一条可复现的拒绝路径，不能据此断定用户这次也是历史模式。

原实现 `restart()` 先废弃旧连接，只投递一次 `restartInput`；该时刻尚未获得 view focus 就直接放弃。之后 `showKeyboard()` 仅请求显示键盘，没有修复缺失连接；窗口焦点恢复也没有补偿路径。旧连接的后续回调被 `active()` 拦截并返回，不会到达输入队列，旧日志没有记录这次丢弃。这是明确的恢复缺口，尚不能替代用户设备上的根因确认。

## 实现

- 保留待重建请求，只有输入已启用、View 已挂载且 View/window 同时有焦点时才消费；由焦点恢复、挂载或显式点击键盘补执行，事件驱动且合并同一轮请求。
- 点击键盘检查本机输入连接和 IME 服务的活动 View，必要时先重建再请求显示；有效连接上的普通再次点击不清空尚未确认的组词。
- 失去 View/window 焦点时废弃旧端点及未确认组词；切换目标时主动安排重建。编辑卡片/其他窗口拥有焦点时，不抢回焦点或自动弹出键盘。
- 旧回调仍丢弃，不转发到新目标；旧 `closeConnection()` 不清除后续新连接。旧回调发现当前端点缺失时可触发恢复，不重放原文字。
- 保持原 SSH 断开策略、tmux 目标校验和暂停的退格实验；本次不修改远端配置。

焦点条件参考 [Android InputMethodManager](https://developer.android.com/reference/android/view/inputmethod/InputMethodManager#showSoftInput(android.view.View,int))：显示键盘要求 View 和窗口同时获得焦点。连接失效语义参考 [InputConnection.closeConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection#closeConnection())。

## 日志

导出头新增 `inputIme=focus-recovery-v1`。无需增加输入内容权限或启用终端正文记录。

| 事件 | a | b |
| --- | --- | --- |
| IME_CREATED | 当前进程输入连接编号 | 状态位 |
| IME_RETIRED | 被作废连接编号 | 状态位 |
| IME_RESTART | 重建前连接编号，无连接为 0 | 状态位 |
| IME_FOCUS | 1 为 View 焦点事件，2 为 window 焦点事件 | 状态位 |
| IME_SHOW | Android 是否接受显示请求，1/0 | 状态位 |
| IME_INPUT | 输入连接编号 | 该连接第一次非空文本提交的 UTF-16 长度 |
| IME_STALE | 收到失效回调的连接编号 | 状态位，另有 32 表示连接已作废 |

状态位：1=输入启用，2=View 有焦点，4=window 有焦点，8=已挂载，16=当前存在连接。按位组合；IME_SHOW 的 1 只表示请求被接受，不等同于键盘最终已经显示。

生命周期事件及每个连接的首次提交/首次失效回调才记录，不逐字写日志。无文字、按键正文、命令、凭据或服务器地址。日志中的 IME_INPUT 仅证明本地连接收到提交，不表示 SSH 已发送或远端已显示。

本次仅增加输入连接诊断，尚未增加完整的逐批排队、SSH 发送和渲染分段计时。0.9.0 原版也不会出现这些新 IME 事件。

## 验证

JVM 回归覆盖“resume 早于 window focus 的重建不能丢失”、再次点击键盘和关闭编辑窗口后的请求处理。Android 用例覆盖实际 BaseInputConnection、失焦但未退后台、旧 close 晚到，以及通过真实 InputMethodManager 重建连接和弹出/关闭另一编辑窗口。

设备执行结果与环境边界记录在 [证据目录](../evidence/ime-focus-20261030/)。最终用户手机输入法的切换、熄屏与中文选词仍需用新构建复测。

本次结果：两种 app 各 41 项 JVM 单测（含 3 项恢复时序回归）、两种 Debug 构建、standard 测试 APK 与两种 lint 均通过。Android 15 AOSP_ATD / LatinIME 上，旧安装的两项定向回归按预期失败，修复版 7 项全部通过。另已检查两种 APK 的模型/运行库隔离。修复随 0.9.1 预发行版交付；用户手机输入法仍需复测。
