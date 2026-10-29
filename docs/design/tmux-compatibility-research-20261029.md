# tmux 最小配置兼容性调研

日期：2026-10-29。范围：Linux 官方仓库包、仅设置 `mouse on` 的滚动路径，以及当前 mTmux 的版本下限。

## 建议结论

用户已确认将 **tmux 3.2a 固定为产品最低 baseline**，Doctor 对低于此版本在“版本”一行显示 ⚠️ 兼容性警告。不实现旧版元数据协议降级。它不是鼠标支持首次出现的版本，而是结合当前 mTmux 所用协议与主流发行版仓库选择的边界。3.2 是必要格式特性的引入点，3.2a 作为目标包含后续修复且是常见发行版提供的版本。

对具备完整默认绑定的受测版本，最小滚动配置仅需 `set -g mouse on`；不需要统一额外写入 root 的 WheelUpPane / WheelDownPane。Doctor 检查有效配置与绑定，发现异常才按版本提供修复。历史容量另按需要设置，它不决定鼠标协议或滚轮路由。

不要承诺“近 N 年发布的 Linux 都可以”。企业发行版的新小版本可能仍提供旧 tmux，应按实际包版本及运行中的 server 能力判断。达到 baseline 不代表已经完成整个发行版、所有架构、所有 Agent 的认证。

## 当前应用的实际下限依据

当前 `core/.../TmuxFields.kt` 用 `#{n:...}` 产生 UTF-8 字节长度，构造任务元数据的有边界记录。`TaskCatalog`、`TmuxPreparation` 等依赖这一格式；tmux 3.2 的官方变更说明明确新增 `n:` 修饰符。旧版本可能能滚动，但无法直接正确输出当前应用的记录格式。[tmux 3.2 官方变更](https://raw.githubusercontent.com/tmux/tmux/3.2/CHANGES)

3.2a 的变更包含鼠标终端特性、格式修饰符等修复，因此推荐产品最低目标为 3.2a，而不是只选择特性首发的 3.2。[tmux 3.2a 官方变更](https://raw.githubusercontent.com/tmux/tmux/3.2a/CHANGES)

理论上的鼠标配置历史断点仍为 2.1（统一 `mouse`）、2.4（copy-mode 绑定重构）。这些不是当前应用完整兼容下限；不应仅为了滚动去宣称支持所有 2.x。

## 实际定向验证

在同一 Ubuntu 24.04 x86_64 主机上，解包 Ubuntu 官方二进制包，不安装、不替换系统 tmux；每次运行专用临时 socket 和仅一行 `mouse on` 的配置。另测试本机 tmux 3.4。探测结束只关闭对应临时 server。

| 项目 | Ubuntu 包 3.0a | Ubuntu 包 3.2a | 本机 3.4 |
| --- | --- | --- | --- |
| mouse 有效值 | on | on | on |
| 上滚自动进入历史 | 通过 | 通过 | 通过 |
| 下滚回到底部退出历史 | 通过 | 通过 | 通过 |
| 上下滚轮转发给请求鼠标的模拟应用 | 通过 | 通过 | 通过 |
| 默认 root 显式 WheelDownPane | 没有 | 没有 | 没有 |
| 当前应用需要的中文长度输出 | `:中文`，缺少长度 | `6:中文`，通过 | `6:中文`，通过 |

这说明本次最老受测的 3.0a 不用补 root WheelDownPane 也能完成这些滚轮路径，但不能直接满足当前元数据协议。不能将 3.0a 说成鼠标支持绝对最低版本。

测试通过 PTY 注入 SGR 滚轮，模拟应用请求鼠标事件后记录实际收到的数据；未经过 Android 触摸层，也不是 Codex/OpenCode 等真实应用测试，未测试发行版完整虚拟机、aarch64 和所有历史版本。当前已存在的 3.4 回归证据与本次定向验证的覆盖范围需分别看待。

驱动：[probe_tmux_compat.py](../../scripts/probe_tmux_compat.py)。原始结果：[probe-results.json](../evidence/tmux-compat-20261029/probe-results.json)。下载包 URL、SHA-256 和版本：[packages.json](../evidence/tmux-compat-20261029/packages.json)。

包来源：[Ubuntu 官方包目录](https://archive.ubuntu.com/ubuntu/pool/main/t/tmux/)。下载包为 `3.0a-2ubuntu0.4` 与 `3.2a-4ubuntu0.3`，相同上游版本的其他发行版补丁修订仍需实际检测。

复现：

```sh
python3 scripts/probe_tmux_compat.py --binary /path/to/extracted/usr/bin/tmux --binary /usr/bin/tmux
```

## 官方仓库版本与覆盖范围

这里列出查询到的普通官方仓库/指定分支，排除 PPA、自编译及 backports。表中“达到”只表示达到建议版本线。仓库状态与架构可能改变补丁修订，Doctor 应以目标机实际结果为准。

| 发行版分支 | tmux 上游版本 | 达到 3.2a baseline | 官方依据 |
| --- | --- | --- | --- |
| Ubuntu 20.04 LTS | 3.0a | 否 | [Focal 官方手册包版本](https://manpages.ubuntu.com/manpages/focal/man1/tmux.1.html) |
| Ubuntu 22.04 LTS | 3.2a | 是 | [Ubuntu 包索引](https://packages.ubuntu.com/tmux) |
| Ubuntu 24.04 LTS | 3.4 | 是 | [Ubuntu 包索引](https://packages.ubuntu.com/tmux) |
| Ubuntu 26.04 LTS | 3.6a | 是 | [Ubuntu 包索引](https://packages.ubuntu.com/tmux) |
| Debian 11 Bullseye | 3.1c | 否 | [Debian 源码版本索引](https://sources.debian.org/src/tmux/) |
| Debian 12 Bookworm | 3.3a | 是 | [Debian 源码版本索引](https://sources.debian.org/src/tmux/) |
| Debian 13 Trixie | 3.5a | 是 | [Debian 源码版本索引](https://sources.debian.org/src/tmux/) |
| Rocky Linux 8 | 2.7 | 否 | [官方 BaseOS 包目录](https://download.rockylinux.org/pub/rocky/8/BaseOS/x86_64/os/Packages/t/) |
| Rocky Linux 9 | 3.2a | 是 | [官方 BaseOS 包目录](https://download.rockylinux.org/pub/rocky/9/BaseOS/x86_64/os/Packages/t/) |
| AlmaLinux 8 | 2.7 | 否 | [官方 BaseOS 包目录](https://repo.almalinux.org/almalinux/8/BaseOS/x86_64/os/Packages/) |
| AlmaLinux 9 | 3.2a | 是 | [官方 BaseOS 包目录](https://repo.almalinux.org/almalinux/9/BaseOS/x86_64/os/Packages/) |
| Amazon Linux 2023.11 / 2023.12 | 3.2a / 3.6a | 是 | [AWS 官方包升级表](https://docs.aws.amazon.com/linux/al2023/release-notes/vercmp-AL2023.11-AL2023.12.html) |
| Alpine 3.20 / 3.22 | 3.4 / 3.5a | 是 | [3.20 包](https://pkgs.alpinelinux.org/package/v3.20/main/x86_64/tmux)、[3.22 包](https://pkgs.alpinelinux.org/package/v3.22/main/x86_64/tmux) |
| Fedora 43 / 44，查询时更新仓库 | 3.7c | 是 | [Fedora 包页面](https://packages.fedoraproject.org/pkgs/tmux/tmux/index.html) |
| Arch Linux，查询时滚动仓库 | 3.8 | 是 | [Arch 官方包页面](https://archlinux.org/packages/extra/x86_64/tmux/) |

首批支持目标可以写成“Ubuntu 22.04 LTS、Debian 12、Rocky/Alma 9 及后续已验证分支”，而不是按操作系统发布时间兜底。RHEL 同代产品通常使用对应上游包，但本表直接核对的是 Rocky/Alma 公开仓库，不冒充已逐一验证 RHEL 所有订阅仓库。

openSUSE 仓库页面在本轮查询未获得足够可核对的包记录，未凭第三方列表填入版本；不影响按实际 tmux 版本运行 Doctor。

## Doctor 的版本提示与门控

1. `>= 3.2a`：版本项达到 baseline，继续检查实际能力；不凭版本显示全部通过。新版本未覆盖 Android 回归时不宣称已实测。
2. `< 3.2a`（包括 3.2）：版本行显示 ⚠️“低于最低兼容版本 3.2a，部分功能可能不兼容，建议升级”。作为版本提醒，不单独把新建入口判为硬性不可用。
3. `< 3.2`：展开说明当前任务元数据协议依赖的长度格式可能缺失，本次 3.0a 已确认不满足。普通 SSH 仍可使用；不做旧版协议兼容。
4. 旧版不是未安装。版本警告、任务嗅探能力缺失、无法创建会话分别判断；只有创建本身需要的能力不可用，才依据已有门控方案限制新建。
5. 同时读取可执行文件和已运行 server 的版本。安装包升级不一定更新已运行的 server；以实际处理命令的 server 能力为准，不自动重启或终止用户会话。
6. 版本解析保留数字段及字母后缀，不用浮点比较；未知/development 版本与发行版回移特性优先以能力探测解释，不误报为没有 tmux。

目标机没有 server 时，已安装版本可用于初步建议，但运行时字段仍待验证。创建首个会话后补充能力结果，不为诊断刷绿而偷偷启动 server。

## 下一步验证范围

将 3.2a、3.3a、3.4、主流更新版本纳入原有核心命令与 Android 手势回归。3.2a baseline 已固定，但当前定向结果不能替代各版本端到端测试。低于 baseline 的旧版适配不在计划中，Doctor 保留版本提醒及实际能力结果。
