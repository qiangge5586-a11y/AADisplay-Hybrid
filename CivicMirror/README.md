# CivicMirror

面向 2019 款本田思域劲动版原厂 USB Android Auto 车机的独立 Android 镜像项目。

## 当前状态

**0.7.0：新增 AAP 消息分片重组层及 JVM 单元测试；尚未完成 AAP/TLS 握手和车机视频投射。**

已实现：
- MediaProjection 用户授权与前台服务
- VirtualDisplay 直接输出到 MediaCodec Surface，避免 ImageReader RGBA CPU 拷贝
- H.264 / AVC 编码（默认 1280 长边、30 fps、4 Mbps、无 B 帧以降低延迟）
- MP4 容器录制、视频样本、关键帧标记和 PTS
- 捕获尺寸变化时创建新的编码器和独立 MP4 分段
- USB AOA accessory 枚举、系统权限请求和 USB 句柄打开诊断
- AAP 原始帧读取、明文消息分帧、扩展长度与输入大小边界检查
- AAP 消息分片重组：按通道独立缓存，支持跨通道交错，限制消息长度和并发组装数
- AAP 帧编解码与消息重组 JVM 单元测试
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
5. 查看 manufacturer、model、version，以及是否成功打开 USB 句柄。

此诊断只确认应用能否访问 Android Auto USB accessory，不会发送 AAP 协议数据，也不能证明车机已经接受 CivicMirror 的 TLS 身份。

## AAP 协议实现顺序

AAP 使用单条双向字节流承载多路通道。帧层区分 BULK/FIRST/MIDDLE/LAST；FIRST 帧携带完整消息长度，消息分片可以在不同通道之间交错，但同一通道必须按序重组。

当前实现仅覆盖帧读写和消息重组。下一阶段依次实现：
1. USB accessory 双向流生命周期和断线清理
2. 协议版本交换与 TLS 1.2 双向认证状态机
3. Service Discovery、动态通道绑定和 Ping/ByeBye
4. 视频通道配置、H.264 SPS/PPS 与帧流控
5. 输入通道事件解析和可用权限范围内的触控处理

协议参考：
- https://github.com/mrmees/open-android-auto
- https://github.com/mretallack/AndroidAuto
- https://github.com/prototux/AARE

这些项目仅用于核对公开协议行为；CivicMirror 保持独立实现。不会提取或伪造 Google 私有签名证书。不同 OEM 车机的证书策略和协议能力可能不同，不能用 DHU 通过替代本田实车验证。

## MP4 验证方法

1. 点击“开始屏幕捕获与 MP4 录制”，在系统弹窗中允许。
2. 保持屏幕显示动态内容一段时间。
3. 点击“停止捕获并保存 MP4”。
4. 检查 Android/data/com.civicmirror.debug/files/Movies/CivicMirror/ 下生成的 MP4。
5. 用手机播放器打开 MP4，确认画面和时序正常。

## 构建

需要 JDK 17、Gradle 8.9、Android SDK 35。

在本目录执行：

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

APK 预期输出：app/build/outputs/apk/debug/app-debug.apk。

## 重要说明

- MediaProjection 每次会话都必须取得用户授权。
- Android 14+ 对 MediaProjection 会话及 VirtualDisplay 生命周期有额外限制。
- DRM / FLAG_SECURE 内容可能无法捕获。
- 编码分辨率不等于修改手机物理屏幕分辨率。
- 当前版本不是车机投屏成品；AAP/TLS、视频通道和触控回传仍未实现。
