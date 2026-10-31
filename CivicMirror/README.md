# CivicMirror

面向 2019 款本田思域劲动版原厂 USB Android Auto 车机的独立 Android 镜像项目。

## 当前状态

**0.3.0：手机端屏幕采集 + H.264 硬件编码已接入；车机传输尚未实现。**

已实现：
- MediaProjection 用户授权与前台服务
- VirtualDisplay 直接输出到 MediaCodec Surface，避免 ImageReader 的 RGBA CPU 拷贝
- H.264 / AVC 编码（默认 1280 长边、30 fps、4 Mbps、无 B 帧以降低延迟）
- 编码包、字节数与 PTS 统计
- Android 14+ 捕获尺寸变化时重建编码器并切换现有 VirtualDisplay 的 Surface
- 不修改手机物理分辨率或 DPI

尚未实现：
- Android Auto 车机视频传输 / 接收端
- OpenGL 画面缩放、Fit/Fill 和自定义分辨率设置 UI
- 车机触控回传
- DHU / 本田实车验证

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

APK 预期输出：

```
app/build/outputs/apk/debug/app-debug.apk
```

仓库根目录的 GitHub Actions 工作流会构建本工程并上传 debug APK artifact。

## 技术路线

`MediaProjection -> VirtualDisplay -> MediaCodec AVC Surface -> encoded packet sink -> validated car transport`

触控回传作为独立模块验证，优先评估 AccessibilityService 的能力和限制。

## 重要说明

- MediaProjection 每次会话都必须取得用户授权。
- Android 14+ 对 MediaProjection 会话及 VirtualDisplay 生命周期有额外限制。
- DRM / FLAG_SECURE 内容可能无法捕获。
- 编码分辨率不等于修改手机物理屏幕分辨率。
- 普通侧载 APK 是否显示在真实 Android Auto 界面，必须通过 DHU 和实车验证。
- 当前编码包没有传输到车机，0.3.0 不是完整镜像成品。
