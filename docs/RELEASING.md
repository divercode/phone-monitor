# 发版规则（重要）

**每次更新都必须递增版本号**，规则固定如下：

| 字段 | 规则 |
|---|---|
| `versionCode` | 每次发版 **+1**（整数，Play/系统用它判断新旧） |
| `versionName` | 语义化版本 `主.次.修`：功能更新 +0.1.0，修复 +0.0.1 |

位置：`app/build.gradle.kts` 的 `defaultConfig`。改完必须同步：
1. App 内「设置」面板底部的版本号会自动读取 `BuildConfig`，无需另改
2. README 的「当前版本」小节
3. 本轮变更写进提交信息（`feat:` / `fix:` / `ui:` 前缀）

## 推荐流程

```powershell
# 自动递增（默认 patch+1；功能更新用 --minor，重大版本用 --major）
python .tools\bump_version.py
python .tools\bump_version.py --minor

# 然后照常推送，CI 会自动构建并把 APK 发到 apk-latest Release
python .tools\push_to_github.py --repo git@github.com:divercode/phone-monitor.git
```

## 历史版本

| versionName | versionCode | 内容 |
|---|---|---|
| 0.5.0 | 5 | 内置 4 个 LUT（只读不可删）+ 用户 LUT 导入/删除（当前） |
| 0.4.0 | 4 | 桌面图标 + HUD/设置界面重做 |
| 0.3.0 | 3 | 移除无 Log 的相机 UVC 输入；延迟优化（未压缩优先、低延迟模式） |
| 0.2.0 | 2 | 相机 Type-C UVC（USB Streaming）支持、分辨率协商、CI 发布 APK |
| 0.1.0 | 1 | 首个可用版本：UVC 预览 + LUT + 峰值/斑马/网格/直方图 |
