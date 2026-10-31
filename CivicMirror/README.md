# CivicMirror

面向 2019 款本田思域劲动版原厂 USB Android Auto 车机的独立 Android 镜像项目。

## 当前状态

**0.4.0：手机端屏幕采集、H.264 硬件编码和 MP4 本地验证录制已接入；车机传输尚未实现。**

已实现：
- MediaProjection 用户授权与前台服务
- VirtualDisplay 直接输出到 MediaCodec Surface，避免 ImageReader RGBA CPU 拷贝
- H.264 / AVC 编码（默认 1280 长边、30 fps、4 Mbps、无 B 帧以降低延迟）
- 根据编码器输出格式建立 MP4 容器，写入视频样本、关键帧标记和 PTS
- 捕获尺寸变化时创建新的编码器和独立 MP4 分段
- 录制文件保存在应用专属外部 Movies/CivicMirror 目录
- 不修改手机物理屏幕分辨率或 DPI

尚未实现：
- Android Auto 车机视频传输 / 接收端
- OpenGL 画面缩放、Fit/Fill 和自定义分辨率设置 UI
- 车机触控回传
- DHU / 本田实车验证

## MP4 验证方法

1. 安装 debug APK，打开 CivicMirror。
2. 点击“开始屏幕捕获与 MP4 录制”，在系统弹窗中允许。
3. 保持屏幕显示动态内容一段时间。
4. 点击“停止捕获并保存 MP4”。
5. 检查 `Android/data/com.civicmirror.debug/files/Movies/CivicMirror/` 下生成的 MP4；尺寸变化时会产生独立分段。
6. 用手机播放器打开 MP4，确认画面和时序正常。

若录制目录没有文件，或文件不能播放，先检查 Android 日志中的 `CivicMirrorAVC` 与 `CivicMirrorMP4` 标签。

## Android Auto 通道可行性

不能把 Android Auto Car App Library 的导航 Surface 当成通用投屏接口。Android Auto 对应用类别和 Surface 权限有明确限制；当前官方支持列表也不提供通用手机屏幕镜像类别。

Google 在 2026 年宣布计划逐步把停车状态下的视频应用体验扩展到 Android Auto，但这属于受支持视频应用的准入路线，并不等于任意屏幕捕获应用可以直接发布为视频应用。需要继续核实 rollout、兼容车机和应用准入要求。

参考：
- https://developer.android.com/training/cars
- https://developer.android.com/training/cars/parked/auto
- https://developer.android.com/docs/quality-guidelines/car-app-quality
- https://android-developers.googleblog.com/2026/05/android-for-cars-unifying-platforms-premium-experiences.html

## 构建

需要 JDK 17、Gradle 8.9、Android SDK 35。

在本目录执行：

```bash
gradle :app:assembleDebug
```

APK 预期输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 技术路线

`MediaProjection -> VirtualDisplay -> MediaCodec AVC Surface -> MP4 validation -> validated car transport`

触控回传作为独立模块验证，优先评估 AccessibilityService 的能力和限制。

## 重要说明

- MediaProjection 每次会话都必须取得用户授权。
- Android 14+ 对 MediaProjection 会话及 VirtualDisplay 生命周期有额外限制。
- DRM / FLAG_SECURE 内容可能无法捕获。
- 编码分辨率不等于修改手机物理屏幕分辨率。
- 普通侧载 APK 是否显示在真实 Android Auto 界面，必须通过 DHU 和实车验证。
- 当前 MP4 是手机端链路验证，不是车机镜像成品。
