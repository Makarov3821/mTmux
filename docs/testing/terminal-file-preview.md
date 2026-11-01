# 内置文件预览、分页指示点与首页下拉刷新

2026-11-01，基于 0.9.3-rc.3 后的未发布工作区实现。没有改版本号、提交或推送发布标签。设计依据：[预览计划](../design/terminal-file-preview-plan.md)、[交互修复计划](../design/terminal-and-home-interaction-plan.md)。

## 已实现

- 手机短按 OSC 8 链接标题、可见 HTTP(S) 或 Markdown/HTML/图片路径，按类别打开；优先消费链接手势，不同时发送远端点击或弹键盘。xterm 重绘后的命中缓存失效，手指下方内容变化时取消本次激活。代码仅使用 xterm 公开 API。
- HTTP(S) 交系统浏览器；localhost 地址先说明其指向手机。浏览器不可用时复制链接并提示。file URI 属于当前 SSH 目标，不访问手机文件，其他主机 authority 不静默切换。
- 独立预览 Dialog 沿用当前色板、圆角、标题/图标按钮，文档和图片各自处理手势，不套底部面板的拖拽关闭。关闭、返回历史、复制路径和修改基准目录均可用。
- Markdown 用固定 Marked / DOMPurify 资产渲染，包含表格、代码块、图片和链接；支持同文档锚点及本地子文档。静态 HTML 关闭页面 JS。两者都没有终端 JavascriptInterface，按虚拟 HTTPS origin 路由，未知资源请求拒绝，CSP 阻止页面脚本、表单、iframe 和自动互联网资源。
- 文档相对图片能显示，Markdown 图片可点击进入原生缩放视图；PNG/JPEG/WebP 支持双指缩放/拖动、双击放大/复位和 EXIF 方向。多指/拖动文档时不误触图片。
- 相对文件路径优先采用当前 tmux pane 目录，读取前核对 pane 身份；可手动改项目目录。普通 SSH 的相对路径先给出 HOME 候选并等待用户确认目录。不能自动推断 Agent 工具的工作目录，实际完整路径在面板中可见。
- 复用当前认证链的独立 SFTP 通道，串行按需读文件；没有新常驻 SSH。取消/关闭只处理拥有的文件通道和临时目录，重试会重建失效的文件通道。启动清理遗留缓存，文件名用本地随机 ID。
- 预览与编辑/工具/快捷回复/主机验证/语音录音和转写流程互斥；禁用终端输入，关闭后不自动弹键盘。服务器或目标变化关闭旧预览，不把旧结果带到新连接。
- 分页点以反向 Pager 的视觉顺序绘制：左滑亮点向右，右滑向左，拖动落点与页号一致，首尾循环；保留 5 秒闲置淡出和原有按键分组/粘滞键。
- 首页列表到顶后继续下拉，达到阈值松手刷新；圆形主题底、刷新箭头、进度与回收状态均接入现有 Material nested scroll。只刷新已展开文件夹和未分组服务器，绕过 5 分钟间隔；保留并发 2、连接复用、每服务器去重、指纹和 Doctor 策略。批次结束收回提示，失败由各卡片继续说明；不重置搜索、排序或展开状态。

## 资源与边界

- 文本 2 MiB、CSS 512 KiB、单图片文件 20 MiB；每预览 50 MiB / 32 个资源、全局预留与缓存预算 100 MiB、文档导航历史 10 项。CSS 及其本地依赖共同受资源数和字节预算约束。
- 独立图片先读尺寸，源图最多 100 MP / 单边 32768，解码输出最多 8 MP（还按堆上限下调）；内嵌网页图片超过 8 MP 会阻止显示，用户可直接打开原文件使用采样预览。它不是超大原图无损分块浏览器。
- 文件通道初始化 10 秒；每操作无进度 10 秒、总计 30 秒，取消有 250ms 的强制脱离后备，避免无限阻塞。共享 SSH 的 TCP 带宽仍由终端与文件共同使用。
- 静态预览不加载 CDN、网页脚本、远程字体/图片；动态 HTML、SVG、PDF、Mermaid 等不在本版范围。服务器需要可用的 SFTP subsystem；不在服务器安装服务，不自动改成 shell cat 下载。
- 资源自动读取限制在文档/所选项目范围内，realpath 后检查符号链接与路径前缀；这不宣称能够原子防止远端并发修改链接。SFTP 通配字符按字面转义，路径不拼进 shell 命令。
- 跳系统浏览器仍遵守现有 onStop 断开策略；应用内 Dialog 不触发该策略。已读内容是临时快照，不会监听远端文件变动。

## 测试

环境：`mtmux-test` Android 15 / API 35 google_apis x86_64，Google WebView 124.0.6367.219；使用隔离 loopback OpenSSH/SFTP、临时密钥、专用 tmux socket。全部新增文件与测试没有依赖用户服务器。

- 新增 `RemoteFilePathsTest`，覆盖规范化、中文/空格、路径数据不执行、目录边界、扩展名/行号。
- `SshIntegrationTest` 新增两项 SFTP 集成：二进制逐字节一致、文件上限与通配字符字面路径、只有一次 SSH 握手、关闭/取消后 exec 仍可用、半文件删除。
- `PreviewInteractionTest` 四项真实 Android 用例：分页亮点的像素位置与循环；非顶部拖动不刷新；5 分钟内首页主动刷新且折叠分组无更新；Markdown/相对图片/子文档、HTML 禁 JS、图片预览与放大。
- 文档/图片不仅检查 DOM 或 semantics，还检查截图里的绿色测试图像像素。WebView 的 HTML 模式禁 JS 时，不使用 evaluateJavascript 作为就绪条件；使用 title / visual state callback / 实际绘制。
- 网页 7 项 Node 单测通过；实际打包页面的桌面 Chromium 回归包含 OSC 8 标签、中文路径、重绘取消、点击不发送远端鼠标或唤起 IME。当前本机使用 file origin，未将其记为 Android WebView 验证。

最终选定的 14 项 Android 回归全部通过（0 failure / 0 skip），按 5 个测试类/定向入口独立执行，原始结果在 `.tools/diagnostics/matrix/preview-final-20261101/`；包括 PreviewInteractionTest 4 项、TerminalImeFocusTest 2 项、DirectTerminalInputTest 5 项、TerminalKeyboardTest 2 项和终端绘制/键盘布局 1 项。此前新增图片点击用例的失败、整改与定向重跑均保留在 `.tools/preview-*.log` 和各独立轮次目录中，没有覆盖成全绿。

截图为 AGP additional output 中 `preview-evidence/` 的实际设备画面，已被 build 目录的忽略规则排除，不作为离线合成图；该输出可能被后续测试清理，需要保留时及时复制到本地图集目录。

在最终加载状态保护调整后，预览专项另外复跑 4/4，通过 Markdown 图片点击、双击放大、静态 HTML 实际绘制和关闭后本次临时目录清理。结果在 `.tools/diagnostics/matrix/preview-delivery-20261101/`。四张实际截图已复制到本机 `docs/screenshots/current/preview/index.html`，继续 Git 忽略。

交付检查：standard / voice 的 app JVM 各 41/41、voice engine 4/4、Node 7/7、Python 7/7；两种 Debug APK、standard instrumentation APK 编译与两种 lint 均通过。两项 SFTP 定向集成 2/2，0 skip；截图及外链改动没有破坏模型/runtime/权限分离，`verify_voice_apk.py` 通过。两 APK 为本机 debug 签名，未作为 GitHub 正式证书升级包发布。

可复核的源码摘要、APK 哈希、测试汇总和 SFTP 原始 XML 保存在 `.tools/diagnostics/preview-delivery/summary.json` 及其同目录；构建基线为 `2536851`，未提交源码快照 SHA-256 为 `df2006637361fa50829c08825e860420a351c230bd02c10429fb496c58994d9c`。该摘要包含 app/core/voice/web 的跟踪与新增源码和资源，不包含本文等文档。

### 本轮发现并修复的问题

- xterm 的 mouseleave 不清除最后命中 cell；同一位置二次探测必须通过公开事件更新位置，再读 hover 回调。写入解析确认不代表画面已更新，重绘期间要拒绝旧命中。
- JSch 的 InputStream get 在取消后自动 close 可能补发 SFTP CLOSE，破坏共享 SSH。改为有界 OutputStream 接口及协作取消，阻塞时仅断开文件通道，真实 SSH 集成已覆盖该回归。
- 弹窗截图在首帧前采集可能出现正文空白。增加绘制同步和像素断言后，Markdown、HTML、图片均实际绘制；没有以空白截图或 DOM 有字宣称成功。

本轮不宣称全部历史 UI 用例或 ARM 真机/所有厂商 WebView 已通过；现有加密私钥偶发欢迎语超时等历史问题不在本次修复范围。

## 轻量日志

`PREVIEW_OPEN / CHANNEL / RESOLVED / FIRST_BYTE / READ / READY / CLOSED / FAILED` 仅写请求编号、时间戳、开通耗时及字节数。不写文档内容、完整路径、网址参数或凭据。可以区分文件通道、读取与显示阶段，但不是完整逐字符输入时延埋点。

新增部署 JS 约 80 KB 原始文件（含渲染入口），随包固定版本和许可证，不运行时下载。完整 APK 增量以同条件构建对照为准。

## 2026-11-01：绝对路径图片补充

先将上面的基础版提交为 `881eb94`，再单独实现本节改动。此补充尚未发版。

- Markdown 与静态 HTML 的普通图片地址支持 `/home/.../image.png`、`file:///home/.../image.png`、`file://localhost/home/.../image.png`，以及 authority 匹配当前 SSH 配置主机的 file URI。
- 地址先映射成当前文档的虚拟 HTTPS 资源，再通过原有 SFTP 通道读取；`file://` 从不交给手机本地文件系统。保留中文、空格、转义的 `#`、URL 查询/片段；百分号只解码一次。绝对路径子文档链接采用相同映射。
- Markdown 在 DOMPurify 检查 URL 之前映射；静态 HTML 仅重写 img/src、a/href 等 URL 属性，保留其余标记及 script/style/title/注释等原始文本。静态 HTML 的 JavaScript 仍关闭。没有增加解析库或原生运行时依赖。
- HTML/CSS 中站内根路径资源也能按远端绝对路径处理，预览内部资产和其他文档的保留路由不能变成远端文件入口。CSS `file://`、响应式 `srcset` 不在本次新增验收范围。
- 其他主机、带账户/端口的 file URI、控制字符不转成当前服务器文件读取；既有目录 realpath、越界/符号链接、大小和像素限制继续生效。需要引用其他目录时，应由用户明确调整基准目录，不因绝对路径自动放宽范围。

验证：新增 3 项 JVM 路径/HTML 属性重写测试、2 项 Node 映射测试均通过。两版 app JVM 各 44/44、网页单测 9/9、两版 Debug 构建及 lint 通过。`PreviewInteractionTest` 全类真实 Android 5/5，0 skip，覆盖此前 4 项和新增绝对路径用例；后者通过真实 SSH/SFTP 加载包含中文、空格和 `#` 的图片，检查 Markdown 多种 URI 的 naturalWidth，以及静态 HTML 中绝对路径绿图和 file URI 蓝图的实际像素。

同一 Android 用例同时确认未知主机、越界绝对路径和指向范围外的符号链接不显示图片；整个流程仅一次 SSH 握手，关闭后 exec 仍可执行。原始结果在 `.tools/diagnostics/matrix/absolute-preview-20261101/`，构建记录在 `.tools/absolute-preview-checks.log`。本轮采用 google_apis API 35 / Google WebView 124，没有把桌面浏览器结果当 Android 验证。
