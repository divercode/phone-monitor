"""Kotlin/XML 静态自检：括号配平 + XML 合法性。

不是编译器，只用于在没有 JDK/Android SDK 的环境里提前发现低级错误
（漏括号、字符串未闭合、XML 写坏等）。
"""
import io
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main")
TEST_SRC = os.path.join(ROOT, "app", "src", "test")


def strip_kotlin(text):
    """去掉注释与字符串字面量，返回仅剩代码结构的文本。"""
    out = []
    i, n = 0, len(text)
    state = "code"  # code | line_comment | block_comment | string | raw_string | char
    depth_block = 0
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == "code":
            if c == "/" and nxt == "/":
                state = "line_comment"
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = "block_comment"
                depth_block = 1
                i += 2
                continue
            if c == '"':
                if text[i:i + 3] == '"""':
                    state = "raw_string"
                    i += 3
                    continue
                state = "string"
                i += 1
                continue
            if c == "'":
                state = "char"
                i += 1
                continue
            out.append(c)
            i += 1
        elif state == "line_comment":
            if c == "\n":
                state = "code"
                out.append("\n")
            i += 1
        elif state == "block_comment":
            if c == "/" and nxt == "*":
                depth_block += 1
                i += 2
                continue
            if c == "*" and nxt == "/":
                depth_block -= 1
                i += 2
                if depth_block == 0:
                    state = "code"
                continue
            i += 1
        elif state == "string":
            if c == "\\":
                i += 2
                continue
            if c == '"':
                state = "code"
            i += 1
        elif state == "raw_string":
            if text[i:i + 3] == '"""':
                state = "code"
                i += 3
                continue
            i += 1
        elif state == "char":
            if c == "\\":
                i += 2
                continue
            if c == "'":
                state = "code"
            i += 1
    return "".join(out), state


PAIRS = {")": "(", "]": "[", "}": "{"}


def check_brackets(name, code):
    stack = []
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in "([{":
            stack.append((ch, line))
        elif ch in ")]}":
            if not stack:
                return f"{name}:{line} 多余的 '{ch}'"
            op, oline = stack.pop()
            if op != PAIRS[ch]:
                return f"{name}:{line} 括号不匹配：{oline} 行 '{op}' vs '{ch}'"
    if stack:
        op, oline = stack[-1]
        return f"{name}:{oline} 未闭合的 '{op}'（还有 {len(stack)} 个）"
    return None


def main():
    problems = []
    kt_files = []
    for base, _, files in os.walk(SRC):
        for f in files:
            if f.endswith(".kt"):
                kt_files.append(os.path.join(base, f))
    # 单元测试源码也做同样的检查
    if os.path.isdir(TEST_SRC):
        for base, _, files in os.walk(TEST_SRC):
            for f in files:
                if f.endswith(".kt"):
                    kt_files.append(os.path.join(base, f))
    # 根目录的 Gradle Kotlin 脚本也做括号配平检查
    for f in os.listdir(ROOT):
        if f.endswith(".kts"):
            kt_files.append(os.path.join(ROOT, f))
    for path in sorted(kt_files):
        text = io.open(path, encoding="utf-8").read()
        code, state = strip_kotlin(text)
        if state != "code":
            problems.append(f"{path}: 文件结束时仍处于 {state} 状态（字符串/注释未闭合）")
            continue
        err = check_brackets(os.path.relpath(path, ROOT), code)
        if err:
            problems.append(err)

    xml_files = []
    for base, _, files in os.walk(SRC):
        for f in files:
            if f.endswith(".xml"):
                xml_files.append(os.path.join(base, f))
    for path in sorted(xml_files):
        try:
            ET.parse(path)
        except ET.ParseError as e:
            problems.append(f"{path}: XML 解析失败: {e}")

    print(f"检查 Kotlin 文件 {len(kt_files)} 个，XML 文件 {len(xml_files)} 个")
    if problems:
        print("发现问题：")
        for p in problems:
            print("  -", p)
        sys.exit(1)
    print("括号配平与 XML 合法性检查通过")


if __name__ == "__main__":
    main()
