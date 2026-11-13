"""生成 App 图标（无需设计工具）：

- 传统 mipmap：ic_launcher.png / ic_launcher_round.png（各密度）
- 自适应图标前景：ic_launcher_foreground.png（108dp，各密度）
- 单色（Android 13 主题图标）：ic_launcher_monochrome.png
图形：深色底 + 白色监视器轮廓 + 屏幕内 LUT 色带 + 红色录制点。

用法: python .tools/gen_icons.py
"""
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "app", "src", "main", "res")

BG = (14, 17, 22, 255)          # #0E1116
FG = (255, 255, 255, 255)
ACCENT = (255, 82, 82, 255)     # #FF5252
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}
BASE = 1024  # 主画布


def draw_glyph(draw, size, scale=1.0, mono=False):
    """在 size x size 画布上画监视器图形，scale 控制占画布比例。"""
    s = size
    # 监视器本体（按画布 0.62 设计）
    w, h = 0.62 * s * scale, 0.42 * s * scale
    x0, y0 = (s - w) / 2, (s - h) / 2 - 0.03 * s * scale
    r = 0.07 * s * scale
    stroke = max(2, int(0.035 * s * scale))

    color = FG if not mono else (255, 255, 255, 255)
    draw.rounded_rectangle([x0, y0, x0 + w, y0 + h], radius=r,
                           outline=color, width=stroke)
    # 屏幕内的 LUT 色带
    if not mono:
        pad = stroke * 2
        sx0, sy0 = x0 + pad, y0 + pad
        sx1, sy1 = x0 + w - pad, y0 + h - pad
        draw.rectangle([sx0, sy0, sx1, sy1], fill=(24, 28, 35, 255))
        band_w = (sx1 - sx0) * 0.22
        for i, c in enumerate([(255, 82, 82, 210), (255, 193, 7, 210), (76, 217, 100, 210)]):
            bx0 = sx0 + i * band_w
            draw.rectangle([bx0, sy0, bx0 + band_w * 0.8, sy1], fill=c)
        # 录制点
        dot = max(2, int(0.03 * s * scale))
        draw.ellipse([sx1 - dot * 2.4, sy1 - dot * 2.4, sx1 - dot * 0.6, sy1 - dot * 0.6],
                     fill=ACCENT)
    # 支架
    stand_w = w * 0.28
    draw.rounded_rectangle(
        [s / 2 - stand_w / 2, y0 + h + stroke * 0.2,
         s / 2 + stand_w / 2, y0 + h + stroke * 1.6],
        radius=stroke // 2, fill=color,
    )
    foot_w = w * 0.5
    draw.rounded_rectangle(
        [s / 2 - foot_w / 2, y0 + h + stroke * 1.4,
         s / 2 + foot_w / 2, y0 + h + stroke * 2.4],
        radius=stroke // 2, fill=color,
    )


def legacy_icon(size, round_icon=False):
    img = Image.new("RGBA", (BASE, BASE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if round_icon:
        d.ellipse([0, 0, BASE, BASE], fill=BG)
    else:
        d.rounded_rectangle([0, 0, BASE, BASE], radius=int(BASE * 0.22), fill=BG)
    draw_glyph(d, BASE, scale=1.0)
    return img.resize((size, size), Image.LANCZOS)


def adaptive_foreground(size):
    img = Image.new("RGBA", (BASE, BASE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 自适应图标安全区约 66%：图形缩到 0.62
    draw_glyph(d, BASE, scale=0.62)
    return img.resize((size, size), Image.LANCZOS)


def monochrome(size):
    img = Image.new("RGBA", (BASE, BASE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_glyph(d, BASE, scale=0.62, mono=True)
    return img.resize((size, size), Image.LANCZOS)


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG")
    print("wrote", os.path.relpath(path, ROOT), f"{img.size[0]}px")


def main():
    for dpi, factor in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{dpi}")
        save(legacy_icon(int(48 * factor)), os.path.join(folder, "ic_launcher.png"))
        save(legacy_icon(int(48 * factor), round_icon=True),
             os.path.join(folder, "ic_launcher_round.png"))
        save(adaptive_foreground(int(108 * factor)),
             os.path.join(folder, "ic_launcher_foreground.png"))
        save(monochrome(int(108 * factor)),
             os.path.join(folder, "ic_launcher_monochrome.png"))


if __name__ == "__main__":
    main()
