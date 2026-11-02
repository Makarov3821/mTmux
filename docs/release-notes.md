# 发布说明规范

## 内容归属

根 README 介绍产品。CHANGELOG 保存面向用户的逐版本变化，并作为 GitHub Release 正文的唯一来源。`docs/releases/<版本>.md` 保存原因、实现、验证范围和交付记录，`docs/testing/` 与 `docs/evidence/` 保存证据。

本规范从 CHANGELOG 的 0.9.3 条目起采用。更早条目保留历史原文，已发布的 GitHub Release 本轮不批量修改。

## 如何写变化

- 每条写一件用户能感受到的新增、改善或修复，先写结果。
- 用产品语言描述，例如“修复刷新后打开终端时的连接异常”。线程、通道和库异常分析写入交付记录。
- 正式版相对上一个正式版汇总；期间 RC 的改动合并去重。RC 描述相对上一个 RC 的变化，首个 RC 描述其候选范围。
- 需要用户操作的升级变化直接写成一条，并说明具体操作。
- 条目数量随实际变化决定；不用“全面优化”“体验升级”等无法核对的空泛描述。

Release 正文仅由无序列表组成。标题和预发行状态由 GitHub Release 的标题、标签及 prerelease 字段表达。测试数量、证书、APK 大小、模型文件、内部实现、安全保证和长篇限制说明归入内部记录。

## CHANGELOG 格式

开发中的变化先放在 `## [Unreleased]`。发版前写入与 versionName 对应的独立条目，正文只写 `- ` 开头的单行列表项，可使用行内 Markdown。空行允许，不使用段落、子标题、嵌套列表、HTML 或代码块。

```markdown
## [0.x.y] — YYYY-MM-DD

- 新增一项用户功能。
- 改善一种具体操作。
- 修复一个明确问题。
```

0.x.y 为格式占位。实际日期和版本在发版前填写；版本条目不能重复，变化不能留空。0.9.3 是仓库中的实际示例。

## 发布前执行

1. 整理正式版或 RC 的变化，更新 versionName 与递增的 versionCode；两个 flavor 的版本一致。
2. 完成对应内部交付记录和必要测试，填写 CHANGELOG 对应版本的变化。
3. 在仓库根目录验证提取结果：

   ```bash
   python3 -m unittest discover -s scripts/tests
   python3 scripts/release_notes.py --version 0.9.3 --output /tmp/mtmux-release-notes.md
   cat /tmp/mtmux-release-notes.md
   ```

   上述版本用于检查现有示例，实际发版时换成目标版本。提取结果应只有本版变化，不能混入 Unreleased、其他版本或内部记录。

4. 按 [签名发布流程](testing/webview-and-release-runbook.md#固定签名与-github-发布) 核对候选提交、签名、附件与版本，再在获得的发布授权范围内推送标签。

Release workflow 在获取模型和构建 APK 前运行 Python 测试，并根据 versionName 生成说明。缺少对应条目、重复版本、空列表或格式不符会使流程失败；不会退回到版本号或文档链接代替正文。

普通 push／PR 的 Android checks 也包含独立的发布说明检查任务，运行生成器测试并校验当前 versionName 的条目。

手动运行 workflow 使用当前 versionName 生成同样的正文，随 `signed-release-apks` artifact 提供 `release-notes.md`，不创建 Release。正式标签仍必须等于 `v` 加 versionName；原有签名、包体、权限和对齐校验保持执行。

本次格式与流程验证见 [整理验证记录](testing/release-notes.md)。
