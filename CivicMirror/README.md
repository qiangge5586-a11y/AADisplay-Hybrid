# CivicMirror

面向 2019 款本田思域劲动版原厂 USB Android Auto 车机的独立 Android 镜像项目。

## 当前状态

**0.5.0：手机端屏幕采集、H.264 硬件编码、MP4 本地验证和 USB AOA accessory 访问诊断已接入；AAP/TLS 车机协议尚未实现。**

已实现：
- MediaProjection 用户授权与前台服务
- VirtualDisplay 直接输出到 MediaCodec Surface，避免 ImageReader RGBA CPU 拷贝
- H.264 / AVC 编码（默认 1280 长边、30 fps、4 Mbps、无 B 帧以降低延迟）
- 根据编码器输出格式建立 MP4 容器，写入视频样本、关键帧标记和 PTS
- 捕获尺寸变化时创建新的编码器和独立 MP4 分段
- 录制文件保存在应用专属外部 Movies/CivicMirror 目录
- USB AOA accessory 枚举、系统权限请求和 USB 句柄打开诊断
- 不修改手机物理屏幕分辨率或 DPI

尚未实现：
- AAP 版本协商、TLS 双向认证和服务发现
- Android Auto 视频通道及 H.264 数据传输
- OpenGL 画面缩放、Fit/Fill 和自定义分辨率设置 UI
- 车机触控回传
- DHU / 本田实车视频投屏验证

## USB AOA 诊断

1. 安装 CivicMirror，打开应用。
2. 使用支持数据传输的 USB 线连接车辆原厂 Android Auto USB 口。
3. 解锁手机，等待车机启动 Android Auto。
4. 点击“检测 USB AOA 接口”，按系统提示授权。
5. 查看返回的 manufacturer、model、version，以及是否成功打开 USB 句柄。

此诊断只确认应用能否访问 Android Auto USB accessory，不会发送 AAP 协议数据，也不能证明车机已经接受 CivicMirror 的 TLS 身份。

## AAP 协议可行性与兼容风险

Android Auto 手机端投屏需要 USB AOA 传输、AAP 帧封装、TLS 双向认证、服务发现、视频通道协商、流控和输入通道；仅有 MediaProjection 和 H.264 编码远远不够。

公开实现 AARE（GPL-3.0）报告：
- USB AOA 和 AAP 手机端协议栈已有实现。
- 它在一台 2026 Toyota Land Cruiser 原厂 Denso Ten 车机上通过 USB 工作，但这不能推断 2019 Honda Civic 兼容。
- AARE 的手机屏幕镜像功能仍标注为尚未在真实车机上验证。
- 使用 Google 接收端库的车机可能要求 Google Automotive Link CA 签发的手机证书；AARE 的自签名证书在 Google DHU 上会被拒绝。部分 OEM 车机则接受自签名证书。

参考项目：
- https://github.com/prototux/AARE
- https://github.com/mretallack/AndroidAuto
- https://github.com/mrmees/open-android-auto

CivicMirror 目前只采用公开协议资料作技术参考，未复制上述项目的代码。下一阶段先实现独立 AAP 诊断握手，识别本田车机的协议版本、服务发现结果和证书拒绝状态，再决定是否启动视频通道。不会提取或伪造 Google 私有签名证书。

## MP4 验证方法

1. 点击“开始屏幕捕获与 MP4 录制”，在系统弹窗中允许。
2. 保持屏幕显示动态内容一段时间。
3. 点击“停止捕获并保存 MP4”。
4. 检查 `Android/data/com.civicmirror.debug/files/Movies/CivicMirror/` 下生成的 MP4。
5. 用手机播放器打开 MP4，确认画面和时序正常。

## 构建

需要 JDK 17、Gradle 8.9、Android SDK 35。

在本目录执行：

```bash
gradle :app:assembleDebug
```

APK 预期输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 重要说明

- MediaProjection 每次会话都必须取得用户授权。
- Android 14+ 对 MediaProjection 会话及 VirtualDisplay 生命周期有额外限制。
- DRM / FLAG_SECURE 内容可能无法捕获。
- 编码分辨率不等于修改手机物理屏幕分辨率。
- 当前版本不是车机投屏成品；AAP/TLS、视频通道和触控回传仍未实现。
