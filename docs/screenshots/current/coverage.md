# 界面覆盖说明

对应业务代码提交：`c6a672b`。图片清单、哈希和采集类型见 [manifest.json](manifest.json)，浏览入口见 [index.html](index.html)。

| 入口 / 组件 | 已展示的独立界面与分支 | 图片目录 |
| --- | --- | --- |
| ServerHome | 空列表、服务器和任务、搜索匹配/无结果、排序、折叠分组/服务器、多 pane 展开、状态灯、最近使用、六套深色主题和浅色 | home |
| 分组与服务器操作 | 新建/重命名/删除分组、重名提示、服务器菜单、移动分组、删除服务器 | home |
| ServerEditor | 新建/编辑、密码/私钥认证、高级配置、所属分组、跳板导入与编辑、必填校验、放弃修改 | server |
| PrivateKeyPasteDialog | 粘贴私钥与格式错误 | server |
| ServerVerification | 正在验证、目标/跳板指纹、验证失败、重试与 Save anyway | dialogs |
| NewTmuxDialog | 命名、非法名称、创建中、同名拒绝、创建结果不确定 | server、dialogs |
| AppSettings | 外观/语言、连接保留、终端字号/滚动、隐私、语音、诊断日志和清除结果、浅色 | settings |
| MainActivity 终端 | 直接输入、长文本编辑、键盘避让布局、工具面板、连接详情、连接中/认证失败/断线/回复目标拦截、首次指纹与密钥变化 | terminal |
| TerminalKeys | 默认快捷键、F1–F6、F7–F12、Ctrl/Shift/Alt 与编辑键、粘滞组合键 | terminal |
| QuickReplySheet | 列表、新增、编辑/删除；已有草稿时追加/替换/取消 | terminal、dialogs |
| TerminalCopyDialog | 文字副本、空缓冲、裁剪提示 | terminal |
| VoiceInputControl | 麦克风准备、绿色录音、最后十秒橙色/五秒红色、上滑取消、转写中、各类失败、浅色录音 | voice |

## 展示边界

- 应用没有独立账户登录页。SSH 认证设置在服务器表单，主机信任在指纹确认弹窗。
- 通用页面与可触发弹窗使用当前生产组件渲染。网络进行中/失败、部分语音分支用固定演示状态展示；清单逐张标明类型，不代表已执行真实认证或转写。
- 终端页使用实际 MainActivity 原生控件。由于本次软件模拟器 WebView 不稳定，正文由同一份 xterm.js 在离线 Chromium 中渲染后注入；这些图片均标注为“原生界面＋离线终端画面”，不能作为 Android WebView 或 SSH 的运行验证。
- 图片由 Android View/Compose 树绘制，包含应用窗口及弹窗。系统状态栏图标、权限授权框、文件选择器和其他输入法的键盘属于其他进程，未伪造其截图。键盘相关图仅用于展示应用的避让布局。
- 标准版与语音版共享页面；语音专属状态另列。未穷举屏幕尺寸、字号、语言、主题与数据数量的排列组合。状态提示复用同一布局时以代表分支展示。
- 当前语音转写后进入终端编辑卡片，未展示旧代码中不再进入的独立结果页。此目录针对主应用，不包含独立的 `test-audio` 验证软件。
- 截图使用公开示例地址和文字，不连接真实服务器、不使用私钥、不录音。测试仅在专用 debug 模拟器暂时解除截图保护，正式应用行为不变。

旧程序截图的删除清单见 [removed-legacy-images.json](removed-legacy-images.json)；历史版本仍可从 Git 恢复。
