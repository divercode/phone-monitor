"""着色器一致性检查（无需 GPU / GL 编译器）。

检查：
1. Shaders.kt 里声明的 uniform / attribute 与 MonitorRenderer 缓存的名称是否一一对应
   （拼写错会得到 -1 的 location，编译期不报错、运行期静默失效）
2. vertex 与 fragment 的 varying 是否一致
3. GLSL ES 2.0 的常见违规（ES3 专有语法、缺 #extension、fragment 缺 precision 等）
4. fragment 采样器数量 <= 8（ES2 保证的最小值）
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHADERS = os.path.join(ROOT, "app", "src", "main", "java",
                       "com", "phonemonitor", "monitor", "gl", "Shaders.kt")
RENDERER = os.path.join(ROOT, "app", "src", "main", "java",
                        "com", "phonemonitor", "monitor", "gl", "MonitorRenderer.kt")

problems = []


def rel(p):
    return os.path.relpath(p, ROOT)


def extract_block(text, name):
    m = re.search(r'const val ' + name + r' = """(.*?)"""', text, re.S)
    if not m:
        problems.append(f"{rel(SHADERS)}: 找不到 {name} 着色器字符串")
        return ""
    return m.group(1)


def main():
    src = io.open(SHADERS, encoding="utf-8").read()
    vertex = extract_block(src, "VERTEX")
    fragment = extract_block(src, "FRAGMENT")

    renderer = io.open(RENDERER, encoding="utf-8").read()

    # ---- 1. uniform / attribute 对齐
    decl_uniforms = set(re.findall(r"\buniform\s+\w+\s+(\w+)\s*;", fragment + "\n" + vertex))
    decl_attrs = set(re.findall(r"\battribute\s+\w+\s+(\w+)\s*;", vertex + "\n" + fragment))

    used_uniforms = set(re.findall(r'prog\.uniform\("(\w+)"\)', renderer))
    used_attrs = set(re.findall(r'prog\.attrib\("(\w+)"\)', renderer))

    for name in sorted(decl_uniforms - used_uniforms):
        problems.append(f"着色器声明了 uniform {name}，但 MonitorRenderer 没有查询/设置")
    for name in sorted(used_uniforms - decl_uniforms):
        problems.append(f"MonitorRenderer 查询了 {name}，但着色器里没有该 uniform")
    for name in sorted(decl_attrs - used_attrs):
        problems.append(f"着色器声明了 attribute {name}，但 MonitorRenderer 没有查询")
    for name in sorted(used_attrs - decl_attrs):
        problems.append(f"MonitorRenderer 查询了 {name}，但着色器里没有该 attribute")

    # ---- 2. 每个缓存下来的 location 必须真的被 glUniform* 使用（否则静默不生效）
    pairs = dict(re.findall(r'(\w+)\s*=\s*prog\.uniform\("(\w+)"\)', renderer))
    uniform_args = set(re.findall(r"glUniform\w+\(\s*(\w+)", renderer))
    for field, name in sorted(pairs.items(), key=lambda kv: kv[1]):
        if field not in uniform_args:
            problems.append(f"uniform {name} 缓存到字段 {field}，但从未传给 glUniform*")

    # ---- 3. varying 对齐
    v_out = set(re.findall(r"\bvarying\s+\w+\s+(\w+)\s*;", vertex))
    f_in = set(re.findall(r"\bvarying\s+\w+\s+(\w+)\s*;", fragment))
    if v_out != f_in:
        problems.append(f"varying 不一致：vertex 输出 {sorted(v_out)}，fragment 输入 {sorted(f_in)}")

    # ---- 3. GLSL ES 2.0 约束
    for label, shader in (("VERTEX", vertex), ("FRAGMENT", fragment)):
        if re.search(r"#version\s+300", shader):
            problems.append(f"{label}: 使用了 GLSL ES 3.0（本工程是 ES 2.0 上下文）")
        for bad, hint in (
            (r"\btexture\s*\(", "texture() 是 ES3 写法，应使用 texture2D()"),
            (r"\btextureLod\s*\(", "textureLod 是 ES3 写法"),
            (r"\bdFdx\b|\bdFdy\b|\bfwidth\b", "导数函数需要扩展，ES2 基础版本不可用"),
            (r"\blayout\s*\(", "layout 限定符是 ES3 写法"),
            (r"\b(flat|smooth|centroid|noperspective)\s+\w+", "插值限定符是 ES3 写法"),
            (r"\bsampler(3D|2DArray|CubeArray|Buffer)\b", "ES2 不支持这些采样器类型"),
            (r"\b(isampler|usampler)\w*", "ES2 不支持整数采样器"),
            (r"\bswitch\s*\(", "ES2 不支持 switch"),
            (r"\bcase\s+\w+\s*:", "ES2 不支持 case"),
            (r"\b(struct|union)\s+\w+\s*\{", "ES2 不支持自定义结构体（本项目不需要）"),
        ):
            if re.search(bad, shader):
                problems.append(f"{label}: {hint}（匹配 `{bad}`）")

        # 每行末尾不能有分号之外的裸语句问题这里不检查

    # fragment 需要 precision + 正确的 OES 扩展声明
    if not re.search(r"precision\s+(lowp|mediump|highp)\s+float\s*;", fragment):
        problems.append("FRAGMENT: 缺少默认 float 精度声明")
    first_token = next((ln.strip() for ln in fragment.splitlines() if ln.strip()), "")
    if not first_token.startswith("#extension GL_OES_EGL_image_external"):
        problems.append("FRAGMENT: 第一行必须是 GL_OES_EGL_image_external 扩展声明")
    if "samplerExternalOES" in vertex:
        problems.append("VERTEX: 不应使用 samplerExternalOES")
    if "samplerExternalOES" in fragment and "GL_OES_EGL_image_external" not in fragment:
        problems.append("FRAGMENT: 使用 samplerExternalOES 但缺少扩展声明")

    # ---- 4. 采样器数量
    samplers = re.findall(r"\buniform\s+(?:sampler2D|samplerExternalOES|samplerCube)\s+\w+", fragment)
    if len(samplers) > 8:
        problems.append(f"FRAGMENT: 采样器数量 {len(samplers)} 超过 ES2 保证的 8 个")

    print(f"着色器：uniform {len(decl_uniforms)} 个、attribute {len(decl_attrs)} 个、"
          f"fragment 采样器 {len(samplers)} 个、varying {len(f_in)} 个")
    if problems:
        print(f"发现 {len(problems)} 个问题：")
        for p in problems:
            print("  -", p)
        sys.exit(1)
    print("着色器与渲染器名称对齐 / GLSL ES2 约束检查通过")


if __name__ == "__main__":
    main()
