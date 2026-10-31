# CivicMirror

面向 2019 款本田思域劲动版原厂 USB Android Auto 车机的独立 Android 镜像项目。

## 当前状态

**0.9.0：新增持久 USB AOA 原始双向传输句柄管理；尚未完成 AAP/TLS、服务发现和车机视频投射。**

已实现：
- MediaProjection 用户授权与前台服务
- VirtualDisplay 直接输出到 MediaCodec Surface，H.264/AVC 编码及 MP4 分段录制
- USB AOA accessory 枚举、系统权限请求
- 持久 USB accessory 文件描述符；输入/输出 fd 分离，避免一个流关闭时误关另一个方向
- USB accessory detach 广播触发传输清理，应用销毁时关闭句柄
- AAP 原始帧读写、明文消息分帧、扩展长度与输入大小边界检查
- AAP 消息分片重组和明文双向流会话 JVM 单元测试
- 不修改手机物理屏幕分辨率或 DPI

尚未实现：
- AAP TLS 1.2 双向认证、加密载荷/明文帧头分层
- AAP 版本交换、Service Discovery、动态通道绑定
- Android Auto 视频通道及 H.264 数据传输
- Fit/Fill、自定义分辨率和 FPS 设置 UI
- 车机触控回传
- DHU / 本田实车视频投屏验证

## 传输边界

0.9.0 只打开并保持 Android UsbAccessory 对应的双向文件描述符，未向车机写入数据，也未启动 AapStreamSession。当前 AapStreamSession 的帧编解码路径是明文模式；在 TLS/AAP 记录分层完成前，不能把 TLS 字节流直接交给它，否则会错误解析 TLS record 为 AAP 帧。USB 句柄打开成功仅证明 Android 系统允许应用访问 accessory，不证明车机接受 CivicMirror 的协议身份。

## USB 测试

1. 安装 APK，使用支持数据传输的 USB 线连接原厂车机 USB 口。
2. 解锁手机并启动 Android Auto。
3. 点击“连接并保持 USB AOA 数据流”，按系统提示授权。
4. 界面应显示 accessory 描述及“双向句柄已保持打开”。
5. 点击“断开 USB 数据流”验证句柄关闭；拔线也应自动关闭。

若手机/车机没有进入 USB accessory 模式，应用会提示未发现设备。不同车机的 accessory 字符串可能不同；当前识别优先匹配 Android Auto，单设备时允许诊断连接。

## MP4 验证

点击“开始屏幕捕获与 MP4 录制”，在系统弹窗中允许；录制动态画面后点击停止，在 Android/data/com.civicmirror.debug/files/Movies/CivicMirror/ 检查 MP4。

## 构建

需要 JDK 17、Gradle 8.9、Android SDK 35。

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

## 重要说明

- MediaProjection 每次会话都必须取得用户授权；DRM/FLAG_SECURE 内容可能无法捕获。
- 编码分辨率不等于修改手机物理屏幕分辨率。
- Shizuku 不能替代 MediaProjection 授权，也不能创造 Android Auto 视频通道。
- 当前版本是开发测试版，不是可用的车机镜像成品；必须通过目标本田车机实测后才能判断兼容性。
