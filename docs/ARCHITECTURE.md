# 架构说明

## 1. 数据流

```
索尼相机 HDMI ──▶ MS2130 采集卡 ──USB──▶ 手机
                                        │
                              USBMonitor（热插拔/权限）
                                        │
                                   UVCCamera
                                        │ setPreviewTexture
                                        ▼
                     SurfaceTexture（外部 OES 纹理，GL 线程）
                                        │ updateTexImage + getTransformMatrix
                                        ▼
                          MonitorRenderer（GLES2 单 pass）
                                        │
        ┌───────────────────────────────┼───────────────────────────────┐
        │ 采样 OES → LUT 三线性 → 斑马纹 → 峰值 → 网格/安全框 → 屏幕     │
        └───────────────────────────────┼───────────────────────────────┘
                                        │ 每 10 帧
                                        ▼
                     160×90 FBO 离屏渲染 → glReadPixels → 亮度直方图
```

关键点：**采集卡画面不进 CPU**。UVCCamera 把 YUV 帧直接送到 SurfaceTexture，
GL 采样外部纹理时由 EGL/驱动完成色彩转换（BT.601/709 由驱动决定，通常与相机输出匹配）。
这也意味着延迟 = 相机 HDMI 输出 + 采集卡编码 + 一帧 GL 渲染，实测约 60~110ms。

## 2. 模块职责

| 模块 | 职责 | 不做什么 |
|---|---|---|
| `uvc/UvcCameraSource` | 发现 UVC 设备、申请权限、打开/关闭相机、把 SurfaceTexture 交给 CameraHelper | 不碰 GL |
| `gl/MonitorRenderer` | 拥有 OES 纹理与 SurfaceTexture 生命周期、每帧渲染、LUT 上传、直方图读回、帧率统计 | 不碰 USB |
| `lut/*` | 解析 .cube、打包图集、存储与选择 | 不碰 GL 状态（只产出字节数组） |
| `MainActivity` | UI 与用户交互、把设置变化转成 `requestRender()` | 不做像素处理 |
| `settings/*` | 参数持久化 | 不含业务逻辑 |

> 帧率统计在渲染线程每 30 帧算一次（`onFps` 回调），Activity 只负责显示；
> 该值反映采集卡实际出帧能力，可作为「延迟是否够用」的直观参考。

## 3. 3D LUT 的 GPU 实现

1. **解析**：`.cube` 中数据按 R 变化最快排列，`data[(z*N + y)*N + x]`。
2. **打包**：把 N 个 B 切片排成 `T×T` 网格（T = ⌈√N⌉），每切片占 `(N+2)²` 像素：
   内容居中，四周各 1px 边距复制边缘值。这样 GPU 在切片内部做双线性插值时
   永远不会采样到相邻切片，避免串色。
3. **采样**（`Shaders.FRAGMENT`）：
   - 输入 RGB → 索引空间 `idx = clamp(rgb,0,1) * (N-1)`
   - `z0 = floor(idx.b)`、`z1 = min(z0+1, N-1)`，双线性取两个切片，按小数部分混合
   - 图集坐标：`uv = (tileOrigin + 1 + xy + 0.5) / dim`
4. **精度**：RGBA8 图集（8 位/通道），与主流监看 App 一致；若需要更平滑可换 RGBA16F（GLES3）。

## 4. 效果实现

- **峰值对焦**：对梯度幅值 `|L(uv±dx)| + |L(uv±dy)|` 做阈值判断，超过阈值把像素混向红色。
  偏移用 `uTexel = 1/previewSize`，与 LUT 无关，避免 LUT 压缩高光后边缘丢失。
- **斑马纹**：用**显示亮度**（套 LUT 之后）与阈值比较，斜纹由
  `fract((gl_FragCoord.x+gl_FragCoord.y)/14)` 生成。为什么用显示亮度：与监看所见一致，
  操作直觉更好；若要做严格的 Log 域过曝判断，把采样点换到 LUT 之前的 `c` 即可。
- **网格/安全框**：用帧缓冲像素坐标绘制，宽度约 1px；视口为 letterbox 居中，
  所以 uniform 传入视口矩形 `(uVideoOrigin, uVideoPixels)` 而不是全屏尺寸。
- **变形宽银幕**：`uv.x = 0.5 + (uv.x - 0.5) / desqueeze`，采样更窄的区域实现横向拉伸。

## 5. 线程模型

- **主线程**：UI、USB 权限、Settings 读写
- **USBMonitor 工作线程**：onAttach/onConnect 回调；`UvcCameraSource` 内部用 `lock` 串行化相机打开
- **GL 线程**：SurfaceTexture 创建/attach、每帧渲染、LUT 上传、直方图读回
- 跨线程交互只用两种手段：`GLSurfaceView.requestRender()`（线程安全）和 `@Volatile` 字段
  （`requestedPreviewSize`、`sourceConnected`、`currentLut` 引用）

## 6. 不变量

1. `SurfaceTexture` 只在 GL 线程创建，且只创建一个；上下文丢失后重建并重新 `attach`。
2. LUT 纹理上传只发生在 GL 线程，身份判断用 `===`（同一对象不重复上传）。
3. `previewWidth/Height` 必须与 `setDefaultBufferSize` 一致，否则 letterbox 比例会错。
4. 设置变化后必须 `requestRender()`，因为渲染模式是 `RENDERMODE_WHEN_DIRTY`（省电）。

## 7. 扩展点

- **新输入源**：实现 `FrameSource`（Wi-Fi/索尼 CRSDK 等），渲染层无需改动。
- **录制**：在 `MonitorRenderer` 加 FBO + MediaCodec Surface，录制已套 LUT 的画面；
  或用 `GLES20.glReadPixels` 走 ImageReader（性能较低）。
- **波形图**：复用直方图的 160×90 FBO，把每列亮度映射成折线，用 `Canvas` 叠层绘制。
- **更多 LUT 槽位**：`LutStore` 增加批量导入，`MonitorRenderer` 支持 LUT 强度混入（0~1 与原色 mix）。

## 8. UVC 集成

当前实现（`uvc/UvcCameraSource.kt`）走库的**公开封装** `com.herohan.uvcapp.CameraHelper`，
接口签名全部对照 `UVCAndroid 1.0.13` 源码确认：

```kotlin
UVCUtils.init(appContext)                 // 库里用静态 Application，先显式初始化
val helper = CameraHelper()
helper.setStateCallback(stateCallback)    // 回调统一在主线程派发

// 状态机：
// onAttach(device)              -> helper.selectDevice(device)   （库内部申请 USB 权限）
// onDeviceOpen(device,isFirst)  -> helper.openCamera(Size(...))  （不支持的尺寸会自动回落）
// onCameraOpen(device)          -> helper.getPreviewSize() 读回实际值
//                                  helper.addSurface(surfaceTexture, false)
//                                  helper.startPreview()
// onCancel(device)              -> USB 权限被拒绝
// onError(device, CameraException) -> 打开失败（BUSY 表示设备被占用）
```

生命周期约定：

- `start()`：持有 helper，查 `deviceList` 里第一个 UVC 设备并 `selectDevice`；没有设备就停在测试图案
- `stop()`（Activity.onPause）：`stopPreview` + `removeSurface` + `closeCamera`，**保留 helper**，
  下次 `start()` 只需重新 `selectDevice`
- `release()`（Activity.onDestroy）：`releaseAll()`，helper 置空；再次 `start()` 会重建

`addSurface` 支持 `Surface` / `SurfaceView` / `SurfaceHolder` / `SurfaceTexture`（源码确认），
因此渲染层完全不感知 UVC 的存在。

**输入源与分辨率协商**：当前 UVC 输入只面向 **HDMI 采集卡**（相机 USB Streaming 的 UVC 模式
已被移除——该模式禁用 Picture Profile，没有 S-Log3，不符合本项目目标）。
采集卡支持的尺寸差异较大，所以 `onCameraOpen` 里做一次协商：
优先 1080p MJPEG → 1080p 任意格式 → 720p MJPEG → 720p 任意格式 → 最大面积；
若当前尺寸不是目标尺寸，调用 `CameraHelper.setPreviewSize(Size)` 切换（异步排在同一条
handler 队列里，先于 `addSurface`/`startPreview` 执行）。


依赖选型踩坑记录：`com.github.jiangdongguo:AndroidUSBCamera:2.3.6` 的 POM 声明依赖
`com.serenegiant:common:4.1.1`，而该构件只存在于 jcenter（已关停），Central 上 404，
因此改用 `com.herohan:UVCAndroid:1.0.13`（Central 托管，AAR 内含
`armeabi-v7a / arm64-v8a / x86 / x86_64` 四套 native 库）。

**备选路径：直接使用库内原始类**（若将来 `CameraHelper` 行为不满足需求）。已核对的签名要点：
`UVCCamera` 只能用 `UVCCamera(UVCParam)` 构造（无无参构造，且必须在带 Looper 的线程），
`open(ctrlBlock)` 返回 int，期望尺寸放 `UVCParam(Size, quirks)`，实际尺寸用 `getPreviewSize()`
读回；`setPreviewTexture(SurfaceTexture)` → `startPreview()`，释放用 `destroy()`（内部先 `close()`）。

## 9. 生命周期时序

```
启动  onCreate：建 source / renderer / glView
      onResume：glView.onResume() → source.start()
      [GL 线程] onSurfaceCreated：建 OES 纹理 + SurfaceTexture → source.attach(ST)
      [库回调] onAttach → selectDevice → onDeviceOpen → openCamera(Size)
               → onCameraOpen：addSurface(ST, false) + startPreview()
      [GL 线程] onFrameAvailable → updateTexImage → 每帧渲染

暂停  onPause：source.stop()（stopPreview → removeSurface → closeCamera）→ glView.onPause()

恢复  onResume：glView.onResume() → source.start() → 复用保留的 ST 重新走 onCameraOpen 挂载

销毁  onDestroy：destroyed = true → source.release()（releaseAll，清空回调与 ST 引用）
```

两个容易踩的点，代码里已经处理：

1. **ST 与相机的先后顺序是双向的**：相机可能先打开（此时 ST 为空，只报状态、不调用
   `startPreview`），渲染器就绪后 `attach()` 再补挂载；反之 ST 先到，`onCameraOpen` 直接用。
2. **GL 上下文丢失**会创建新 ST：`attach()` 检测到 ST 换了，先对旧 ST `removeSurface` 再挂新的，
   避免库内部持有已失效的纹理。

另：Activity 销毁后 USB/GL 线程仍可能有迟到回调，统一用 `destroyed` 标志在
`runOnUiThread` 里挡掉，避免触碰已销毁的视图。

## 10. 三种视频传输方式的区别（重要）

| 方式 | 协议 | 能否看到 S-Log3 | 延迟 | 代价 |
|---|---|---|---|---|
| HDMI + 采集卡 | HDMI → UVC | ✅（相机 HDMI 输出带 PP） | 60~110ms | 需要采集卡 + HDMI 线 |
| Type-C UVC（USB Streaming） | UVC | ❌ 官方文档：串流期间 Picture Profile 禁用 → **本项目已移除此输入** | 较低 | 一根线，但无 Log |
| Type-C / Wi-Fi PTP（PC Remote） | Sony PTP 私有扩展 | ✅（实时取景带 PP） | 较高（JPEG 取景流） | 需要自己实现 PTP；取景分辨率由相机决定 |

Monitor+ / 索尼 Monitor & Control 走的是第三条（PTP）。索尼官方
[Camera Remote SDK](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html) 支持 ILCE-7M4，
但 **Supported OS 不含 Android**，所以安卓端只能实现 PTP 协议本身
（[Camera Remote Command](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html)
是公开的 PTP 私有扩展参考；开源参考实现如 [tethercam](https://github.com/nredd/tethercam)）。
本项目当前实现的是前两条；第三条是 M4 里程碑。


