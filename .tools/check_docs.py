"""文档一致性检查：验证 README/docs 里的相对链接与仓库内路径真实存在。

检查范围：
  - 所有 *.md 中的 Markdown 链接（跳过 http/https/mailto/纯锚点）
  - 反引号里出现的、以仓库顶层目录开头的路径（app/ docs/ samples/ .tools/ .github/ gradle/）
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

MD_FILES = []
SKIP_DIRS = {
    ".git", "build", ".gradle", ".idea",
    "pylibs", "tmp", "keys", "ref",
    "verify-clone", "ci-logs-view", "ci-logs",
}
for base, dirs, names in os.walk(ROOT):
    # 跳过构建产物、第三方参考代码与本机临时目录
    dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
    for n in names:
        if n.endswith(".md"):
            MD_FILES.append(os.path.join(base, n))

TOP_DIRS = ("app/", "docs/", "samples/", ".tools/", ".github/", "gradle/")
problems = []


def rel(p):
    return os.path.relpath(p, ROOT)


def check_link(md_path, target):
    target = target.strip()
    if target.startswith(("http://", "https://", "mailto:", "#")):
        return
    target = target.split("#", 1)[0]  # 去掉锚点
    if not target:
        return
    # 相对 md 所在目录解析
    candidate = os.path.normpath(os.path.join(os.path.dirname(md_path), target))
    if os.path.exists(candidate):
        return
    # 再按仓库根解析（有些文档按根写相对路径）
    candidate2 = os.path.normpath(os.path.join(ROOT, target))
    if os.path.exists(candidate2):
        return
    problems.append(f"{rel(md_path)}: 链接目标不存在 -> {target}")


def check_inline_path(md_path, raw):
    p = raw.strip().strip("`'\"").rstrip(".,;:)）")
    if not p.startswith(TOP_DIRS):
        return
    if "*" in p or " " in p:  # 通配或说明性文字
        return
    if not os.path.exists(os.path.join(ROOT, p)):
        problems.append(f"{rel(md_path)}: 反引号里的路径不存在 -> {p}")


def main():
    for md in MD_FILES:
        text = io.open(md, encoding="utf-8").read()
        for m in re.finditer(r"\[([^\]]*)\]\(([^)]+)\)", text):
            check_link(md, m.group(2))
        for m in re.finditer(r"`([^`\n]+)`", text):
            check_inline_path(md, m.group(1))

    print(f"检查 {len(MD_FILES)} 个 Markdown 文件")
    if problems:
        print(f"发现 {len(problems)} 个问题：")
        for p in problems:
            print("  -", p)
        sys.exit(1)
    print("文档链接与仓库路径检查通过")


if __name__ == "__main__":
    main()
