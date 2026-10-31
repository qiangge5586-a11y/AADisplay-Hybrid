# CivicMirror

独立 Android 项目，目标设备为 2019 款本田思域劲动版原厂 USB Android Auto 车机。

## 当前状态

**0.1.0 工程骨架，不是可用的镜像版本。**

当前包含 Android 应用最小工程、启动页和 GitHub Actions 构建流程。尚未实现：
- MediaProjection 屏幕捕获
- Android Auto Car App / Surface 接入
- OpenGL ES 渲染链路
- 车机触控回传
- 分辨率、画面比例及帧率控制

## 构建

需要 JDK 17、Gradle 8.9、Android SDK 35。

从本目录运行：

```bash
gradle :app:assembleDebug
```

APK 预期输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

推送到 GitHub 后，在 Actions 页面运行 CivicMirror Android CI，可下载构建产物。

## 技术路线

计划采用：

`MediaProjection -> ImageReader -> OpenGL ES -> Android Auto Surface`

车机输入回传作为独立模块验证，优先考虑 AccessibilityService 的可行性和系统限制。

## 重要说明

- MediaProjection 必须取得用户授权。
- Android 14+ 对 MediaProjection 会话和 VirtualDisplay 生命周期有额外限制。
- DRM / FLAG_SECURE 内容可能无法捕获。
- 输出分辨率不等于修改手机物理屏幕分辨率。
- 普通侧载 APK 是否出现在真实 Android Auto 车机界面，取决于 Android Auto 的应用分发与兼容性限制；必须通过 DHU 和实车验证。
