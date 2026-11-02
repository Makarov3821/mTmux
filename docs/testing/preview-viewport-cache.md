# 视口图片加载与缓存：开发验证

2026-11-02，工作区基于 `d0221a8`。这部分尚未发布，不改变 0.9.3 正式版的历史验证记录。实现约定见[视口加载与容量缓存](../design/preview-viewport-cache.md)。

## 环境与范围

- JDK 17、Android SDK 36；本机 Node 24、真实桌面 Chromium。
- 专用 API 35 x86_64 / Google WebView 124 模拟器，真实 loopback SSH/SFTP/tmux 夹具，不访问用户保存的服务器。
- 两版 Debug/Release、测试 APK 编译与 lint；app 两版 JVM、core 和 voice engine；Python/Node/浏览器与 SSH/SFTP 集成。
- 联动轮每版 19 项，覆盖 PreviewLoadingTest、PreviewInteractionTest、LanguageUiTest、UiRefreshTest，以及终端路径点击、键盘网格和隐私保护。最终修复后重新执行完整预览与加载两类、终端文件入口，共每版 12 项。没有把这轮定向验证称为整个应用全量或 ARM 实机验收。

## 初轮记录

- `preview-window-standard-round1`：既有预览 7/7；新增 3 项中 2 项通过。快速滚动场景中的总下载次数断言失败：返回顶部可以合法地补下载尚未缓存的邻图，因此改为核对目标图片原有缓存文件身份，准确验证其复用。
- `preview-window-standard-round2`：新增 4/4、既有预览 7/7。新增位图采样用例保留源图并验证内联响应尺寸。
- `.tools/preview-browser-scenarios.log`：取消断言曾把同一文件后续合法重试算作旧下载完成，改为按请求 ID 验证旧请求取消且不能完成；保留原始失败记录。后续扩展浏览器场景通过。
- 第一轮固定代码联动：standard 19/19；voice 18/19，绝对路径预览的定位断言失败。原因是失败占位覆盖原图片 alt，已改为保留 alt 并另显示重试按钮。另在最终审查修复 HTML 图片包装改变 CSS 子选择器的问题，HTML 保持原 DOM，通过只读 Blob 映射完成原生点击浏览；补充浏览器子选择器及真实 Android HTML 图片点击回归。
- `preview-window-standard-final2` 首个预览类 6/7，新加的 HTML 图片短按未打开，按类即停止。诊断确认 Blob 映射正确，原生命中数据需要等到触摸后的帧更新；产品改为下一帧读取，移除测试诊断辅助后原用例通过（`preview-html-tap-frame`）。
- `preview-window-*-final3`：standard 12/12；voice 的预览、加载 11/11 通过，终端入口用例在 Activity 销毁时抛出 Compose SlotWriter 异常。该入口原样单独复测通过（`preview-window-voice-entry-repeat`），未据此认定为环境原因或根因已证实。补充预览销毁后的迟到失败/阻止回调保护；UI 状态统一在 Main.immediate 更新，IO 会话所有权改为原子持有而非 Compose 状态。

## 最终结果

最终 `preview-window-final4` 全部通过，源码哈希前后核对一致。

| 检查 | 结果 |
| --- | --- |
| standard Android：预览 7 + 加载/设置/缓存/采样 4 + 终端入口 1 | 12/12，0 失败 / 0 跳过 |
| voice Android：同一组用例 | 12/12，0 失败 / 0 跳过 |
| app JVM standard / voice | 各 50/50 |
| core，真实 SSH/SFTP/PTY 夹具 | 77/77，0 跳过 |
| voice engine JVM | 4/4 |
| Python 下载/资产测试 | 7/7 |
| Node | 17/17 |
| 实际打包页面 Chromium | 终端 file origin 回归；Markdown/HTML 预览虚拟 HTTPS origin 全场景通过 |
| 两版 Debug/Release、测试 APK、lint | 通过 |
| 宿主 tmux、模型/载荷/权限分离、两版 16 KiB ZIP 对齐 | 通过 |

最终两版报告未出现 WebView 跨线程调用、SlotWriter 越界或 FATAL EXCEPTION。早先单次销毁异常的底层根因没有独立确证；保留原始失败与防护后的完整复测结果，不把原失败改成通过。

最终证据在 `.tools/diagnostics/preview-window-final4/`，包括 `stages.json`、`source-before.json`、`audit.json`、`final-proof.json`；Android 原始 XML、单项日志和 runner 状态在 `.tools/diagnostics/matrix/preview-window-{standard,voice}-final4/`。第一轮与中间各轮记录仍保留，没有覆盖。
