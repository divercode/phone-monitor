# Type-C PTP（PC Remote）方案可行性验证

结论：**可以实现**。协议、Android 能力、参考实现三方面都已确认，唯一需要你配合的是
A7M4 实机联调（我这边没有相机）。

## 1. 为什么是 PTP 而不是 UVC

| 方式 | S-Log3 | 原因 |
|---|---|---|
| HDMI + 采集卡 | ✅ | HDMI 输出保留 Picture Profile |
| Type-C UVC（USB Streaming） | ❌ | 索尼官方文档：串流期间 Picture Profile 被禁用 |
| Type-C **PTP（PC Remote）** | ✅ | 相机保持完整拍摄状态，实时取景带 PP；Monitor+ 走的就是这条 |

## 2. 协议栈（已从开源实现逐条核对）

来源：[nredd/tethercam](https://github.com/nredd/tethercam)（Rust，MIT/开源；`src/ptp/*`、`src/sony/*`）
与 Sony PTP Command 文档。

| 层 | 内容 | 关键常量 |
|---|---|---|
| USB | 相机 USB 连接模式必须是 **PC Remote**（模式字节 `0x0c34`；MTP `0x0c03` 会失败） | claim 接口后走 bulk IN/OUT |
| PTP 容器 | 12 字节头：`length(u32) / type(u16) / code(u16) / transaction_id(u32)`；命令、数据、响应三类；参数上限 5 个 u32 | 大 payload 按 PTP 规则连续多包 |
| 会话 | `OpenSession(0x1002)` → `GetDeviceInfo(0x1001)` | 标准 PTP |
| 索尼握手 | `SDIO_CONNECT(0x9201)` 参数 `(phase,0,0)`，依次 phase 1/2/3 → `SDIO_GET_EXT_DEVICE_INFO(0x9202)` 参数 `(协议版本,1)` 拿属性/控制表 | 协议版本 `0x00C8`(v2.00) / `0x012C`(v3.00) |
| 取景就绪 | 读属性 **`0xD221` LIVE_VIEW_STATUS**，等到 ready（参考实现超时 10s） | 变更事件 `0xC203` 带 `0xD221` |
| 取帧 | **`GET_OBJECT(0x1009)`**，对象句柄 **`0xFFFFC002`** | 每帧超时约 2.5s |
| 帧格式 | 对象 = 8 字节头（JPEG 偏移 u32 + 大小 u32，小端）+ JPEG（以 `FFD8` 开头）；v3.00 机身在 JPEG 字段后追加对焦框信息 | — |
| 取景规格 | 分辨率/帧率由相机决定（参考：a7 III 为 1024x680 @ ~15fps） | — |

## 3. Android 侧映射（平台能力已确认）

| 需要的能力 | Android API | 结论 |
|---|---|---|
| 发现设备/授权 | `UsbManager.deviceList` + `requestPermission(PendingIntent)` | ✅ 标准能力 |
| 打开设备 | `UsbManager.openDevice(device)` | ✅ |
| 认领 PTP 接口 | `UsbDeviceConnection.claimInterface(intf, true)` | ✅ 无需内核驱动 |
| bulk 收发 | `bulkTransfer(endpoint, buffer, offset, len, timeout)` | ✅ 512 字节对齐、分包即可 |
| 传输异常恢复 | `controlTransfer(CLEAR_FEATURE, ENDPOINT_HALT)` / `setInterface` | ✅ 可做 |
| JPEG 解码 | `BitmapFactory.decodeByteArray` | ✅ |
| 上屏 | 新增 GL **2D 纹理**路径（现有 OES 路径保留给采集卡） | 需开发 |

## 4. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 握手/属性因机型/固件不同 | 连不上或取不到帧 | 按索尼协议版本分支 + 型号 quirks 表；先打全量日志 |
| **会话未关闭会卡死相机**（参考实现明确说明） | 相机要断电重启 | 严格异常路径 `CloseSession`；拔线/超时后重连前先复位 |
| 手机 USB 供电给相机 | 手机耗电快、部分机型供电不足 | 用带供电的 OTG Hub；提示用户 |
| 取景分辨率/帧率低（相机决定） | 清晰度/流畅度不如 HDMI | 定位为"免采集卡监看"，不替代 HDMI 低延迟场景 |
| Android 机型 USB Host 差异 | 个别 ROM 不认 PTP 接口 | 权限失败/枚举失败时给出明确提示 + 日志 |

## 5. 分阶段实现计划

- **M4.1 连通性验证**（不需要出画面，可先验证相机是否认）：
  USB 发现/授权 → claim 接口 → `OpenSession` → `GetDeviceInfo` → 索尼三步握手 →
  `SDIO_GET_EXT_DEVICE_INFO` → 读 `0xD221`。成功即打印协议版本与属性表。
- **M4.2 出画面**：`GET_OBJECT(0xFFFFC002)` 循环取帧 → 解析 8 字节头 → JPEG 解码 →
  GL 2D 纹理 → 现有 LUT/峰值/斑马/网格/直方图全部复用。
- **M4.3 稳定性与控制**：取景就绪等待、事件监听、超时/断线恢复、会话清理；
  之后再加快门/ISO/录制等控制。

## 6. 需要你配合的事项

1. 相机：`MENU → 设置 → USB → USB 连接模式 → 电脑遥控(PC Remote)`
2. 用 USB 3 数据线连手机；建议接供电 Hub（手机会给相机供电）
3. 提供相机固件版本；每轮测试后把 logcat 里 `PtpCameraSource` 标签的日志发我
4. 若某一步失败，告诉我停在哪一步（发现/授权/握手/取帧），我根据日志调整协议细节

## 7. 参考

- Sony Camera Remote SDK / Camera Remote Command 官方页
- tethercam 协议实现（PTP 容器、索尼 SDIO 扩展、实时取景对象解析）
- 索尼 ILCE-7M4 帮助指南：USB Streaming 与 Picture Profile 限制
