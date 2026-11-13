# 开发文档（交接给另一台开发机 / DSH）

本文档面向"拿到仓库后继续开发"的开发者或 AI 助手。先读 `docs/PROGRESS.md` 了解当前状态。

## 1. 仓库与环境

- 仓库：<https://github.com/divercode/phone-monitor>（public，默认分支 `main`）
- 技术栈：Kotlin 1.9.24 / AGP 8.5.2 / Gradle 8.7 / JDK 17 / compileSdk 34 / minSdk 24
- 依赖：`com.herohan:UVCAndroid:1.0.13`（Maven Central）、androidx appcompat 1.7.0、core-ktx 1.13.1、JUnit 4.13.2
- UI：全部代码构建（无 XML 布局）；GLES 2.0 自定义渲染

```bash
git clone git@github.com:divercode/phone-monitor.git
cd phone-monitor
```

## 2. 快速开始

**有 Android Studio 的机器（推荐）**

1. 安装 SDK Platform 34、Build-Tools 34.0.0
2. Gradle JDK 选 17
3. 打开工程；仓库不含 `gradle-wrapper.jar`，首次可执行
   `gradle wrapper --gradle-version 8.7` 或让 IDE 使用本地 Gradle 8.7
4. 连接手机（开 USB 调试）后 Run

**命令行（已装 Android SDK + Gradle 8.7）**

```bash
gradle testDebugUnitTest   # 12 个纯 JVM 用例
gradle assembleDebug       # 产物 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**没有本地 SDK**：直接 push 到 `main`，GitHub Actions 会构建、跑单测，并把 APK
发布到 Release `apk-latest`（公开可下载）。

## 3. 代码地图

```
app/src/main/java/com/phonemonitor/monitor/
├── MainActivity.kt        # 全屏预览 + HUD + LUT 菜单 + 设置面板（全部代码建 UI）
├── gl/
│   ├── MonitorRenderer.kt # OES 采样 → LUT → 峰值/斑马/网格 → 直方图 FBO；帧率统计
│   ├── GlProgram.kt       # 着色器编译封装
│   └── Shaders.kt         # GLSL 顶点/片元（LUT 图集三线性、叠加效果）
├── lut/
│   ├── CubeParser.kt      # .cube 解析（DOMAIN/TITLE/注释）
│   ├── Lut3D.kt           # 3D LUT → 2D 图集（1px 边距防串色）
│   └── LutStore.kt        # 内置（assets/luts，只读不可删）/ 用户（filesDir/luts，可删）
├── uvc/
│   ├── FrameSource.kt     # 输入源抽象（UVC / 系统相机 / 未来 PTP 都接这里）
│   └── UvcCameraSource.kt # CameraHelper 状态机；分辨率/格式协商（未压缩优先）
├── camera/
│   └── SystemCameraSource.kt # 可选的系统相机测试源（Camera2 → SurfaceTexture）
├── settings/MonitorSettings.kt
└── ui/HistogramView.kt

app/src/main/assets/luts/            # 8 个索尼官方 Look LUT（内置，只读）
app/src/test/java/.../lut/           # CubeParserTest / Lut3DTest
.tools/                              # 校验脚本、发版脚本、推送脚本
docs/                                # 架构/构建清单/PTP 可行性/发版规则/进度
.github/workflows/android.yml        # CI：单测 + 构建 + Release + 失败日志
```

## 4. 发版与 CI 流程（必须遵守）

**版本号规则：每次更新都要同时递增**（见 `docs/RELEASING.md`）：

```bash
python .tools/bump_version.py          # 修复：patch+1
python .tools/bump_version.py --minor  # 功能：minor+1
```

提交信息前缀：`feat:` / `fix:` / `ui:` / `docs:` / `chore:` / `ci:`。

推送后 CI 自动：
1. `testDebugUnitTest`（先跑单测）
2. `assembleDebug`
3. 上传 artifact + 用 `gh release upload apk-latest --clobber` 更新公开 Release
4. **失败时**把日志推到 `ci-logs` 分支（公开可匿名拉取）

> **签名说明**：CI 通过 `actions/cache` 固定 `~/.android/debug.keystore`，保证各次构建的
> APK 签名一致、可覆盖安装。若缓存被清理（7 天未用）导致签名变化，需要卸载旧版重装一次；
> 也可在 workflow 里改 `key: phone-monitor-debug-keystore-v2` 主动换新密钥。

查看 CI 状态：

```bash
python .tools/wait_actions.py <commit-sha> 900   # 轮询到结束并打印失败步骤
python .tools/check_actions.py                   # 只看最近一次
```

失败排查：拉 `ci-logs` 分支读 `ci-logs/unit-tests.log` 或 `ci-logs/build.log`：

```bash
python .tools/fetch_ci_logs.py
```

> 注意：GitHub 未认证 API 有 60 次/小时限流；限流时可用
> `https://github.com/divercode/phone-monitor/releases.atom` 确认 Release 版本。

## 5. 提交前自检（推荐必跑）

```bash
python .tools/check_syntax.py     # 括号配平 + XML 合法（含测试源码与 .kts）
python .tools/check_symbols.py    # 跨文件 import/枚举/成员/R.string/资源/Manifest
python .tools/check_shaders.py    # shader uniform/attribute/varying 对齐、glUniform 使用、ES2 约束
python .tools/verify_lut_math.py  # 复刻 shader 的 LUT 图集采样 vs 三线性参考
python .tools/check_docs.py       # 文档相对链接与仓库路径
```

全部退出码为 0 再提交；CI 会做真正的编译兜底。

## 6. 关键设计决策（别踩回去）

1. **UVC 库**：只能用 `com.herohan:UVCAndroid`；旧库依赖已下架构件，Gradle 必挂。
2. **相机 USB Streaming（UVC）不支持 Log**：索尼文档明确 PP 被禁用，所以该输入已移除；
   不要在 `device_filter` 里重新加索尼 vendor-id 自动拉起。
3. **LUT 两类**：`assets/luts` = 内置只读不可删；`filesDir/luts` = 用户可删。
   `LutStore.deleteUserLut()` 对内置直接拒绝，UI 删除列表只列用户 LUT。
4. **索尼官方 LUT 版权**：内置文件头为 `Copyright 2023 SONY`；公开仓库/Release 属再分发，
   对外发布前需确认授权或转私有。
5. **渲染**：`RENDERMODE_WHEN_DIRTY`（有帧才画）；LUT 上传用 **direct ByteBuffer**
   （堆 buffer 会抛 `Buffer is not direct`）；`eglSwapInterval=0` 仅在低延迟模式。
6. **生命周期**：`onPause` 先关相机再暂停 GL；GL 上下文重建会换 SurfaceTexture，
   `UvcCameraSource.attach` 会先 `removeSurface(旧)`；Activity 销毁后回调用 `destroyed` 拦截。
7. **系统相机测试源**（`camera/SystemCameraSource.kt`）：Camera2 预览到同一 SurfaceTexture；
   通过 `FrameSource.Callback.onOrientation` 把传感器方向（90°/270°=1/3 个 90°）传给渲染器，
   着色器 `uQuarterTurns` 负责旋转、letterbox 宽高比同步交换。若真机画面方向相反，
   改该文件里的旋转符号或 renderer 的 `quarterTurns` 即可。

## 7. 路线图：M4 PTP（Type-C + S-Log3）

协议细节见 `docs/PTP-FEASIBILITY.md`，要点：

- 相机：`USB 连接模式 → 电脑遥控(PC Remote)`
- 标准 PTP：`OpenSession 0x1002` → `GetDeviceInfo 0x1001`
- 索尼握手：`SDIO_CONNECT 0x9201`（phase 1/2/3）→ `SDIO_GET_EXT_DEVICE_INFO 0x9202`
- 取景就绪：属性 `0xD221`
- 取帧：`GET_OBJECT 0x1009`，句柄 `0xFFFFC002`；返回 8 字节头 + JPEG
- 实现建议：新增 `ptp/PtpFrameSource.kt`；渲染层增加 2D 纹理输入路径；严格 CloseSession

风险：机型/固件差异、Android USB 权限、手机给相机供电；**会话未关闭会卡死相机需断电**。

## 8. 本机（当前 DSH 环境）特殊说明

这台机器**没有安装 git / JDK / Android SDK**，因此：

- 推送用纯 Python 的 dulwich 脚本（SSH key 在 `.tools/keys/`，已 gitignore）：
  ```bash
  python .tools/push_to_github.py --repo git@github.com:divercode/phone-monitor.git
  ```
- 编译靠 GitHub Actions；本机只能跑静态脚本。
- 网络偶发不稳定（TLS/限流），必要时重试。

换到有 git 的机器后，直接用标准 `git clone/commit/push` 即可，上面的脚本可不使用。

## 9. 真机验收

按 `docs/BUILD-CHECKLIST.md` 第 8~9 节：APK 安装 → 测试图案 → 插采集卡授权 →
状态栏 `UVC 已连接 WxH@fps` → identity LUT 不变色 → 官方 LUT 还原 → 各开关与低延迟模式。
