# CivicMirror

面向 2019 款本田思域劲动版原厂 USB Android Auto 车机的独立 Android 镜像项目。

## 当前状态

**0.8.0：新增单连接 AAP 双向字节流会话管理及 JVM 单元测试；尚未完成 TLS 认证、服务发现和车机视频投射。**

已实现：
- MediaProjection 用户授权与前台服务
- VirtualDisplay 直接输出到 MediaCodec Surface，避免 ImageReader RGBA CPU 拷贝
- H.264 / AVC 编码（默认 1280 长边、30 fps、4 Mbps、无 B 帧以降低延迟）
- MP4 容器录制、视频样本、关键帧标记和 PTS
- 捕获尺寸变化时创建新的编码器和独立 MP4 分段
- USB AOA accessory 枚举、系统权限请求和 USB 句柄打开诊断
- AAP 原始帧读写、明文消息分帧、扩展长度与输入大小边界检查
- AAP 消息分片重组：按通道独立缓存，支持跨通道交错，限制消息长度和并发组装数
- AAP 双向字节流会话：单读线程、串行写入、完整消息回调、EOF/异常关闭和缓存清理
- AAP 帧编解码、消息重组和流会话 JVM 单元测试
- 不修改手机物理屏幕分辨率或 DPI

尚未实现：
- AAP 版本交换、TLS 1.2 双向认证和证书配置
- Service Discovery、动态通道绑定、Ping/ByeBye
- Android Auto 视频通道及 H.264 数据传输
- Fit/Fill、自定义分辨率和 FPS 设置 UI
- 车机触控回传
- DHU / 本田实车视频投屏验证

## 当前架构边界

AAP 运行在一条双向字节流上，多路通道通过帧头复用。当前 AapStreamSession 负责这条流的生命周期、帧读取、消息重组和串行写入；它不创建 USB 句柄，也不执行 TLS 或 protobuf 解码。上层必须先建立并授权传输，再接入 TLS/协议状态机。

AAP 参考资料：
- [Channel Architecture](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/architecture.md)
- [Version & SSL Auth](https://github.com/mrmees/open-android-auto/blob/main/docs/interactions/02-version-ssl-auth.md)
- [Open Android Auto 协议定义](https://github.com/mrmees/open-android-auto)

这些项目用于核对公开协议行为；CivicMirror 保持独立实现。不会提取或伪造 Google 私有签名证书。不同 OEM 车机的证书策略和协议能力可能不同，不能用 DHU 通过替代本田实车验证。

## USB AOA 诊断

1. 安装 CivicMirror，打开应用。
2. 使用支持数据传输的 USB 线连接车辆原厂 Android Auto USB 口。
3. 解锁手机，等待车机启动 Android Auto。
4. 点击“检测 USB AOA 接口”，按系统提示授权。
5. 查看 manufacturer、model、version，以及是否成功打开 USB 句柄。

此诊断只确认应用能否访问 Android Auto USB accessory，不会发送 AAP 协议数据，也不能证明车机已经接受 CivicMirror 的 TLS 身份。

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
- 当前版本不是车机投屏成品；TLS、服务发现、视频通道和触控回传仍未实现。
