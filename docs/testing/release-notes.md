# README 与发布说明整理验证

2026-11-02，基于 `5fded4d`。此前预览、Doctor 与提示整理已分别提交为 `ee69b01`、`a89dc5e`、`5fded4d`；本轮调整文档、发布说明生成器和 CI。

## 检查结果

| 检查 | 结果 |
| --- | --- |
| Python 测试 | 17/17，包括原有 7 项资产/下载测试和新增 10 项发布说明测试 |
| 生成器边界 | 正式版与 RC 精确匹配；缺失、重复、空条目、非列表、嵌套列表与代码块拒绝；代码围栏内的示例标题不作实际版本 |
| CLI 文件行为 | 成功写出列表；校验失败保留已有输出；拒绝覆盖 CHANGELOG 本身 |
| 文档 | 新增/修改文档的本地链接与锚点检查通过，临时汇总页删除后没有失效入口 |
| workflow | YAML 解析与 shell 语法通过；实际执行 push/PR 校验任务和 Release 前置说明生成步骤 |
| 生成正文 | 两种流程输出相同的 0.9.3 变化列表，正文 8 条，无版本标题、内部报告或额外段落 |
| 既有发布检查 | 标签核对、签名输入、APK 校验、构建、发布命令与清理保留 |

版本列表来自 [CHANGELOG](../../CHANGELOG.md)，维护规则见 [发布说明规范](../release-notes.md)。检查在本地执行，没有触发 GitHub 发布或改写线上历史 Release。

## 范围与证据

本轮 `app/`、`core/`、`voice/`、`web/` 没有改动，不重跑 APK 与 Android 全量回归。此前应用验证见[预览测试](preview-viewport-cache.md)与[提示整理记录](../design/product-copy-cleanup.md)。

可复现的生成器检查：

```bash
python3 -m unittest discover -s scripts/tests
python3 scripts/release_notes.py --version 0.9.3 --output /tmp/mtmux-release-notes.md
```

本地核对产物在 `.tools/diagnostics/readme-release/`：`audit.json`、`ci-preflight.log`、`release-preflight.log` 和 `release-notes-0.9.3.md`。临时生成的仓库根目录 `release-notes.md` 已清理。
