# AGENTS.md — 给 AI / DSH 助手的开发约定

本仓库由一个 DSH 会话从零搭建并持续迭代。你在另一台机器上继续开发时，请先按顺序读：

1. `docs/PROGRESS.md` —— 当前进度、已验证/未验证项、下一步任务
2. `docs/DEVELOPMENT.md` —— 环境、构建、CI、发版流程、关键设计决策
3. `docs/ARCHITECTURE.md`、`docs/PTP-FEASIBILITY.md` —— 架构与 Type-C PTP 路线

## 硬性规则

- **每次修改必须递增版本号**：`python .tools/bump_version.py`（修复，patch+1）或
  `--minor`（功能）。同时更新 `docs/PROGRESS.md` 的版本与内容和 `docs/RELEASING.md` 历史表。
- **提交前跑五个检查**（全绿再提交）：
  `check_syntax.py`、`check_symbols.py`、`check_shaders.py`、`verify_lut_math.py`、`check_docs.py`
- **必须等 CI 绿**再认为任务完成：`python .tools/wait_actions.py <sha> 900`；
  失败日志在 `ci-logs` 分支，用 `python .tools/fetch_ci_logs.py` 拉取。
- **不要擅自把第三方版权素材（如索尼官方 LUT）提交到公开仓库**；当前内置 LUT 由项目所有者
  提供，其版权归 Sony，见 `app/src/main/assets/luts/SOURCE.txt`。
- 不要引入新的 UI 框架/重型依赖；保持"Kotlin + 代码构建 UI + GLES2"的现有结构。

## 当前状态速记（v0.8.0）

- M1 完成：UVC 预览、LUT（内置 8 个索尼 Look LUT + 用户增删）、峰值/斑马/网格/直方图、
  低延迟、帧率、图标与 HUD、**系统相机测试源（可选）**。
- M4（Type-C PTP）只完成可行性验证，未写代码；协议常量在 `docs/PTP-FEASIBILITY.md`。
- 未验证：真机采集、真机 USB 权限流程、实际延迟。

## 常用命令

```bash
python .tools/bump_version.py --minor
python .tools/check_syntax.py && python .tools/check_symbols.py && \
python .tools/check_shaders.py && python .tools/verify_lut_math.py && python .tools/check_docs.py
# 有 git：git add -A && git commit && git push
# 无 git（当前 DSH 环境）：
python .tools/push_to_github.py --repo git@github.com:divercode/phone-monitor.git
python .tools/wait_actions.py <commit-sha> 900
```
