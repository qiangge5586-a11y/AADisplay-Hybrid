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
- 完整二进制 AndroidManifest.xml 解码与修改
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
