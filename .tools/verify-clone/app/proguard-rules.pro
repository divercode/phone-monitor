# 本项目 release 未启用混淆（isMinifyEnabled = false）。
# 若后续开启 R8，UVC 库自带 consumer rules；下面两份是官方 README 要求的 keep 规则：
-keep class com.herohan.uvcapp.** { *; }
-keep class com.serenegiant.usb.** { *; }
-keepclassmembers class * implements com.serenegiant.usb.IButtonCallback { *; }
-keepclassmembers class * implements com.serenegiant.usb.IFrameCallback { *; }
-keepclassmembers class * implements com.serenegiant.usb.IStatusCallback { *; }
