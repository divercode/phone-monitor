# 开发进度快照（交接给另一台开发机 / DSH）

> 更新时间：2026-11-13 · 版本 **v0.8.0**（versionCode 9）
> 仓库：<https://github.com/divercode/phone-monitor>（public，默认分支 `main`）
> 最近 CI：构建 + 单测通过，APK 自动发布到 Release `apk-latest`

## 1. 项目一句话

安卓手机当索尼微单（A7M4）外接监视器：HDMI 采集卡（UVC）实时预览 + 3D LUT 套用 +
峰值对焦/斑马纹/构图网格/直方图。定位是 Monitor+ 的开源精简替代，只做监看，不做相机控制。

## 2. 已完成功能（M1）

| 功能 | 状态 | 说明 |
|---|---|---|
| UVC 采集卡预览 | ✅ | `CameraHelper`（UVCAndroid 1.0.13）→ SurfaceTexture → GL OES，零拷贝 |
| 3D LUT 套用 | ✅ | `.cube` 解析 + 2D 图集打包 + GLSL 三线性；数值验证误差 0.0020 |
| 内置索尼 LUT | ✅ | 8 个索尼官方 Look LUT（S-Log3/S-Gamut3(Cine)→709 的 Warm/Cool/TealOR/Vintage）只读不可删 |
| 用户 LUT 导入/删除 | ✅ | 导入到 `filesDir/luts`，可删除；删除当前使用的会自动关闭 LUT |
| 峰值对焦 / 斑马纹 | ✅ | 阈值可调 |
| 构图网格 / 安全框 | ✅ | 三分线 + 中心十字 + 10% 安全框 |
| 直方图 | ✅ | 160×90 离屏渲染 + glReadPixels |
| 变形宽银幕 | ✅ | ×1.33 / ×2.0 |
| 测试图案 | ✅ | 无采集卡时彩条，用于管线自检 |
| 系统相机测试源 | ✅ | 可选开关：Camera2 预览 → 同一套 GL 渲染；含传感器旋转处理 |
| HUD / 设置界面 | ✅ | 状态灯 + 帧率 + 图标按钮 + 快捷开关条；v0.4 重做 |
| 桌面图标 | ✅ | 自适应/圆形/单色，程序化生成 |
| 低延迟模式 | ✅ | 未压缩格式优先 + `eglSwapInterval=0` |
| 帧率显示 | ✅ | 每 30 帧统计 |
| 插卡自动启动 | ✅ | `device_filter.xml` 匹配 USB Video Class |

## 3. 验证状态

| 项目 | 结论 | 证据 |
|---|---|---|
| 编译 | ✅ 通过 | GitHub Actions `assembleDebug` 成功 |
| 单元测试 | ✅ 12 例通过 | `testDebugUnitTest`（LUT 解析 + 图集打包） |
| 静态自检 | ✅ 全绿 | `.tools/check_*.py` + `verify_lut_math.py` |
| LUT 数学 | ✅ 误差 0.0020（8bit 量化极限） | `verify_lut_math.py` |
| 真机 UVC 采集 | ⏳ **未验证** | 本开发机无采集卡/手机 |
| 真机 USB 权限流程 | ⏳ 未验证 | 个别国产 ROM 可能拦截 |
| 实际延迟 | ⏳ 未实测 | 预期 60~110ms，优化后 40~60ms |

## 4. 重要决策与已排除项

- **依赖**：`com.herohan:UVCAndroid:1.0.13`（Maven Central）。旧方案 `AndroidUSBCamera:2.3.6`
  依赖已下架的 `com.serenegiant:common:4.1.1`，必然解析失败，已弃用。
- **相机 Type-C UVC（USB Streaming）已移除**：索尼官方文档明确串流期间 Picture Profile 被禁用，
  没有 S-Log3，不符合目标。要 Type-C + Log 必须走 PTP（见路线图 M4）。
- **内置索尼 LUT 的版权**：文件头部为 `Copyright 2023 SONY`，已由项目所有者提供。
  仓库/Release 目前**公开**，即公开再分发；如无授权请把仓库设为 private 或撤下文件。
  详见 `app/src/main/assets/luts/SOURCE.txt` 与 README「LUT 管理」。

## 5. 下一步（按优先级）

1. **M4.1 PTP 连通性**：USB Host → claim 接口 → `OpenSession(0x1002)` → `GetDeviceInfo(0x1001)`
   → 索尼握手 `SDIO_CONNECT(0x9201)` phase 1/2/3 → `SDIO_GET_EXT_DEVICE_INFO(0x9202)`
   → 读 `0xD221`（取景就绪）。成功即可确认相机接受自研 PTP。
2. **M4.2 出画面**：`GET_OBJECT(0x1009)` 句柄 `0xFFFFC002` → 8 字节头（JPEG 偏移/大小）
   → JPEG 解码 → GL 2D 纹理上屏；LUT/叠加复用。
3. **M4.3 稳定性**：取景就绪等待、事件监听、超时/断线恢复、严格 CloseSession（否则相机卡死需断电）。
4. 真机验收：按 `docs/BUILD-CHECKLIST.md` 第 9 节逐项勾。
5. 可选：M2 录制/推流、M3 波形/伪色。

> PTP 协议细节（含全部 opcode/属性/句柄）已整理在 `docs/PTP-FEASIBILITY.md`。

## 6. 当前版本与发布

- `versionCode 8` / `versionName 0.7.1`
- APK（公开直链，免登录）：
  `https://github.com/divercode/phone-monitor/releases/download/apk-latest/app-debug.apk`
- 发布流程见 `docs/DEVELOPMENT.md` 第 4 节
