# PhoneMonitor · 手机监视器

用安卓手机 + USB HDMI 采集卡（UVC）当索尼微单的外接监视器，并实时套用 3D LUT。
相当于一个**开源、可魔改的 Monitor+ 精简替代品**（只做监看，不做相机控制）。

## 已实现功能（MVP）

| 功能 | 说明 |
|---|---|
| UVC 实时预览 | 采集卡画面经 SurfaceTexture 直连 GL 外部 OES 纹理，零 CPU 拷贝 |
| 3D LUT | 导入 `.cube`，GPU 双线性+切片混合（三线性）实时套用 |
| 峰值对焦 | 基于梯度幅值的索边染色，阈值可调 |
| 斑马纹 | 按显示亮度阈值叠加斜纹，阈值可调（默认 85%） |
| 构图网格 | 三分线 + 中心十字，可叠加 10% 安全框 |
| 直方图 | 160×90 离屏渲染 + glReadPixels 降采样统计 |
| 变形宽银幕 | ×1.33 / ×2.0 横向拉伸还原 |
| 测试图案 | 没插采集卡也能出画面，先验证 LUT 管线 |
| 帧率显示 | HUD 实时显示采集出帧能力（每 30 帧统计一次） |
| 插卡自动启动 | `device_filter.xml` 只匹配 USB 视频类设备，插采集卡自动拉起 App |
| 双输入 | HDMI 采集卡（UVC）或 **相机 Type-C 直连（USB Streaming）**，同一套渲染管线 |

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

1. 接入方式二选一（见下节「两种接入方式」）：
   - **HDMI 采集卡**：相机 HDMI 分辨率设 1080p、关闭 HDMI 信息显示、画面风格选 S-Log3
   - **Type-C 直连**：相机切到 USB Streaming 模式（不能拍 Log）
2. 插上后 App 顶部状态变绿字「UVC 已连接 1920x1080」
3. **先验证管线**：导入 `samples/identity-17.cube`（恒等 LUT），画面应与不开 LUT 完全一致
4. 再导入正式 LUT：索尼官方 S-Log3→Rec.709 还原 LUT（下载地址见 `samples/README.md`）
5. 点 **设置** 打开峰值/斑马/网格/直方图；点画面空白处可隐藏顶栏

## 两种接入方式

### A. HDMI + 采集卡（推荐：能拍 S-Log3，LUT 才有意义）

相机 HDMI → MS2130 采集卡 → 手机 USB-C。延迟约 60~110ms，1080p60。

### B. Type-C 直连（相机 USB Streaming，免采集卡）

索尼 A7M4 / ZV 系列自带 **USB Streaming**：把相机当 UVC 摄像头输出，手机直接当主机接收。
本 App 已支持（会自动把采集卡和相机都识别为 UVC 输入，并协商到 1080p 优先）。

相机设置（以 ILCE-7M4 官方说明为准）：

1. `MENU → 设置 → USB → USB 连接模式` 选 **`连接时选择`** 或 **`USB 串流`**
2. `MENU → 网络 → 串流 → USB 串流`，设置 **`输出分辨率/帧速率`**
   （可选 4K 15p / 12.5p、1080p 60p / 50p / 30p / 25p、720p 30p / 25p）
3. 用 USB-C 数据线连手机，相机屏显示 **`串流:待机`**
4. 若连接模式是「连接时选择」，在相机弹出的选项里选 **`实时串流(USB 串流)`**
5. 手机端 App 自动弹 USB 授权 → 允许 → 顶部显示 `UVC 已连接 1920x1080`

**必须知道的限制**（索尼官方文档明确写出）：

| 限制 | 影响 |
|---|---|
| 串流期间 **图像配置文件（Picture Profile）被禁用** | 不能拍 S-Log3 → LUT 只能当"风格滤镜"，做不了 Log 还原 |
| 视频格式为 MJPEG 或 YUV420；720p 仅 MJPEG | 1080p60 建议用 YUV420/MJPEG 均可；带宽不够会掉帧 |
| **USB 2.0 连接会被强制降到 720p30/25** | 想要 1080p 必须用 USB 3.x 的 C-to-C 数据线 |
| 相机由 USB 供电 | 手机会给相机供电，手机耗电明显，建议接充电器/带供电的 OTG Hub |
| 串流期间网络功能（遥控拍摄、FTP、手机遥控、蓝牙）不可用 | 只做监看没问题 |

> 结论：要**Log + LUT 监看**用方案 A；要**轻装、免采集卡**用方案 B（接受不能拍 Log）。

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

- **M1（当前）**：预览 + LUT + 峰值 + 斑马 + 网格 + 直方图
- M2：录制（MediaCodec，烧录叠加）/ RTMP 推流
- M3：波形图、伪色、LUT 强度滑杆、峰值颜色可选
- M4：索尼 Wi-Fi 直连通道（免采集卡应急监看）

## 与 Monitor+ 的差异

本项目的定位是「**看懂并重做一个监看器**」：不逆向、不破解任何商业 App，
功能边界、性能取舍（110ms 内延迟、1080p、GPU LUT）全部在 `docs/ARCHITECTURE.md` 里有解释。

## 许可证

本项目源码与示例 LUT 按 **MIT** 授权，见 [LICENSE](LICENSE)；
依赖的 `UVCAndroid`（含 libuvccamera）、AndroidX、Kotlin 标准库均为 Apache-2.0。

