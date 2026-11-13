# 首次编译检查清单

按顺序做完这 9 步，可以把「工程在 Android Studio 里跑起来」的失败概率降到最低。

## 0. 环境要求

| 项 | 要求 |
|---|---|
| Android Studio | Hedgehog (2023.1.1) 或更新，自带 JBR 17 |
| SDK Platform | **Android 14 (API 34)** |
| Build-Tools | **34.0.0** |
| Gradle | **8.7**（AGP 8.5.x 的要求） |
| JDK | **17**（用 AS 自带的 JBR 即可） |
| 磁盘 | 至少 10 GB 空闲 |

> 本项目**不需要 NDK**：UVCAndroid 的 native 库已随 AAR 打包（`armeabi-v7a / arm64-v8a / x86 / x86_64`）。

## 1. 安装 SDK 组件

Android Studio → **SDK Manager**：

- SDK Platforms：勾选 **Android 14.0 (API 34)**
- SDK Tools：勾选 **Android SDK Build-Tools 34**、**Android SDK Platform-Tools**
- 不需要勾选 NDK / CMake

## 2. 设置 Gradle JDK

`Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK` 选 **17**（通常是 `jbr-17`）。

> 若误用 JDK 11，会报 `Unsupported class file major version`。

## 3. 处理 Gradle wrapper

本仓库**不含 `gradle-wrapper.jar`**（这个工程是在没有 JDK/SDK 的环境里生成的），三选一：

- **A（推荐）** 装 Gradle 8.7 后在项目根执行：
  ```powershell
  gradle wrapper --gradle-version 8.7
  ```
  之后统一用 `.\gradlew` 构建。
- **B** `File → Project Structure → Gradle → Gradle distribution` 选 *Local installation*，指向已安装的 Gradle 8.7。
- **C** 让 Android Studio 在 Sync 时用 `gradle-wrapper.properties` 里声明的 8.7 自动下载（个别版本会因缺 jar 拒绝，失败就用 A）。

## 4. 首次 Sync 的预期

会下载：AndroidX（Google Maven）、UVCAndroid 与 hutool（Maven Central）、Kotlin 插件。

- `settings.gradle.kts` 已配置阿里云镜像优先，国内网络一般可直接过
- 以下**警告无害**：
  - `kotlinOptions` 已弃用的提示
  - UVCAndroid AAR 自带的 library manifest 警告

## 5. Sync 报错对照表

| 报错 | 处理 |
|---|---|
| `Unresolved reference: UVCUtils` / `CameraHelper` | 依赖未下载：确认 `mavenCentral()` 可达后执行 `gradle --refresh-dependencies` |
| `Could not find com.herohan:UVCAndroid:1.0.13` | 同上；检查镜像是否拦截了 Central |
| `SDK location not found` | 根目录建 `local.properties`：`sdk.dir=C\:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk` |
| `Unsupported class file major version` | 第 2 步的 Gradle JDK 改成 17 |
| `Namespace not specified` | 本工程已设 namespace；确认 AGP 是 8.5.2 |
| `Manifest merger failed` | 确认 `AndroidManifest.xml` 的 `xmlns:tools` 未被删；AGP ≥ 7 |

## 6. 编译前自检（可选，不需要 SDK）

```powershell
python .tools\check_syntax.py
python .tools\check_symbols.py
python .tools\check_shaders.py
python .tools\verify_lut_math.py
python .tools\check_docs.py
```

## 7. 跑单元测试

```powershell
gradle testDebugUnitTest
```

应看到 **12 个用例通过**（`.cube` 解析 6 个 + LUT 图集打包 6 个）。

## 8. 装到手机

```powershell
gradle assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

首次启动行为：

1. 画面是**彩条测试图案** → 说明 GL/LUT 管线已跑通
2. 接入视频源：**HDMI 采集卡**（插卡 → 系统弹 USB 授权 → 允许）。
   > 不要用相机的「USB 串流 / USB Streaming」模式：该模式禁用 Picture Profile、没有 S-Log3，
   > 本项目不支持。Type-C 直连要等 M4 的 PTP/PC Remote 输入。
3. 顶部状态变为 `UVC 已连接 1920x1080`（App 会优先协商 1080p）
4. 点 **LUT** 导入 `samples/identity-17.cube`，画面应**完全不变**（管线正确性自测）
5. 再导入索尼官方 S-Log3→Rec.709 LUT

## 9. 验收清单

- [ ] 无采集卡时显示测试图案，不崩溃
- [ ] HDMI 采集卡插上后出现实时画面，状态文字正确
- [ ] 相机 S-Log3 画面能正常显示，套 LUT 后还原正确
- [ ] 状态栏显示实际分辨率与帧率（如 `UVC 已连接 1920x1080@60`）
- [ ] 设置里「低延迟模式」开关生效（可能轻微撕裂，但主观更跟手）
- [ ] 插采集卡时系统自动拉起本 App（`device_filter` 只匹配视频类设备）
- [ ] HUD 帧率不是 `--`，数值与源标称接近（1080p60 约 55~60 fps）
- [ ] LUT 开关/切换生效（identity 无变化、warm 有暖调）
- [ ] 峰值对焦、斑马纹、网格、安全框、直方图开关均生效
- [ ] 设置面板底部显示正确的版本号（如 `v0.4.0（build 4）`）
- [ ] 横竖屏切换、按 Home 再回来不崩溃、不黑屏
- [ ] 拔插采集卡/相机线能自动恢复（等待图案 ↔ 实时画面）
