# 内置文件预览、分页指示点与首页下拉刷新

后续开发：2026-11-02 的按视口加载、可配置大小和 LRU 缓存见[新实现与验证](preview-viewport-cache.md)。下文保留前几轮实现及其当时的限制和测试记录。

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

## 0.9.3-rc.4 反馈：刷新提示与终端链接命中

基于已发布的 `cc31105` 修改，尚未另行发布。

- 删除下拉刷新完成/没有目标时写入全局危险提示栏的消息；保留刷新图标、动画和每台服务器自己的状态、错误及指纹提示。
- 提取基于公开 buffer API 的 `linksForRow`，去掉中文宽字符折行留下的行尾空占位，保留实际字符到终端格子的映射。旧逻辑在回归样例中只得到中文路径的后半段。
- 除 xterm 自动折行外，兼容 Agent/TUI 在右边界附近显式换行并添加续行缩进的路径和网址；拼接后再识别链接，不先把第一行当成完整 URL。保留空行、独立链接边界、8 KiB 限制与有限行扫描，不将整段终端文本去空白合并。缺少 OSC 8 元数据时，显式换行只能根据可见布局判断，无法任意还原 Agent 已丢弃的文本。
- 点击仍优先消费本地链接，不发送远端鼠标点击、不请求键盘；拖动、长按、连接变化或手指下内容变化继续按原有规则处理，不修改服务器 tmux 配置。

验证：Node 11/11；实际打包终端的桌面 Chromium 点击回归通过，覆盖用户提供的 `docs/design/code-review.md`、图片、中文宽字符长路径以及显式换行网址/路径两侧的点击，同时断言没有 input/binaryInput/focusInput。桌面仍采用 file origin，不代替 Android。

Android API 35 / Google WebView 124：原有预览与首页下拉刷新 5/5，0 skip；新增 MainActivity + 真实 SSH/tmux 用例通过普通相对路径、图片及带缩进硬换行路径，实际打开远端预览并确认 IME 关闭。结果分别在 `.tools/diagnostics/matrix/link-taps-20261101/PreviewInteractionTest/` 和 `.tools/diagnostics/matrix/link-taps-delivery-20261101/`。两版 app JVM 各 44/44、两版 Debug 构建与 lint 通过，构建记录 `.tools/link-fix-checks.log`。

失败记录没有删除：沙箱内隔离 sshd 的 PTY 在 verifyResume 前断开，宿主 SSH PTY 对照同样失败，沙箱外对照正常，因此完整终端点击用例在沙箱外运行，仍只用临时密钥、loopback sshd 和独立 tmux socket。新增测试的动态画面曾在输入文件写完前读取到空文件；改为先写临时帧再原子重命名，并等待确切的新终端内容及 visual state callback 后点击。此前轮次保留在 `link-taps-diagnose/connection/wrapped/final/frame-20261101` 等独立目录，不计为最终通过结果。

## 0.9.3-rc.5 截图反馈：括号中的 HTML 报告路径

基于 `ff92238` 的后续修改，尚未提交或发布。用户截图中的报告地址为 `2nd/tackle-1/batch/reports/index.html`，在 Codex 输出中写成 `(2nd/tackle-1/` 换行后接 `batch/reports/index.html)。`。

- 原版能复现两种错误：显式换行的续行没有缩进且没有占满终端宽度时，第一行无链接，第二行仅识别 `batch/reports/index.html`；合并为单行后，又将开头的 `(` 识别为文件名，得到 `(2nd/tackle-1/batch/reports/index.html`。
- 未闭合括号内的路径允许沿正文内边距继续换行，不再要求这一类续行恰好位于物理终端右边界。普通未括起的输出仍使用原有边界限制。
- 未加引号的文件链接剥离开头多出的包裹括号，并修正点击格子的偏移；`docs/chart(1).html`、`(draft).html` 等配对括号文件名保持完整，带引号的字面路径也保持原样。
- 此修改处理链接识别；前述基准目录输入框与失败时实际请求路径的显示问题不在本节修改中。

验证：新增两个 Node 回归先在原逻辑上失败，修复后网页全套 13/13；实际打包页面的桌面 Chromium 通过单行括号链接和截图两行两侧的点击断言，无 input/binaryInput/focusInput。Android API 35 / Google WebView 124 的完整 SSH/tmux 点击用例 1/1，0 skip，除已有 Markdown、图片及带缩进换行路径外，新增截图形式的 HTML 链接，只有完整路径存在于远端，点击续行后实际打开静态 HTML，IME 保持关闭。

Android 原始结果在 `.tools/diagnostics/matrix/html-report-link-20261101/`，桌面记录 `.tools/html-link-browser.log`；实际设备截图由测试输出到 `ui-evidence/terminal-file-tap-html-report.png`，继续 Git 忽略。没有重跑全部历史 Android 用例，也没有更改版本号或覆盖 rc.5 发布包。

## 完整文件路径编辑与 SVG

后续工作区改动，未修改版本号或发布。

- 失败时显示实际尝试读取的完整文件路径，直接在两行高的输入框中修改并打开；不再显示基准目录解释及下方重复路径。复制按钮移入输入框。绝对路径按原样读取，不再把整个文件路径作为目录再追加旧相对链接。
- 右上角文件夹按钮仅在文件成功加载后出现，用于展开/收起文件路径编辑框；错误时输入框自动显示，不保留无效的切换按钮。重试时清理输入焦点并收起键盘。原有相对路径仍在内部按 pane 目录/HOME 候选解析，普通 SSH 的相对路径仍先等待用户确认完整路径。
- 新的完整路径显式转到原范围外时，资源范围取新文件的父目录；原范围内导航继续保留范围。符号链接 realpath、文件/像素、数量和缓存限制继续保留。
- PNG、JPG/JPEG、WebP 原本已支持，本轮增加 JPEG/JPG 的实际像素回归。新增 `.svg` 路径识别、独立 SVG 预览及 Markdown/HTML 的 SVG 图片资源；SVG 沿用 2 MiB 文本文件预算。
- SVG 使用现有 WebView 的 `<img>` 图像模式，不增加依赖。独立窗口保持 JavaScript 关闭、手机文件访问关闭、有限虚拟 origin 及 CSP；图像模式禁止脚本和外部依赖，支持内建缩放。依据：[SVG 图像处理模式](https://www.w3.org/TR/SVG/latest/conform.html)、[Android WebView 缩放设置](https://developer.android.com/reference/android/webkit/WebSettings)。不将 SVG 作为可执行的顶层网页打开。

新增真实 SSH/SFTP 交互用例覆盖：错误路径自动展示完整候选、失败隐藏按钮、替换为完整文件名后打开成功、成功后展开/收起两行路径框；JPEG/JPG 像素；独立 SVG 像素、缩放、Markdown 内嵌 SVG naturalWidth 与像素。SVG 故意含有修改颜色的脚本及外部蓝图引用，断言图形保留绿色、没有脚本生成的洋红色和外部蓝色。

测试发现 SVG 文件已加载但容器百分比高度为 0，实际截图空白；修正为固定到 WebView 已测量的视口，与终端的布局处理一致。此前失败保存在 `preview-path-svg/preview-svg-ready/preview-svg-layout-20261101` 等轮次中，诊断期间仅在已失败的 fixture 上临时查看 DOM，不计为安全通过结果；最终测试没有启用 SVG 页面 JavaScript。

最终 `PreviewInteractionTest` 全类 7/7，0 skip，报告 `.tools/diagnostics/matrix/preview-path-svg-delivery-20261101/`；真实 SSH/tmux 的终端文件点击回归另外 1/1，见 `.tools/diagnostics/matrix/preview-path-svg-20261101/TerminalUiTest#codexStyleFilePathsOpenRemotePreviewsWithoutFocusingTheIme/`。网页 Node 13/13、桌面 Chromium 回归通过；两版 app JVM 各 44/44、两版 Debug 构建及 lint 通过，最终构建记录 `.tools/preview-path-svg-delivery-checks.log`。Core 新增格式/完整路径回归通过。

实际设备的错误路径框、修正后路径框、SVG、Markdown 内嵌 SVG 和 JPEG 截图已复制到本机 `docs/screenshots/current/preview/`，继续 Git 忽略。本节是工作区验证，不表示已经发布新的正式证书升级包。
