"""验证 MonitorRenderer 着色器里 3D LUT 的图集采样数学。

做法：
  1. 按 Lut3D.packAtlas 的规则把 3D LUT 打包成 RGBA8 2D 图集（含 1px 边缘复制）
  2. 按 Shaders.FRAGMENT 的算法模拟采样（双线性 + 两切片混合 = 三线性）
  3. 与直接在原始 3D LUT 上做三线性插值的结果对比

同时验证构图网格 alpha 函数在三分线位置的取值。
"""
import math
import random

N = 17
TILES = math.ceil(math.sqrt(N))      # 5
STRIDE = N + 2                       # 19
DIM = TILES * STRIDE                 # 95
Q = 255.0


def build_lut(fn):
    """fn(r,g,b) -> (r,g,b)，返回按 .cube 顺序（R 最快）的扁平数组（3*N^3）。"""
    data = []
    for b in range(N):
        for g in range(N):
            for r in range(N):
                data.extend(fn(r / (N - 1), g / (N - 1), b / (N - 1)))
    return data


def pack_atlas(lut):
    """返回扁平 RGBA8 数组（行主序），完全复刻 Lut3D.packAtlas。"""
    atlas = [0] * (DIM * DIM * 4)

    def comp(x, y, z):
        x = min(max(x, 0), N - 1)
        y = min(max(y, 0), N - 1)
        z = min(max(z, 0), N - 1)
        i = ((z * N + y) * N + x) * 3
        return lut[i], lut[i + 1], lut[i + 2]

    for z in range(N):
        tx, ty = z % TILES, z // TILES
        ox, oy = tx * STRIDE, ty * STRIDE
        for py in range(-1, N + 1):
            for px in range(-1, N + 1):
                c = comp(px, py, z)
                i = ((oy + 1 + py) * DIM + (ox + 1 + px)) * 4
                atlas[i] = round(min(max(c[0], 0), 1) * 255)
                atlas[i + 1] = round(min(max(c[1], 0), 1) * 255)
                atlas[i + 2] = round(min(max(c[2], 0), 1) * 255)
                atlas[i + 3] = 255
    return atlas


def texel(atlas, x, y):
    """CLAMP_TO_EDGE 取像素，返回 0..1 浮点。"""
    x = min(max(x, 0), DIM - 1)
    y = min(max(y, 0), DIM - 1)
    i = (y * DIM + x) * 4
    return atlas[i] / Q, atlas[i + 1] / Q, atlas[i + 2] / Q


def bilinear(atlas, u, v):
    """模拟 GL_LINEAR：uv 以像素中心为基准。"""
    fx = u * DIM - 0.5
    fy = v * DIM - 0.5
    x0, y0 = math.floor(fx), math.floor(fy)
    tx, ty = fx - x0, fy - y0
    c00 = texel(atlas, x0, y0)
    c10 = texel(atlas, x0 + 1, y0)
    c01 = texel(atlas, x0, y0 + 1)
    c11 = texel(atlas, x0 + 1, y0 + 1)
    return tuple(
        (c00[k] * (1 - tx) + c10[k] * tx) * (1 - ty)
        + (c01[k] * (1 - tx) + c11[k] * tx) * ty
        for k in range(3)
    )


def sample_slice(atlas, z, xy):
    """复刻 sampleSlice()：tile 原点 + 内容偏移 1px + 半像素中心。"""
    tx = z % TILES
    ty = z // TILES
    ox, oy = tx * STRIDE, ty * STRIDE
    u = (ox + 1 + xy[0] + 0.5) / DIM
    v = (oy + 1 + xy[1] + 0.5) / DIM
    return bilinear(atlas, u, v)


def shader_lookup(atlas, rgb):
    """复刻 applyLut()。"""
    idx = tuple(min(max(c, 0.0), 1.0) * (N - 1) for c in rgb)
    z0 = math.floor(idx[2])
    z1 = min(z0 + 1, N - 1)
    fz = idx[2] - z0
    c0 = sample_slice(atlas, z0, idx[:2])
    c1 = sample_slice(atlas, z1, idx[:2])
    return tuple(c0[k] * (1 - fz) + c1[k] * fz for k in range(3))


def reference_trilinear(lut, rgb):
    """直接在原始 3D LUT 上做三线性插值（不经过图集/量化）。"""
    def at(x, y, z):
        x, y, z = [min(max(v, 0), N - 1) for v in (x, y, z)]
        i = ((z * N + y) * N + x) * 3
        return lut[i], lut[i + 1], lut[i + 2]

    idx = [min(max(c, 0.0), 1.0) * (N - 1) for c in rgb]
    x0, y0, z0 = (math.floor(v) for v in idx)
    x1, y1, z1 = (min(v + 1, N - 1) for v in (x0, y0, z0))
    fx, fy, fz = idx[0] - x0, idx[1] - y0, idx[2] - z0

    def lerp(a, b, t):
        return tuple(a[k] * (1 - t) + b[k] * t for k in range(3))

    c00 = lerp(at(x0, y0, z0), at(x1, y0, z0), fx)
    c10 = lerp(at(x0, y1, z0), at(x1, y1, z0), fx)
    c01 = lerp(at(x0, y0, z1), at(x1, y0, z1), fx)
    c11 = lerp(at(x0, y1, z1), at(x1, y1, z1), fx)
    c0 = lerp(c00, c10, fy)
    c1 = lerp(c01, c11, fy)
    return lerp(c0, c1, fz)


def grid_alpha(p, W, H):
    """复刻 Shaders.gridAlpha：三分线 + 中心十字。"""
    line_w = 0.6

    def smoothstep(e0, e1, x):
        t = min(max((x - e0) / (e1 - e0), 0.0), 1.0)
        return t * t * (3 - 2 * t)

    def line_dist(d):
        return 1.0 - smoothstep(line_w, line_w + 1.0, d)

    a = 0.0
    for target in (W / 3.0, 2.0 * W / 3.0):
        a = max(a, line_dist(abs(p[0] - target)))
    for target in (H / 3.0, 2.0 * H / 3.0):
        a = max(a, line_dist(abs(p[1] - target)))
    dcx, dcy = abs(p[0] - W * 0.5), abs(p[1] - H * 0.5)
    a = max(a, line_dist(dcx) * (1.0 if abs(p[1] - H * 0.5) <= 36 else 0.0))
    a = max(a, line_dist(dcy) * (1.0 if abs(p[0] - W * 0.5) <= 36 else 0.0))
    return a


def main():
    random.seed(7)

    # ---- 测试 LUT：恒等 + 一个平滑的非线性函数，覆盖边界情况
    lut_id = build_lut(lambda r, g, b: (r, g, b))
    lut_curvy = build_lut(
        lambda r, g, b: (
            r ** 0.8 * (1.0 - 0.15 * b),
            g * 0.9 + 0.1 * r,
            b ** 1.2,
        )
    )

    cases = [("identity", lut_id), ("curvy", lut_curvy)]
    for name, lut in cases:
        atlas = pack_atlas(lut)
        max_err = 0.0
        # 采样点：随机 + 切片边界（b 接近整数）+ 图集边界
        samples = [(random.random(), random.random(), random.random()) for _ in range(4000)]
        samples += [(random.random(), random.random(), z / (N - 1)) for z in range(N)]
        samples += [(0.0, 0.0, 0.0), (1.0, 1.0, 1.0), (1.0, 0.0, 0.5)]
        for rgb in samples:
            got = shader_lookup(atlas, rgb)
            ref = reference_trilinear(lut, rgb)
            for k in range(3):
                max_err = max(max_err, abs(got[k] - ref[k]))
        # 8bit 量化误差上限约 1/255 ≈ 0.004；再加插值误差给到 0.012 容差
        print(f"{name}: 最大误差 {max_err:.4f}（样本 {len(samples)}）")
        assert max_err < 0.012, f"{name} 图集采样与参考实现偏差过大: {max_err}"

    # ---- 网格 alpha：三分线像素处应接近 1，中点为 0
    W, H = 1920, 1080
    s = (W / 3.0, H / 2.0)
    a_line = grid_alpha(s, W, H)
    a_center = grid_alpha((W / 2.0 - 100, H / 2.0 - 100), W, H)
    print(f"网格 alpha：三分线={a_line:.3f}，非线区域={a_center:.3f}")
    assert a_line > 0.9, a_line
    assert a_center < 0.05, a_center

    print("LUT 图集采样数学 + 网格遮罩函数验证通过")


if __name__ == "__main__":
    main()
