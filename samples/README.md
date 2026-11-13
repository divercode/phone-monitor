# samples · 示例 LUT

两个 `.cube` 文件，用于在真机上验证 LUT 管线（由 `.tools/gen_sample_luts.py` 生成）：

| 文件 | 用途 | 预期效果 |
|---|---|---|
| `identity-17.cube` | 恒等 LUT | 套用前后画面**应完全一致**——不一致说明 LUT 采样/图集打包有问题 |
| `demo-warm-look.cube` | 外观 LUT | 轻微对比增强 + 暖调 |

## 怎么用

1. 把 `.cube` 文件拷到手机存储（微信/数据线/网盘均可）
2. App 里点 **LUT** → **导入新文件** → 选这个 `.cube`
3. 勾选「不使用 LUT」可对比套用前后的差异

## 正式素材请用官方 LUT

拍摄 S-Log3 时不要用上面的 demo 文件做还原，请下载索尼官方 LUT：

- Alpha Universe LUT Gallery：<https://www.sony.co.uk/alphauniverse/lut-gallery>
- 官方说明「什么是 LUT？如何获取 LUT 文件？」：
  <https://www.sony.com/zh-cn/electronics/support/memory-camcorders-hdr-pj-series/hdr-pj580e/articles/00197011>
- 常用文件名：`S-Gamut3.Cine_S-Log3_to_LC-709_VER_1.0.cube`
