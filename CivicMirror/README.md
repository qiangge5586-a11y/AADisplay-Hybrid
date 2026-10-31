# CivicMirror

面向 2019 款本田思域劲动版原厂 USB Android Auto 车机的独立 Android 镜像项目。

## 当前状态

**0.1.0 工程骨架，尚不是可用的镜像版本。**

当前包含 Android 最小工程、启动页和 GitHub Actions 构建配置。尚未实现：
- MediaProjection 屏幕捕获
- Android Auto Car App / Surface 接入
- OpenGL ES 渲染
- 车机触控回传
- 分辨率、画面比例及帧率控制

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

## 目标技术路线

`MediaProjection -> ImageReader -> OpenGL ES -> Android Auto Surface`

触控回传作为独立模块验证，优先评估 AccessibilityService 的能力和限制。

## 重要说明

- MediaProjection 必须取得用户授权。
- Android 14+ 对 MediaProjection 会话及 VirtualDisplay 生命周期有额外限制。
- DRM / FLAG_SECURE 内容可能无法捕获。
- 输出分辨率不等于修改手机物理屏幕分辨率。
- 普通侧载 APK 是否显示在真实 Android Auto 界面，需通过 DHU 和实车验证。
