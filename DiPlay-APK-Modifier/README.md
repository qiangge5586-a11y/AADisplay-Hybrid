# DiPlay — Android Auto APK 兼容性分析工具

当前版本：0.1.0（开发基础工程）

## 已实现
- 原生 Java Android 工程，中文界面
- 无 Root 运行
- 检测 Android Auto 与 Google Play 服务
- 扫描可启动的已安装应用
- 通过系统文件选择器导入 APK
- 将 APK 复制到应用私有缓存并计算 SHA-256
- 读取 APK 包名、版本及系统可解析的 Activity 信息
- 运行诊断日志

## 尚未实现
- 二进制 AndroidManifest.xml 只读解析器（首版已加入，仍需测试覆盖与接入界面）
- 二进制 Manifest 修改与回写
- APK 资源/DEX 重打包
- v2/v3 APK 签名
- 原签名与新签名差异检查
- 安装/回滚工作流
- Android Auto 18.0 实车识别验证

## 构建
使用 Android Studio 打开本目录。需要 JDK 17+、Android SDK Platform 35、Android Gradle Plugin 8.7.3。
本仓库尚未包含 Gradle Wrapper、Android SDK 或预编译 APK。

## 重要说明
Android Auto 对应用类别、声明和运行行为有限制。修改 Manifest 不保证任意应用都能被车机识别或安全运行。重新签名会改变证书，可能导致原应用更新失败、数据不能沿用或应用自身拒绝启动。只处理自己有权修改的 APK。


## 车机架构基线

目标车机是 **Linux 底层普通多媒体系统**，不是 Android 车机；用户已确认 USB 连接后能够正常进入 Android Auto。因此 DiPlay 当前只开发手机端 APK 分析与兼容性诊断，不开发车机 Android APK，也不假设车机能安装 Android 应用。

详细边界、开发顺序和实车测试记录见 [Linux 车机架构说明](docs/LINUX-HEAD-UNIT-ARCHITECTURE.md)。能进入 Android Auto 只证明现有连接链路可用，不代表车机或 Android Auto 支持任意应用投射。
