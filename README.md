# PhoneMonitor · 手机监视器

用安卓手机 + USB HDMI 采集卡（UVC）当索尼微单的外接监视器，并实时套用 3D LUT。
相当于一个**开源、可魔改的 Monitor+ 精简替代品**（只做监看，不做相机控制）。

## 当前版本

- **v0.8.2（versionCode 11）**：CI 固定 debug 签名密钥，修复 APK "签名不同无法覆盖安装"
- **发版规则：每次更新必须同时递增 `versionCode` 与 `versionName`**，见
  [docs/RELEASING.md](docs/RELEASING.md)；App 内「设置」底部可看到当前版本
- 直接下载最新包：GitHub Release `apk-latest`（公开，无需登录）

> 安装提示：如果你装过更早的 CI 构建（当时每次构建签名都不同），本次需要**先卸载旧版**
> 再安装；从 v0.8.2 起 CI 使用缓存固定的 debug 密钥，后续升级可直接覆盖安装。

## 开发 / 交接文档

- [AGENTS.md](AGENTS.md)：给 AI / DSH 助手的硬性规则（版本号、自检、CI、版权）
- [docs/PROGRESS.md](docs/PROGRESS.md)：当前进度快照（已完成 / 验证状态 / 待办）
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)：环境、构建、CI 发版、代码地图、关键坑、M4 路线
- [docs/PTP-FEASIBILITY.md](docs/PTP-FEASIBILITY.md)：Type-C PTP 方案可行性（协议常量）


## 已实现功能（MVP）

| 功能 | 说明 |
|---|---|
| UVC 实时预览 | 采集卡画面经 SurfaceTexture 直连 GL 外部 OES 纹理，零 CPU 拷贝 |
| 3D LUT | 导入 `.cube`，GPU 双线性+切片混合（三线性）实时套用 |
| 内置 LUT | **8 个索尼官方 Look LUT**（只读、**不可删除**）；用户 LUT 可导入、可删除 |
| 峰值对焦 | 基于梯度幅值的索边染色，阈值可调 |
| 斑马纹 | 按显示亮度阈值叠加斜纹，阈值可调（默认 85%） |
| 构图网格 | 三分线 + 中心十字，可叠加 10% 安全框 |
| 直方图 | 160×90 离屏渲染 + glReadPixels 降采样统计 |
| 变形宽银幕 | ×1.33 / ×2.0 横向拉伸还原 |
| 测试图案 | 没插采集卡也能出画面，先验证 LUT 管线 |
| 系统相机测试源 | 可选开关：用手机摄像头当输入源，验证 LUT/峰值/斑马/网格/直方图（需相机权限） |
| 帧率显示 | HUD 实时显示采集出帧能力（每 30 帧统计一次） |
| 低延迟模式 | 格式优先未压缩 + 关闭垂直同步，可比默认少 1~2 帧延迟 |
| 插卡自动启动 | `device_filter.xml` 只匹配 USB 视频类设备，插采集卡自动拉起 App |
| 输入源 | HDMI 采集卡（UVC，带 S-Log3，已支持）；Type-C PTP/PC Remote（带 S-Log3，M4 待实现） |

## 技术栈

- Kotlin，单 Activity，UI 全部代码构建（无 XML 布局依赖）
- OpenGL ES 2.0（`GLSurfaceView` + 自定义 shader）
- UVC：`com.herohan:UVCAndroid:1.0.13`（Maven Central 托管、持续维护；AAR 内含
  saki4510t/libuvccamera 的 `com.serenegiant.usb.*` 与各 ABI 的 native 库）

> 依赖选型说明：原本首选的 `AndroidUSBCamera:2.3.6` 依赖 `com.serenegiant:common:4.1.1`，
> 该构件只存在于已关停的 jcenter，Maven Central 上不存在（实测 404），现代 Gradle 必然解析失败，
> 因此改用 Central 上的 `UVCAndroid`。

## 目录结构

```
app/src/main/java/com/phonemonitor/monitor/
├── MainActivity.kt            # 全屏预览、LUT 导入菜单、设置面板、状态栏
├── gl/
│   ├── MonitorRenderer.kt     # 核心：OES 采样 → LUT → 叠加效果 → 直方图
│   ├── GlProgram.kt           # 着色器编译/链接封装
│   └── Shaders.kt             # 顶点/片元 GLSL
├── lut/
│   ├── CubeParser.kt          # .cube 解析（支持 DOMAIN、注释、TITLE）
│   ├── Lut3D.kt               # 3D LUT 数据 + 2D 图集打包（带 1px 边距）
│   └── LutStore.kt            # 私有目录存储、SAF 导入、选中状态
├── uvc/
│   ├── FrameSource.kt         # 输入源抽象（为将来 Wi-Fi 通道留口）
│   └── UvcCameraSource.kt     # CameraHelper（UVCAndroid）打开采集卡
├── settings/MonitorSettings.kt# SharedPreferences 持久化
└── ui/HistogramView.kt        # 直方图叠层

app/src/test/java/com/phonemonitor/monitor/lut/
├── CubeParserTest.kt          # .cube 解析单元测试（纯 JVM）
└── Lut3DTest.kt               # 图集打包单元测试（纯 JVM）
app/src/main/res/xml/device_filter.xml  # 仅匹配 USB Video Class(14) 设备

docs/BUILD-CHECKLIST.md        # 首次编译 9 步检查清单
LICENSE                        # MIT（第三方库为 Apache-2.0）
.tools/                        # 无 SDK 环境下的自检脚本（Python）
samples/                       # 示例 .cube（恒等 LUT 用于验证管线）
.github/workflows/android.yml  # GitHub Actions：单测 + 自动编译 APK
```

## 构建与运行

1. 安装 **Android Studio**（自带 JDK 17 与 SDK 管理器）
2. 用 Android Studio 打开本目录（`D:\手机监视器`），首次同步会自动下载 Gradle 8.7 与依赖
   - 若提示找不到 `gradle-wrapper.jar`：在项目根目录执行 `gradle wrapper --gradle-version 8.7`，
     或在 AS 的 Gradle 设置里改用「本地安装的 Gradle / 指定版本」
3. 手机开启开发者模式 + USB 调试，连接后 Run
4. App 首次检测到采集卡会弹系统 USB 授权，允许即可

**首次编译请对照 [docs/BUILD-CHECKLIST.md](docs/BUILD-CHECKLIST.md)**：9 步检查清单，
含 Gradle wrapper 缺失时的三种处理方式、Sync 报错对照表、装机后的验收项。

**没有本地 Android 环境时**：把工程推到 GitHub，`.github/workflows/android.yml` 会自动跑单元测试、
编译 Debug APK 并作为 artifact 上传，编译日志/报错可直接从 Actions 页面取回。

**单元测试（无需真机）**：`app/src/test/` 下有 12 个纯 JVM 用例，覆盖 `.cube` 解析（含 DOMAIN 归一化、
1D LUT 拒绝、数据不足报错）与 LUT 图集打包（tile 尺寸、1px 边距复制、角点取值）。装好 Android Studio 后：

```powershell
gradle testDebugUnitTest
```

> 编译状态：GitHub Actions 已实测通过（JDK 17 + SDK 34 + Gradle 8.7），
> 单测 12 例与 `assembleDebug` 均为成功，可在 Actions 页面下载 APK。
> 真机（采集卡/相机）运行仍需按 [docs/BUILD-CHECKLIST.md](docs/BUILD-CHECKLIST.md) 验收。

## 使用步骤

1. 接入方式（只保留能出 Log 的方案，见下节）：
   - **HDMI 采集卡（当前可用）**：相机 HDMI 分辨率设 1080p、关闭 HDMI 信息显示、画面风格选 **S-Log3**
   - **Type-C PTP/PC Remote（M4 待实现）**：一根线看 Log，尚未完成
2. 插上后 App 顶部状态变绿字「UVC 已连接 1920x1080」
3. **先验证管线**：导入 `samples/identity-17.cube`（恒等 LUT），画面应与不开 LUT 完全一致
4. 再导入正式 LUT：索尼官方 S-Log3→Rec.709 还原 LUT（下载地址见 `samples/README.md`）
5. 点 **设置** 打开峰值/斑马/网格/直方图；点画面空白处可隐藏顶栏

## 只保留两种"能出 Log"的方案

> 已移除：相机「USB 串流 / USB Streaming」的 UVC 模式。它的限制是相机固件造成的
> ——串流期间 Picture Profile 被禁用，**没有 S-Log3**，与本项目"Log + LUT 监看"的目标不符。

### A. HDMI + 采集卡（当前可用，推荐）

相机 HDMI → MS2130 采集卡 → 手机 USB-C。延迟约 60~110ms，1080p60，**完整保留 S-Log3/PP**。
这是目前唯一已验证可用的 Log 路径。

### B. Type-C PTP / PC Remote（M4，Monitor+ 同款机制）

Monitor+ / 索尼 Monitor & Control 走的是索尼 **PTP 遥控协议**（Camera Remote Command，
PTP 的私有扩展）：相机保持完整拍摄状态，实时取景画面**带着 Picture Profile（含 S-Log3）**
传上来，App 侧再套 LUT。一根 USB-C 线即可，不需要采集卡。

已核实的官方资料：

- [Sony Camera Remote SDK](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)：
  支持 **ILCE-7M4**，接口 USB / 有线 LAN / Wi-Fi；但 **Supported OS 没有 Android**
  （仅 macOS / Windows / Linux）——所以安卓端必须自己实现 PTP
- [Sony Camera Remote Command](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html)：
  PTP 私有扩展的公开参考（免费，面向企业客户）
- 开源对照实现 [nredd/tethercam](https://github.com/nredd/tethercam)：USB-C + PTP + 相机
  「PC Remote」模式取流；实时取景分辨率由相机决定（a7 III 为 1024x680 @ ~15fps）

**状态：尚未实现（M4）**。**可行性已逐条验证**（协议常量、Android API、风险对策）：
见 [docs/PTP-FEASIBILITY.md](docs/PTP-FEASIBILITY.md)。实现要点：Android USB Host → PTP 会话
→ 索尼 SDIO 握手（0x9201/0x9202）→ 等 0xD221 就绪 → `GET_OBJECT(0xFFFFC002)` 取 JPEG
→ 解码上传为 GL 2D 纹理（现有 LUT/叠加全部复用）。
代价：延迟高于 HDMI，取景分辨率/帧率由相机决定；收益：一根 Type-C 线看 S-Log3。

## LUT 管理

### 内置：8 个索尼官方 Look LUT（只读、不可删除）

| 文件名 | 显示名 | 输入 |
|---|---|---|
| `SL3SG3CtoWarm_709` | 索尼·暖调 709 | S-Log3 / S-Gamut3.Cine |
| `SL3SG3CtoCool_709` | 索尼·冷调 709 | S-Log3 / S-Gamut3.Cine |
| `SL3SG3CtoTealOR_709` | 索尼·青橙 709 | S-Log3 / S-Gamut3.Cine |
| `SL3SG3CtoVintage_709` | 索尼·复古 709 | S-Log3 / S-Gamut3.Cine |
| `SL3SG3toWarm_709` | 索尼·暖调 709 | S-Log3 / S-Gamut3 |
| `SL3SG3toCool_709` | 索尼·冷调 709 | S-Log3 / S-Gamut3 |
| `SL3SG3toTealOR_709` | 索尼·青橙 709 | S-Log3 / S-Gamut3 |
| `SL3SG3toVintage_709` | 索尼·复古 709 | S-Log3 / S-Gamut3 |

- 打包位置：`app/src/main/assets/luts/`；LUT 菜单里标注「内置」，删除列表不会出现，
  代码层 `deleteUserLut()` 直接拒绝
- **版权说明**：这些是 Sony 官方 LUT，版权归 Sony 所有，不受本项目 MIT 许可覆盖
  （见 `assets/luts/SOURCE.txt`）。由项目所有者提供并纳入内置；**对外公开分发前请自行确认
  已获得 Sony 再分发授权，或将仓库与 Release 设为私有**

### 用户 LUT

LUT 菜单 →「导入新文件」，复制到应用私有目录，可随时通过「删除…」删除；
删除正在使用的 LUT 会自动关闭 LUT。

## 延迟优化（HDMI 链路）

预算（1080p60，各段为典型范围，合计约 **60~110ms**）：

| 环节 | 典型延迟 | 说明 |
|---|---|---|
| 相机 HDMI 输出 | ~1 帧（16.7ms） | 机身内部处理，无用户可调项 |
| 采集卡 | 未压缩 10~30ms / MJPEG 30~60ms | MJPEG 要卡片编码 + 手机解码，是主要可优化项 |
| USB 传输 + 出帧 | 1~2 帧 | 受采集卡与 USB 带宽影响 |
| 手机 GL 渲染 + 合成 | 1~2 帧 | 本 App 单 pass 渲染，开销很小 |
| 显示垂直同步 | 0~16.7ms | 60Hz 屏等 vsync 的最坏值 |

**本 App 已实现的两项优化**（设置面板可开）：

1. **格式协商优先未压缩**：`UvcCameraSource` 按「1080p 未压缩 → 1080p MJPEG → 720p …」
   选择格式。支持 YUYV/NV12 的 USB3 采集卡会直接走未压缩，省掉编码/解码延迟。
2. **低延迟模式（`eglSwapInterval = 0`）**：关掉垂直同步、立即交换缓冲，少约 1 帧显示延迟；
   代价是可能轻微撕裂（可随时关掉）。

硬件/系统侧：

- 采集卡选 **USB3 + 支持 1080p60 未压缩（YUYV）** 的型号，比只有 MJPEG 的廉价卡明显更低延迟
- 手机屏幕刷新率越高越好（120Hz 可再省约 8ms），并关闭系统"智能刷新率"
- 相机端保持 1080p 输出（4K 增加带宽与处理）；关掉直方图可省一点 GPU（影响很小）

**怎么量延迟**：让相机对着另一台手机/显示器上的毫秒计时器（如网页秒表）拍，截图对比两边
时间差；或用高速摄影拍"相机屏幕 + 监视器画面"同框，按帧数差换算。


## 静态自检（无需 Android SDK）

```powershell
python .tools\check_syntax.py     # 括号配平 + XML 合法性
python .tools\check_symbols.py    # 跨文件符号：内部 import / 枚举常量 / 成员调用 / R.string / Manifest / 资源引用
python .tools\check_shaders.py    # uniform/attribute/varying 对齐 + glUniform 使用 + GLSL ES2 约束
python .tools\verify_lut_math.py  # 复刻着色器的 LUT 图集采样，与三线性参考实现对比
python .tools\check_docs.py       # 文档里的相对链接与仓库路径是否存在
python .tools\gen_sample_luts.py  # 重新生成 samples/ 下的示例 LUT（带自检）
```

当前结果：

- `check_syntax.py`：**15 个 Kotlin/kts 文件 + 4 个 XML 全部通过**（含单元测试源码）
- `check_symbols.py`：**内部引用 / 枚举 / 成员 / 资源 / Manifest 检查全部通过**
- `verify_lut_math.py`：恒等 LUT 与非线性 LUT 上，着色器图集采样与参考三线性插值的
  **最大误差 0.0020**（= 8bit 量化的 0.5/255，含切片边界采样），网格遮罩在三分线处 alpha=1.000
- `check_docs.py`：**4 个 Markdown 文件的相对链接与仓库路径全部有效**
- `gen_sample_luts.py`：恒等 LUT 与暖调 LUT 均通过「黑点=0、白点=1、单调」自检

> 编译已由 GitHub Actions 实测通过（单测 12 例 + `assembleDebug` 均成功），
> 静态脚本只是本地快速回归手段。

## 依赖核验（抓真实仓库元数据）

| 依赖 | 仓库 | 核验结果 |
|---|---|---|
| `com.herohan:UVCAndroid:1.0.13` | Maven Central | ✅ AAR 已发布（2.76 MB，sha256 `4f1295a6…`），模块元数据里的依赖与 POM 一致 |
| `cn.hutool:hutool-core:5.8.35`（含 parent） | Maven Central | ✅ POM 存在 |
| `org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24` | Maven Central | ✅ POM 存在 |
| `androidx.appcompat:1.7.0` / `core-ktx:1.13.1` / `activity` | Google Maven | ⚠️ 服务端以 `application/octet-stream` 返回，抓取工具无法读取；版本为官方稳定发布 |
| `com.android.tools.build:gradle:8.5.2` | Google Maven | ⚠️ 同上（AGP 8.5.x 要求 Gradle 8.7，与工程配置一致） |

## 已知风险 / 待验证

- **UVC 集成已改用库的公开封装 `CameraHelper`**（`uvc/UvcCameraSource.kt`），接口签名逐项对照
  `UVCAndroid:1.0.13` 源码确认：
  `setStateCallback` → `selectDevice` →（`onDeviceOpen` 里）`openCamera(Size)` →
  （`onCameraOpen` 里）`addSurface(SurfaceTexture, false)` + `startPreview()`；
  暂停用 `stopPreview` / `removeSurface` / `closeCamera`，退出用 `releaseAll`。
  期望分辨率不合法时库会自动回落到支持的最大 MJPEG 尺寸，实际值用 `getPreviewSize()` 读回。
- **仍需真机验证的点**：`CameraHelper` 的 USB 权限申请流程在个别国产 ROM 上可能被系统策略拦截
  （表现为 `onCancel` 或一直停在测试图案）。遇到时先在系统设置里给本 App 放行 USB/OTG 权限，
  再重插采集卡。
- 部分国产 ROM 会拦截 USB 权限弹窗，需要在系统设置里给本 App 放行 OTG/USB 权限。
- 采集卡建议 MS2130 芯片、1080p60；老旧 MS2109 延迟高、易花屏。
- AAR 已确认包含 `armeabi-v7a / arm64-v8a / x86 / x86_64` 四套 native 库，64 位专属机型可正常运行。
- 直方图每 10 帧读一次 160×90 像素，功耗可忽略；如遇掉帧可调 `MonitorRenderer.frameCounter`。

## 里程碑

- **M1（已完成，CI 编译通过）**：预览 + LUT + 峰值 + 斑马 + 网格 + 直方图
- M2：录制（MediaCodec，烧录叠加）/ RTMP 推流
- M3：波形图、伪色、LUT 强度滑杆、峰值颜色可选
- M4：**索尼 PTP / PC Remote 输入（Type-C 或 Wi-Fi）**：实时取景带 Picture Profile（含 S-Log3），
  即 Monitor+ 的同款机制；渲染层需要新增 2D 纹理输入路径

## 与 Monitor+ 的差异

本项目的定位是「**看懂并重做一个监看器**」：不逆向、不破解任何商业 App，
功能边界、性能取舍（110ms 内延迟、1080p、GPU LUT）全部在 `docs/ARCHITECTURE.md` 里有解释。

## 许可证

本项目源码与示例 LUT 按 **MIT** 授权，见 [LICENSE](LICENSE)；
依赖的 `UVCAndroid`（含 libuvccamera）、AndroidX、Kotlin 标准库均为 Apache-2.0。

