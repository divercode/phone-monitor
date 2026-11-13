"""Kotlin 工程跨文件符号自检（无编译器环境下的替代校验）。

检查项：
1. 项目内部 import 是否指向真实存在的 .kt 文件与顶层类型
2. 枚举常量引用（如 FrameSource.Status.WAITING）是否存在
3. 自有类实例的方法/属性调用是否存在（settings / lutStore / renderer / source / lut）
4. R.string.* 资源引用是否在 strings.xml 中声明
5. AndroidManifest 里 ".MainActivity" 是否能解析到类

用法: python .tools/check_symbols.py
"""
import io
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main")
TEST_SRC = os.path.join(ROOT, "app", "src", "test")
PKG_ROOT = os.path.join(SRC, "java")
PKG_ROOT_TEST = os.path.join(TEST_SRC, "java")

PKG_PREFIX = "com.phonemonitor.monitor"

problems = []


def rel(p):
    return os.path.relpath(p, ROOT)


def strip_code(text):
    """去掉注释、字符串字面量和 import 行，避免把 import 路径/文档当成成员调用。"""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"""[\s\S]*?"""', '""', text)
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    text = re.sub(r"^import\s+[\w.]+.*$", " ", text, flags=re.M)
    return text


# ---------------------------------------------------------------- 收集声明

files = []          # (path, package, text)
for pkg_root in (PKG_ROOT, PKG_ROOT_TEST):
    if not os.path.isdir(pkg_root):
        continue
    for base, _, names in os.walk(pkg_root):
        for n in names:
            if n.endswith(".kt"):
                path = os.path.join(base, n)
                text = io.open(path, encoding="utf-8").read()
                m = re.search(r"^package\s+([\w.]+)", text, re.M)
                files.append((path, m.group(1) if m else "", text))

by_package = {}     # package -> {typeName: path}
for path, pkg, text in files:
    types = set()
    for m in re.finditer(
        r"^(?:(?:data|sealed|abstract|open|enum|inner|annotation)\s+)*"
        r"(?:class|object|interface)\s+(\w+)",
        text,
        re.M,
    ):
        types.add(m.group(1))
    by_package.setdefault(pkg, {})
    for t in types:
        by_package[pkg][t] = path

# 枚举体: 名称 -> 成员集合（支持单行/多行、带构造参数的枚举）
enums = {}
for path, pkg, text in files:
    for m in re.finditer(r"enum\s+class\s+(\w+)\s*\{([^}]*)\}", text):
        name, body = m.group(1), m.group(2)
        members = set()
        for chunk in re.split(r"[,;\n]", body):
            chunk = chunk.strip()
            cm = re.match(r"([A-Z][A-Z0-9_]*)", chunk)
            if cm:
                members.add(cm.group(1))
        enums[name] = members

# 每个文件的成员名（fun / val / var）
members = {}
for path, pkg, text in files:
    names = set()
    names.update(m.group(1) for m in re.finditer(r"\bfun\s+([A-Za-z_]\w*)\s*\(", text))
    names.update(m.group(1) for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)", text))
    members.setdefault(pkg, set()).update(names)

# 自有类 -> 成员（按类声明的文件归并，近似：整个文件成员集合）
class_members = {}
for path, pkg, text in files:
    for t in by_package.get(pkg, {}):
        if by_package[pkg][t] == path:
            class_members[t] = members.get(pkg, set())

# 接收者变量名 -> 自有类型（只检查成员完全自有的类，避免继承来的 Android 方法误报）
RECEIVERS = {
    "settings": "MonitorSettings",
    "lutStore": "LutStore",
    "renderer": "MonitorRenderer",
    "source": "FrameSource",
    "currentLut": "Lut3D",
    "lut": "Lut3D",
}

# ---------------------------------------------------------------- 检查

# 1. 内部 import 解析
for path, pkg, text in files:
    for m in re.finditer(r"^import\s+([\w.]+)", text, re.M):
        imp = m.group(1)
        if imp == PKG_PREFIX or imp.startswith(PKG_PREFIX + "."):
            parts = imp.split(".")
            # 逐级缩短，找到「包 + 顶层类型」的组合
            ok = False
            for cut in range(len(parts), 0, -1):
                candidate_pkg = ".".join(parts[:cut])
                if candidate_pkg in by_package:
                    ok = True
                    break
                # 包的最后一段是类型名的情况
                if cut > 1 and ".".join(parts[:cut - 1]) in by_package:
                    if parts[cut - 1] in by_package[".".join(parts[:cut - 1])]:
                        ok = True
                        break
            if not ok:
                problems.append(f"{rel(path)}: import 无法解析 -> {imp}")

# 2. 枚举常量
for path, pkg, text in files:
    stripped = strip_code(text)
    for m in re.finditer(r"\b(\w+)\.([A-Z][A-Z0-9_]*)\b", stripped):
        enum_name, const = m.group(1), m.group(2)
        if enum_name in enums and const not in enums[enum_name]:
            problems.append(f"{rel(path)}: 枚举 {enum_name} 没有常量 {const}")

# 3. 自有类成员调用
for path, pkg, text in files:
    stripped = strip_code(text)
    for recv, typ in RECEIVERS.items():
        known = class_members.get(typ)
        if not known:
            continue
        for m in re.finditer(r"\b" + recv + r"\.([A-Za-z_]\w*)", stripped):
            member = m.group(1)
            if member not in known:
                problems.append(f"{rel(path)}: {recv}.{member} 在 {typ} 中不存在")

# 4. R.string 资源
strings_path = os.path.join(SRC, "res", "values", "strings.xml")
strings = set()
if os.path.exists(strings_path):
    root = ET.parse(strings_path).getroot()
    strings = {el.get("name") for el in root.findall("string")}
for path, pkg, text in files:
    stripped = strip_code(text)
    for m in re.finditer(r"\bR\.string\.(\w+)", stripped):
        if m.group(1) not in strings:
            problems.append(f"{rel(path)}: R.string.{m.group(1)} 未在 strings.xml 声明")

# 5. Manifest 主 Activity 可解析
manifest_path = os.path.join(SRC, "AndroidManifest.xml")
if os.path.exists(manifest_path):
    manifest = ET.parse(manifest_path).getroot()
    ns = "{http://schemas.android.com/apk/res/android}"
    for act in manifest.iter("activity"):
        name = act.get(ns + "name", "")
        if name == ".MainActivity":
            if "MainActivity" not in by_package.get(PKG_PREFIX, {}):
                problems.append("AndroidManifest: .MainActivity 找不到对应类")
        elif name and not name.startswith("."):
            if name.rsplit(".", 1)[-1] not in by_package.get(PKG_PREFIX, {}):
                problems.append(f"AndroidManifest: activity {name} 找不到对应类")

# 6. XML 里的 @string / @style / @color / @xml 等资源引用是否已定义
defined = {}
values_dir = os.path.join(SRC, "res", "values")
if os.path.isdir(values_dir):
    for fn in os.listdir(values_dir):
        if fn.endswith(".xml"):
            root = ET.parse(os.path.join(values_dir, fn)).getroot()
            for el in root:
                if el.tag in ("string", "style", "color", "dimen", "bool", "integer"):
                    defined.setdefault(el.tag, set()).add(el.get("name"))
xml_dir = os.path.join(SRC, "res", "xml")
if os.path.isdir(xml_dir):
    for fn in os.listdir(xml_dir):
        if fn.endswith(".xml"):
            defined.setdefault("xml", set()).add(fn[:-4])
for base, _, names in os.walk(SRC):
    for fn in names:
        if not fn.endswith(".xml"):
            continue
        path = os.path.join(base, fn)
        text = io.open(path, encoding="utf-8").read()
        for m in re.finditer(r"@(string|style|color|dimen|bool|integer|xml)/([\w.]+)", text):
            kind, name = m.group(1), m.group(2)
            if name not in defined.get(kind, set()):
                problems.append(f"{rel(path)}: 资源 @{kind}/{name} 未定义")

# ---------------------------------------------------------------- 输出

print(f"扫描 {len(files)} 个 Kotlin 文件，{len(enums)} 个枚举，{len(class_members)} 个自有类")
if problems:
    print(f"发现 {len(problems)} 个问题：")
    for p in problems:
        print("  -", p)
    sys.exit(1)
print("内部引用 / 枚举 / 成员 / 资源 / Manifest 检查全部通过")
