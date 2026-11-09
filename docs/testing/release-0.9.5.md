# 0.9.5 验证记录

2026-11-10，版本 0.9.5 / versionCode 46。本轮验证针对功能键分页顺序、设置版本与渠道显示、项目与 Issue 浏览器入口，以及两版构建与资源分离。

## 执行结果

| 检查 | 结果 |
| --- | --- |
| Python 发布说明与资源测试 | 17 项通过 |
| app JVM standard / voice | 各 50 项通过，失败、错误、跳过均为 0 |
| 两版 Debug 与测试 APK | 构建通过 |
| 两版 Lint | 通过 |
| 模型与运行库 | 固定 SHA 校验通过 |
| Debug APK 负载 | standard / voice 资源分离、模型与 ABI 校验通过 |
| standard Android | 3 项通过，失败、错误、跳过均为 0 |
| voice Android | 3 项通过，失败、错误、跳过均为 0 |

## Android 用例与运行条件

专用 `mtmux-release-095` 模拟器，API 35 / x86_64，Google APIs 镜像、KVM，720 × 1600 显示尺寸。两版分别执行：

- `AppAboutUiTest#installedVersionChannelAndProjectLink`：读取版本与渠道，点击项目按钮，观察实际 HTTPS Intent 和浏览器获得焦点。
- `AppAboutUiTest#issueLinkOpensBrowser`：点击 Issue 按钮，观察精确路径的实际 HTTPS Intent 和浏览器获得焦点。
- `TerminalUiTest#functionKeyPagesFillWidthAndDeliverAllTwelveKeysToBoundPane`：通过真实 OpenSSH/tmux 检查第三、第四组及全部十二个功能键的字节，验证草稿保留与断线后的按键状态。

浏览器事件由非阻断 ActivityMonitor 观察，Intent 正常交给系统浏览器。SSH fixture 使用生成的真实密钥、独立 sshd 和 tmux socket，工作文件位于项目内的隔离目录。

本轮为定向设备回归，未执行整个应用的 Android 测试矩阵、ARM 手机语音或厂商输入法验收。截图采集入口单独用于文档展示。

## 证据与候选源码

本地构建输出和审计结果位于 `.tools/diagnostics/release-095/`，设备 XML 和 runner 日志位于 `.tools/diagnostics/matrix/release-095-{standard,voice}-final/`。审计核对每项用例名称、实际执行数量、runner 状态及产品源码哈希，验证前后源码一致。

GitHub 的同一候选提交运行 Android checks 与 Voice checks；通过后推送 `v0.9.5`，由 Release APK workflow 执行正式签名构建及 APK 校验。线上交付结果与下载验签记录保存到本轮本地证据目录。
