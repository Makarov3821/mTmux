# 产品文案整理

遵循根目录 [AGENT.md](../../AGENT.md)：功能机制与安全边界说明放在文档和修改记录，不在产品页面、README 或 Release 正文常驻展示。

## 处理范围

- 保留第 32 项：终端行列数与累计解析字节数（`tools_terminal_info`），便于性能排查。
- 删除设置、预览、服务器编辑、指纹、终端工具、输入与语音页面中的机制说明及保证性段落。
- 保留操作名称、字段、手势状态、错误结果、输入校验、Doctor 检查结果和低于 tmux 3.2a 的兼容性警告。
- SSH 保留数量、预览单文件与缓存上限、校验和错误分类未改变；图片采样、缓存与传输逻辑未改变。
- 主机指纹核验、截屏保护、多行粘贴校验、首次配置确认及配置撤销条件继续执行。首次配置复选框保留其必要条件。
- 本机链接确认框保留，改为显示实际目标 URI。
- 验证中修正 Doctor 快速连接失败后仍显示连接中的状态更新：界面状态统一在 `Dispatchers.Main.immediate` 更新，SSH 与配置检查仍在 IO 执行。
- 根 README 的历史说明移至 [项目说明](../project-reference.md)，未来 Release 仅列版本及修改记录链接。已发布的历史 Release 不在本次处理范围。

## 机制说明索引

- [预览加载与缓存](preview-viewport-cache.md)：单文件默认 10 MiB、单次预览缓存默认 50 MiB；文本完整读取，内嵌图片优先可见区域，滚动减速后预取上下各一屏，缓存按最近使用淘汰。SVG 与其他图片使用相同的单文件限制；1 MiB 为 1,048,576 字节。
- [项目说明](../project-reference.md)：SSH 链保留、凭据、隐私展示、调试日志、直接输入与复制、离线语音和手势等原说明。
- Doctor：检查默认只读，不自动安装软件，不为检查创建会话。只在满足条件并经确认时为当前登录用户新建最小配置；不覆盖已有配置。新配置在 tmux 下次启动时加载，历史默认值不扩大已有 pane 历史。初始化撤销只处理应用创建且未改变的配置，并在主目录保留备份；竞争变更可能保存在 `~/.mtmux-doctor-undo.*/tmux.conf`。
- Doctor 的会话检查最多抽查 16 个会话、32 个 pane；能力检测、命令预算与“不可创建”状态有效期仍遵循现有实现。版本通过不代表其他能力全部通过。
- 预览继续限制脚本与网络访问；超限、权限不足等错误仍然提示，只移除实现细节和常驻边界说明。

## 文案迁移表

下表保存整理前的中文说明及处理结果。中英文资源同步调整。部分原文是历史限制，不代表当前实现；实际机制以对应设计文档和代码为准。

| 原资源 | 原文 | 产品中的处理 |
|---|---|---|
| `copy_help` | 长按文字并拖动选区，使用系统菜单复制。这里是已加载内容的静态副本；更早的 tmux 历史需返回终端滚动后再打开。 | 移入文档；删除常驻文案 |
| `delete_server_body` | 删除本机配置、凭据和缓存；不影响远端任务。 | 移入文档；删除常驻文案 |
| `doctor_auto_mouse` | 此服务器已设置为进入会话时自动开启鼠标支持。 | 移入文档；删除常驻文案 |
| `doctor_blocked_help` | 近期检查确认 tmux 不可用，请运行 Doctor 或编辑 tmux 路径。限制五分钟后过期，可重新检查。 | tmux 不可用，请重新检查。 |
| `doctor_check_version` | 版本 · baseline 3.2a | tmux 版本 |
| `doctor_config_pending` | 新配置将在 tmux 下次启动时加载。Doctor 不会创建会话，实际滚动和历史容量仍需在会话建立后检查。 | 移入文档；删除常驻文案 |
| `doctor_config_scope` | 为当前 SSH 登录用户创建 %1$s。已有文件及自定义配置会保留。 | 移入文档；删除常驻文案 |
| `doctor_config_uncertain` | 无法确认配置操作结果，请先重新诊断再重试；已有文件不会被覆盖。 | 无法确认创建结果，请重新检查。 |
| `doctor_creating_config` | 正在新建配置，已有文件不会被覆盖… | 正在创建配置… |
| `doctor_first_setup` | 这是我的首次配置，没有其他自定义 tmux 配置或 socket。 | 首次配置，无自定义配置或 socket |
| `doctor_initialized` | 最小配置已创建，将在 tmux 下次启动时加载；可回主页创建第一个会话。 | 配置已创建。 |
| `doctor_install_help` | 请使用服务器的包管理器（apt、dnf、apk 或 zypper）安装 tmux，或编辑其绝对路径，再运行 Doctor。应用不会自动安装软件。 | 请安装 tmux，或修改 tmux 路径后重试。 |
| `doctor_invite_body` | 要检查 tmux 的安装、版本、滚动和历史记录吗？诊断只读取服务器，配置修改会单独提供选项。 | 移入文档；删除常驻文案 |
| `doctor_path_candidate` | 发现其他可执行路径：%1$s。可在服务器编辑页修改 tmux 路径。 | 发现可执行路径：%1$s |
| `doctor_reading` | 正在读取 tmux 环境（命令预算五秒）… | 正在检查… |
| `doctor_readonly` | 只读检查 | 移入文档；删除常驻文案 |
| `doctor_reason_config_exists` | 发现已有配置或受保护路径，不会覆盖。 | 已有用户配置或受保护路径。 |
| `doctor_reason_config_ready` | 未发现标准用户配置及运行中的 server，确认后可生成最小配置。 | 可创建用户配置。 |
| `doctor_reason_config_unavailable` | 尚不满足或未能确认最小初始化条件，现有配置会保留。 | 无法创建用户配置。 |
| `doctor_reason_display_check` | 未能确认 UTF-8 locale 或终端信息；应用会以 UTF-8 连接 tmux。 | 无法确认 UTF-8 或终端信息。 |
| `doctor_reason_display_ok` | 已发现 UTF-8 locale 与 xterm-256color 终端信息；显示与字符宽度仍需设备实测。 | UTF-8 与终端信息正常。 |
| `doctor_reason_executable_ok` | 当前配置的可执行文件可以运行。 | 可执行文件运行正常。 |
| `doctor_reason_helpers_missing` | 缺少部分辅助命令，会影响状态嗅探，不代表 tmux 无法新建会话。 | 缺少辅助命令。 |
| `doctor_reason_helpers_ok` | base64、head、tail、tr 均可用。 | 辅助命令可用。 |
| `doctor_reason_history_ok` | 历史容量可用；数值包含默认和抽查 pane 的容量，修改默认值不会扩大已有历史。 | 历史容量正常。 |
| `doctor_reason_history_small` | 部分历史容量低于建议的 2000 行或未能读取，不影响新建会话。 | 部分历史容量低于 2000 行或无法读取。 |
| `doctor_reason_input_ok` | 抽查 pane 未发现同步输入；发送时仍会实时校验回复目标。 | 未检测到同步输入。 |
| `doctor_reason_input_sync` | 部分抽查 pane 开启了同步输入，输入可能影响多个 pane。 | 检测到同步输入。 |
| `doctor_reason_mouse_off` | 部分作用范围的鼠标支持未开启或尚未确认。 | 部分会话的鼠标支持未开启或无法确认。 |
| `doctor_reason_mouse_on` | 默认及抽查会话均已开启鼠标支持。 | 已开启鼠标支持。 |
| `doctor_reason_no_server` | 暂无运行中的 server 或可检查的 pane；检查不会为此创建会话。 | 暂无运行中的 tmux 会话。 |
| `doctor_reason_not_checked` | 此项尚未检查。 | 尚未检查。 |
| `doctor_reason_path_missing` | 当前路径或 PATH 未找到可执行文件，可能尚未安装或位于其他路径。 | 未找到 tmux 可执行文件。 |
| `doctor_reason_path_not_executable` | 当前路径不是可执行的普通文件。 | 当前路径不是可执行文件。 |
| `doctor_reason_persistence_changed` | 部分保留设置可能在客户端离开后销毁会话或退出 server，请查看有效作用范围。 | 部分会话保留设置异常。 |
| `doctor_reason_persistence_ok` | 已检查的选项允许在最后一个客户端断开后保留会话。 | 会话保留设置正常。 |
| `doctor_reason_protocol_missing` | 当前 server 未返回所需长度格式，任务列表和连接准备可能失败。 | 任务列表读取异常。 |
| `doctor_reason_protocol_ok` | 任务列表需要的长度格式可用。 | 任务列表读取正常。 |
| `doctor_reason_server_inaccessible` | 无法访问 tmux server，请检查 socket 权限；这不等于没有安装 tmux。 | 无法访问 tmux server，请检查 socket 权限。 |
| `doctor_reason_sessions_ok` | 会话访问正常；最多抽查 16 个会话、32 个 pane，下方为抽查会话数。 | 会话访问正常。 |
| `doctor_reason_verified` | SSH 身份校验与认证通过。 | SSH 身份校验与认证通过。 |
| `doctor_reason_version_ok` | 达到 3.2a baseline，各项能力仍需分别检查。 | 达到最低兼容版本 3.2a。 |
| `doctor_reason_version_old` | 低于最低兼容版本 3.2a，部分功能可能不兼容，建议升级。 | 低于最低兼容版本 3.2a，建议升级。 |
| `doctor_reason_version_unknown` | 无法识别发布版本，兼容性尚未确认。 | 无法识别 tmux 版本。 |
| `doctor_reason_wheel_custom` | 发现自定义滚轮逻辑，请保留并实测行为，不会自动替换。 | 存在自定义滚轮配置。 |
| `doctor_reason_wheel_missing` | 未发现部分历史滚轮绑定；root 中没有显式 WheelDownPane 本身不属于问题。 | 部分滚轮配置未找到。 |
| `doctor_reason_wheel_ok` | 已识别常见历史滚动和转发绑定，下方为步长（copy 上/下、vi 上/下）；程序内部滚动仍需实测。 | 滚轮配置正常。 |
| `doctor_saved_detail` | 主机指纹已记录，认证通过 | 移入文档；删除常驻文案 |
| `doctor_suggestions_copied` | 建议已复制。请结合现有配置确认后使用；没有修改远端文件。 | 已复制。 |
| `doctor_touch_off` | 应用已关闭远端触摸转发；即使 tmux 开启鼠标，手势也只浏览本地终端历史。 | 未开启 |
| `doctor_touch_on` | 手指滑动可以向远端终端发送滚轮事件。 | 已开启 |
| `doctor_undo_changed` | 文件已变化，拒绝撤销。若变化发生在操作期间，内容会保留在 ~/.mtmux-doctor-undo.*/tmux.conf 中，请重新检查。 | 文件已变化，无法撤销，请重新检查。 |
| `doctor_undo_help` | 只撤销应用创建且未被修改的文件，文件会移至同一主目录下的备份。运行中的 tmux 会话及设置不会改变。 | 移入文档；删除常驻文案 |
| `doctor_undone` | 初始化已撤销。备份：%1$s | 已撤销，备份：%1$s |
| `editor_folder_none_hint` | 可在首页新建文件夹来分组服务器 | 移入文档；删除常驻文案 |
| `editor_jump_pick_help` | 复制其地址、用户名和凭据到此链路；它自己的跳板会一并加在前面。之后修改该服务器不会同步到这里。 | 移入文档；删除常驻文案 |
| `editor_keystore_note` | 凭据由 Android Keystore 加密，仅保存在本机 | 移入文档；删除常驻文案 |
| `editor_save_anyway_hint` | 不加验证地保存配置，不信任本次获取的新指纹；连接时仍会进行原有指纹验证。 | 移入文档；删除常驻文案 |
| `editor_verifying_help` | 验证通过后保存，并记录每一级的主机指纹 | 移入文档；删除常驻文案 |
| `err_send_uncertain` | 发送结果不确定，请检查远端输出；不会自动重发 | 发送结果不确定，请检查远端输出。 |
| `err_task_gone` | 原任务已结束、移动或重建；请重新选择任务，不会自动连接同名任务 | 原任务已结束、移动或重建，请重新选择任务。 |
| `folder_delete_body` | 其中的服务器将移回未分组，配置、凭据与缓存均保留。 | 其中的服务器将移至未分组。 |
| `home_empty_detail` | 添加服务器后，即可发现 tmux 任务或打开 SSH 终端。 | 移入文档；删除常驻文案 |
| `home_empty` | 添加服务器，连接你的终端 | 暂无服务器 |
| `home_saved_unverified` | 配置已保存，连接尚未验证。可通过“验证指纹”或连接按钮重试。 | 已保存 · 未验证 |
| `host_key_changed_hint` | P0 不提供覆盖入口，核对原因后才能清除应用信任记录。 | 移入文档；删除常驻文案 |
| `host_key_first_hint` | 信任后请重新点击连接。 | 移入文档；删除常驻文案 |
| `host_key_verify_hint` | 请通过服务器管理员或可信终端核对。 | 移入文档；删除常驻文案 |
| `key_paste_help` | 粘贴完整私钥，包含 BEGIN 和 END 行。私钥口令在认证区填写。 | 移入文档；删除常驻文案 |
| `keys_modifier_unsupported` | 所选组合不支持传统终端按键编码。 | 不支持所选组合键。 |
| `log_help` | 从应用启动起自动记录运行事件，最多保留最近 10 MiB，超出后覆盖最早记录。不包含密码、私钥、命令或终端正文。 | 移入文档；删除常驻文案 |
| `preview_blocked` | 部分资源不可用或已被阻止。预览支持静态本地内容。 | 部分资源加载失败。 |
| `preview_body` | 回复与快捷键绑定到显示的 pane；焦点变化时停止发送并保留草稿。多行需要目标程序启用 bracketed paste。 | 移入文档；删除常驻文案 |
| `preview_image_limit` | 图片超过单文件上限 | 图片过大 |
| `preview_image_sampled` | 大图已降低分辨率显示 | 移入文档；删除常驻文案 |
| `preview_limit_details` | 文件大小：%1$s；单文件上限：%2$s。 | 移入文档；删除常驻文案 |
| `preview_limit_unit` | 1 MiB = 1,048,576 字节 | 移入文档；删除常驻文案 |
| `preview_limit` | 文件超过预览大小限制。 | 文件过大。 |
| `preview_limits_help` | 文本完整读取；图片在滚动减慢后按可见区域加载。缓存满时淘汰最久未使用的文件。修改在下次打开预览时生效。 | 移入文档；删除常驻文案 |
| `preview_localhost` | 此地址将访问手机本机，而不是 SSH 服务器。 | 移入文档；删除常驻文案 |
| `preview_not_file` | 此路径指向目录，请填写完整的文件路径。 | 此路径指向目录，请填写文件路径。 |
| `preview_not_found` | 文件不存在，请检查上方的文件路径。 | 文件不存在。 |
| `preview_permission` | 当前 SSH 用户没有读取此文件的权限。 | 没有读取此文件的权限。 |
| `preview_static` | 静态预览 · 已禁用脚本和互联网资源 | 移入文档；删除常驻文案 |
| `preview_timeout` | 文件传输超时，可以重试。 | 文件传输超时。 |
| `preview_unsupported` | 暂不支持预览此文件类型。 | 不支持此文件类型。 |
| `preview_waiting_image` | 图片将在靠近时加载 | 移入文档；删除常驻文案 |
| `quick_reply_help` | 点击填入草稿，编辑后再发送。不会自动确认或执行。 | 移入文档；删除常驻文案 |
| `quick_reply_keep_body` | 当前输入框已有内容。可以追加快捷回复，或明确替换草稿；都不会自动发送。 | 当前已有草稿。 |
| `scroll_speed_help` | 1×～10×：同样距离的滑动发送更多滚轮事件，实际滚动行数由远端应用决定。 | 移入文档；删除常驻文案 |
| `settings_privacy_help` | 开启：隐藏连接地址和用户名，允许普通页面截图。关闭：显示连接信息，禁止截图。凭据、连接详情和指纹核对页面始终禁止截图。终端输出和自定义名称不会自动脱敏。 | 移入文档；删除常驻文案 |
| `settings_touch_help` | 轻点对应左键，长按松开对应右键。滚动行为由 tmux 或终端程序决定。 | 移入文档；删除常驻文案 |
| `ssh_retention_disabled` | 使用结束即关闭，不保留空闲连接。 | 移入文档；删除常驻文案 |
| `ssh_retention_enabled` | 使用后保留最多 %1$d 条空闲 SSH 链，一分钟后关闭。 | 移入文档；删除常驻文案 |
| `ssh_retention_help` | 默认中等（2 条）。跳板属于同一条链。空闲连接不轮询任务状态，退到后台即关闭；正在使用的连接另计。 | 移入文档；删除常驻文案 |
| `status_connected_shell` | 普通 shell：不保证断线后任务存活 | 已连接普通 shell |
| `status_connected_tmux` | 已连接：回复绑定到指定 pane；窗口与尺寸仍与电脑共享 | 已连接 tmux |
| `status_disconnected` | 已断开；不会自动重发输入 | 已断开 |
| `status_input_overflow` | 输入过快，已断开；请核对远端结果，不会自动重发 | 输入过快，连接已断开。 |
| `status_pasted` | 已粘贴文本；点击 Enter 提交 | 已粘贴文本 |
| `status_send_uncertain` | 发送结果不确定；请检查远端输出，不会自动重发 | 发送结果不确定，请检查远端输出。 |
| `status_sent_enter` | 已发送文本和回车，请查看终端结果 | 已发送文本和回车 |
| `status_terminal_closed` | 终端已断开；核对远端结果后手动重连，不会重放输入 | 终端已断开 |
| `terminal_editor_help` | 在这里修改后插入远端当前光标位置，插入不会自动回车。 | 移入文档；删除常驻文案 |
| `terminal_placeholder_waiting` | SSH 已连接，等待服务器输出。\n在下方输入 pwd，再点“发送并回车”。 | 等待服务器输出… |
| `tmux_name_hint` | 创建同名的独立会话和首个窗口，然后进入终端。 | 移入文档；删除常驻文案 |
| `tools_scroll_help` | 慢拖逐步滚动，松手即停。 | 移入文档；删除常驻文案 |
| `tools_touch_help` | 轻点左键，长按后松开右键；上下滑动滚轮。实际行为由 tmux / Agent 的鼠标支持决定。 | 移入文档；删除常驻文案 |
| `trust_verify_hint` | 请通过可信渠道核对该级服务器。 | 移入文档；删除常驻文案 |
| `voice_error_generic` | 语音转写失败，原草稿没有改变。 | 语音转写失败。 |
| `voice_error_model` | 内置 X-ASR 模型缺失或损坏，请重新安装 voice APK。 | 语音模型缺失或损坏，请重新安装 voice APK。 |
| `voice_error_no_speech` | 未识别到语音，草稿没有改变。 | 未识别到语音。 |
| `voice_error_permission` | 麦克风权限被拒绝，仍可继续键盘输入。 | 麦克风权限被拒绝。 |
| `voice_inserted` | 语音文字已放入编辑卡片，请检查后插入终端。 | 已填入编辑卡片 |
| `voice_local_badge` | 离线 · 本机处理 | 移入文档；删除常驻文案 |
| `voice_result_help` | 文字只会加入草稿，绝不会自动发送。 | 移入文档；删除常驻文案 |
| `voice_title` | 离线语音输入 | 语音输入 |
| `voice_transcribing` | 正在使用 X-ASR 在本机转写… | 正在转写… |
| `web_paste_multiline` | 当前程序未启用 bracketed paste，P0 不发送多行文本 | 当前程序不支持多行粘贴。 |

## 验证

本次为文案整理与必要的界面回归，不以此声明完成整个项目的全量测试。专用模拟器为 `emulator-5554`（API 35 / x86_64）。

- 两版 Debug、两版测试 APK 编译通过；app JVM 各 50 项通过，Node 17 项通过。
- 实际打包页面的 Chromium 终端及 Markdown/HTML 预览场景通过；中英文键名和格式参数、预览资源同步、README 链接检查通过。
- Release YAML、shell 语法以及正式版/预发行版的正文生成检查通过，确认只输出版本和修改记录链接。
- Android 分别覆盖 SSH 保留设置 2、预览设置/缓存 4、预览交互 7、服务器保存 3、Doctor 5、界面组件 4、语言 2，以及终端日志/私钥/快捷回复/复制/隐私/跳板/直接输入/预览入口各 1 项。

首轮调度曾将构建与安装测试并行，产生 D8 输出目录冲突，未产生 Android 测试 XML；已停止并改为串行。原始日志在 `.tools/diagnostics/ui-copy-cleanup/android.log` 及 `matrix/ui-copy-cleanup-standard/`。

标准版 Jump Host 用例在原样复测时重复失败：实际点击时开关位于滚动区域之外，密码框仍持有焦点。测试改为先解除焦点、收起键盘，并断言开关可见，再真实点击；继续完整核对跳板顺序、独立凭据与分组保存，修正后通过。产品开关逻辑未改动。原失败及原样复测保存在 `matrix/ui-copy-cleanup-standard-round2/`、`matrix/ui-copy-cleanup-standard-jump-repeat/`，修正后证据在 `matrix/ui-copy-cleanup-standard-jump-focus/`。

语音版 Doctor 离线用例曾多次在 30 秒等待中超时。现场记录显示 TCP 在约 15 毫秒内被拒绝，错误状态与连接清理也已完成，界面却仍停留在连接中；线程栈未发现 SSH 或主线程阻塞。完整尺寸测试宿主没有解决问题，已撤回这一测试改动。将 Doctor 的界面状态更新统一到 `Dispatchers.Main.immediate` 后，原始失败用例在约 3 秒通过，仍保留“服务器已保存、不能将离线误报为缺少 tmux”的原断言。临时产品日志、测试栈转储代码均已删除。

诊断证据在 `doctor-timeout-ui.xml`、`doctor-active-debug.log`、`matrix/ui-copy-cleanup-voice-doctor-*`。这能证明状态调度修正后该用例恢复，不将底层 Compose 调度机制的推测当作独立确证。

最终逐项汇总通过：standard 35/35、voice 35/35，均无跳过；app JVM 各 50/50；两版 Debug 与测试 APK 编译、两版 lint 通过。Node 17/17 及 Chromium 终端、Markdown/HTML 预览回归通过。

Android 结果按相同测试方法去重，并以最终复测替换先前失败，未删除原始失败记录。标准版此前通过的其他项目沿用，Jump Host 和两版 Doctor 在修正后重新验证；语音版终端 8 项在同一测试类、同一临时 SSH/tmux 夹具中顺次执行，核对方法集合与实际 XML 数量。

最终证据位于 `.tools/diagnostics/ui-copy-cleanup/`：`final-proof.json`、`android-final-audit.json`、`build-post-fix.log`、`source-final-before.json` 与 `source-final-after.json`。原始 Android XML 和日志在 `.tools/diagnostics/matrix/ui-copy-cleanup-*/`。最终补充验证期间 app/core/voice/web 源码哈希一致。
