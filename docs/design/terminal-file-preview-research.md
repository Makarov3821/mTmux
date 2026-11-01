# 终端文件预览可行性调研

2026-10-30。仅调研，未更改产品代码、依赖或发布版本。

后续已整理为[终端文件预览实施计划](terminal-file-preview-plan.md)，包含确定的首版范围、相对路径策略、临时文件生命周期、触摸接入与可行性复审。具体实施以该计划为准。

## 结论

基础 Markdown、静态 HTML、常见图片的缩放拖动，以及外部 HTTP(S) 交给系统浏览器，均可以轻量实现。建议复用现有 Compose / WebView / JSch，新增浏览器端 Markdown 解析与净化两个小文件。难点主要是终端链接、远端路径和资源读取整合，基础版集成复杂度中等。交互式 HTML、超大原图和复杂 Markdown 扩展单独评估。

## 仓库现状

- 已有 AndroidX WebKit、系统 WebView 和 Compose，无须另带 Chromium 或引入新的 UI 框架。
- 终端 xterm.js 5.5.0 目前只安装 addon-fit，未接入链接处理器。触摸事件由 `web/terminal.js` 捕获并阻止默认行为，轻点会发鼠标事件并唤起输入，长按会发右键。新增链接动作要在这些动作之前识别并消费手势。
- JSch 已包含 ChannelSftp；现有 SshClient 未暴露文件读取。现有 exec 返回 UTF-8 字符串，stdout 限制 512 KiB，不适合直接读取图片或较大 HTML。
- 未读取 pane_current_path。终端里相对路径的基准需要新增查询或显式项目根目录；Agent 的工具工作目录未必等于 pane 中进程的工作目录。

## 实现拆分

| 功能 | 建议 | 依赖 / 难度 |
| --- | --- | --- |
| 外部 HTTP(S) | 系统 ACTION_VIEW | 无新库；低 |
| 静态 HTML | 单独预览 WebView，支持文字、CSS、图片与滚动 | 可不加新库；基础低，本地资源整合中等 |
| Markdown | Marked 转 HTML，DOMPurify 净化，再在预览 WebView 呈现 | 两个小 JS；低到中等 |
| PNG/JPEG/WebP | 系统 Bitmap 解码 + Compose 图片与缩放/拖动手势；双击复位、边界限制 | 可无新库；低到中等 |
| SVG | 可走独立 WebView 的静态图片路径，或另评估解码器 | 可复用已有引擎；需单独约束资源 |
| 超大图精细浏览 | 按需采样 / 分块加载 | 中等以上，运行内存比安装包更重要 |
| 脚本驱动 HTML | 需明确脚本、CDN、远端资源及 localhost 的策略 | 不一定大幅增大 APK，但工作量明显增加 |

图片手势已由 Compose 提供 transformable / detectTransformGestures。可先不引入 Coil、整套图片浏览框架；若需要统一异步缓存再评估小规模接入，不能将 Coil 的图片加载能力等同缩放手势。

来源：[Android 本地 WebView 内容](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)、[Compose 多点手势](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch)、[Android 浏览器 Intent](https://developer.android.com/guide/components/intents-common#Browser)、[项目使用版本的 ChannelSftp](https://raw.githubusercontent.com/mwiede/jsch/jsch-2.28.7/src/main/java/com/jcraft/jsch/ChannelSftp.java)。

## 依赖体积实测

通过 npm 官方 registry 的 latest 元数据与对应发布 tarball，仅读取部署所需的浏览器 JS 文件，没有安装包或修改 web/package-lock.json：

| 包 | 本次解析版本 | 浏览器文件 | 原始 bytes | gzip bytes |
| --- | --- | --- | ---: | ---: |
| marked | 18.1.2 | lib/marked.umd.js | 49,207 | 14,906 |
| dompurify | 3.4.17 | dist/purify.min.js | 29,828 | 11,710 |
| 合计 | | | 79,035 | 26,616 |

gzip 仅说明压缩后的量级，不等于最终 APK 压缩结果；整个功能还包含 Kotlin、CSS 和预览逻辑。基础实现以新增 APK 不超过 1 MB 为工程预算目标合理，尚无实现后的 Release 对照，不能视为实测结论。最终选定版本要固定文件哈希并验证旧系统 WebView 兼容性，不能运行时从 CDN 取 latest。

Marked 不自带输出净化，因此按其官方建议评估 DOMPurify。相关许可分别为 MIT、MPL-2.0 OR Apache-2.0；选择对应许可路径并随包保留文本即可。

来源：[Marked 文档](https://marked.js.org/)、[DOMPurify](https://github.com/cure53/DOMPurify)、[npm marked 发布元数据](https://registry.npmjs.org/marked/latest)、[npm dompurify 发布元数据](https://registry.npmjs.org/dompurify/latest)。

## 链接和文件读取：主要工作量

1. 区分 OSC 8、可见 HTTP(S)、绝对文件路径、相对路径、Markdown 链接以及路径末尾的行号。终端只保留显示结果和收到的控制序列，不一定还有 Agent 原始 Markdown 结构。若仅显示一个标签且没有 URI/路径，无法可靠猜出目标文件。
2. 先支持明确的远端绝对路径；相对路径结合项目根目录或 pane 的工作目录，存在歧义时允许选择基准目录。文档里的图片与子文档相对链接应基于该文档所在目录。
3. 点击时绑定当时的服务器、跳板链、用户和目标，不能在异步读取完成后跟随到新服务器。关窗/换目标可取消请求。
4. 复用活跃认证 SSH 的独立 SFTP 通道；不另外常驻 SSH，不占终端串行输入写队列。服务器没有 SFTP 时单独提示，或后续加受限的二进制 exec 读取，不能复用当前 512 KiB 文本 exec 接口假装支持图片。
5. 文件按需读取，不预扫项目。设置文档字节、图片像素、关联资源数量和缓存上限；关窗释放解码图像和预览资源。具体限额实现时再确定。
6. 当前触摸优先发远端鼠标事件；点中已识别文件链接后只打开预览，避免同时点击 Agent 控件和唤起键盘。关闭预览再由用户恢复终端输入。

xterm 官方说明 OSC 8 可用 linkHandler，普通网址识别需 web-links addon，自定义文件路径可用 registerLinkProvider。当前触摸层仍需对接，装 addon 并不能自动完成手机点击。

来源：[xterm 链接处理](https://xtermjs.org/docs/guides/link-handling/)、[registerLinkProvider API](https://xtermjs.org/docs/api/terminal/classes/terminal/)。

## 预览范围和运行资源

- 用应用内可关闭 Dialog/大面板，含标题、加载/错误、关闭和返回操作；不是系统级悬浮窗，不需额外悬浮窗权限。
- 应用内预览不需要让 Activity 退后台；跳到全屏外部浏览器时，按当前 MainActivity.onStop 策略会断开 SSH，远端 tmux 继续运行，返回后需重连。若希望外链返回时仍保持 SSH，这是需要单独决定的生命周期策略。
- 预览必须与终端 WebView 分离。终端现有 NativeTerminal 桥接可发送输入，不能把远端 HTML 放进带此桥接的终端页面。静态 HTML 默认关闭文件脚本，Markdown 只运行受信本地渲染代码，外部 HTTP(S) 点击交给系统浏览器，自动外链资源要有明确策略。
- 初版支持静态 HTML 的样式与受控相对资源。若页面本身依赖 JavaScript 生成全部内容，静态模式可能空白；“简单文档预览”不等于完整交互网站运行环境。后续可另设明确的交互预览模式，不必一次实现。
- 常见截图放大拖动并不重；超大原图解码可能很重。以 ARGB_8888 为例，6000×4000×4 约 96 MB（91.6 MiB），与 JPEG 文件大小不是一回事。先读取尺寸并按显示需求采样，要求原尺寸任意放大时再评估分块图片库。
- 远端 `http://localhost:port` 在手机浏览器指向手机，不会自动指向服务器；SSH 端口转发应作为另一个明确需求，不在基础文件预览中自动开启。
- 基础 Markdown 先支持标题、列表、引用、表格、代码块、链接和图片；Mermaid、数学公式、复杂语法高亮与动图等按实际需要后续评估。

来源：[Android 大图内存与采样](https://developer.android.com/topic/performance/graphics/load-bitmap)、[分块图片库的实现说明](https://github.com/davemorrissey/subsampling-scale-image-view)。

## 建议首版

统一“点击文件 → 绑定服务器/解析路径 → 按需读取 → 可关闭预览面板”的入口，一次覆盖 Markdown、静态 HTML 和常见静态图片。外部 HTTP(S) 直接交系统浏览器。把复杂 HTML 脚本、超大原图和 Markdown 扩展拆开，不因这些扩展推迟基础功能。
