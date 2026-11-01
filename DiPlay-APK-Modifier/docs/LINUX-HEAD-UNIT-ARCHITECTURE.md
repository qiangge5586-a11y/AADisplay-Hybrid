# DiPlay 架构基线：Linux Android Auto 接收车机

## 已确认的用户环境

- 车机底层：Linux 普通多媒体系统，不是 Android。
- 车机能够通过 USB 与手机连接，并正常进入 Android Auto 界面。
- 因此，现阶段不把车机侧 APK、Android Manifest 或 Android 系统服务作为开发对象。
- 本项目的 APK 分析器运行在 Android 手机端；车机只作为现有 Android Auto 接收端参与实车验证。

## 架构边界

手机端负责：
1. 选择并检查用户有权分析的 APK。
2. 解析 APK Manifest、包信息、组件、权限和签名证书摘要。
3. 生成 Android Auto 兼容性诊断，不把静态分析结果误报为可投射保证。
4. 在后续阶段研究 Android Auto 支持的应用类别、启动/显示流程和限制。
5. 如开发合法且可验证的转换流程，保留原 APK、输出独立副本并提供回滚/恢复说明。

Linux 车机负责（现有系统能力，不由 DiPlay APK 修改）：
1. 通过 USB 建立 Android Auto 连接。
2. 作为接收端协商并呈现 Android Auto 投射会话。
3. 将触摸/按键等输入按车机自身实现回传。

## 关键判断

手机能正常进入 Android Auto，只能证明当前手机、线缆、车机接收端和现有 Android Auto 会话路径基本可用。它不能证明车机支持任意应用投射，也不能证明只增加 Manifest 元数据就能绕过 Android Auto 的应用类别与平台准入限制。

Android Auto 的投射画面不是把手机任意 Activity 的屏幕直接复制到车机。可显示内容受 Android Auto 平台、应用类别、应用实现方式及接收端能力共同约束。

## 下一阶段开发顺序

### A. APK 静态检查（优先）
- 读取 APK ZIP 中的 AndroidManifest.xml。
- 解析 Android 二进制 XML 的字符串池、资源映射和节点属性。
- 提取 package、version、minSdk、targetSdk、uses-permission。
- 提取 application、activity、activity-alias、service、receiver、provider。
- 提取 intent-filter、exported、enabled、launchMode、process 等字段。
- 提取 meta-data，单独标注与 Android Auto 相关的声明。
- 解析失败时明确报错，不用猜测值补齐报告。

### B. Android Auto 兼容性诊断
- 把识别到的组件与已知 Android Auto 支持类别作对照。
- 区分“发现声明”“结构上可分析”“平台允许”“实车验证通过”四种状态。
- 不自动给普通应用伪造导航、媒体、消息或其他类别身份。
- 对 DRM、登录安全、支付、Play Integrity、应用完整性校验等潜在限制作风险提示。

### C. 可逆改造与验证
- 仅处理用户拥有或明确获准修改的 APK。
- 原始 APK 永不覆盖；输出文件使用独立包名/签名策略前先评估应用自身限制。
- 任何重新签名都必须显示证书变化和更新/数据兼容风险。
- 每次修改生成前后 Manifest 差异报告和 SHA-256。
- 先在手机端验证安装与启动，再连接 Android Auto 18.0 和 Linux 车机验证。
- 失败时保留诊断日志和原始 APK，不宣称“任意应用都能投屏”。

## 实车验证记录模板

| 项目 | 结果 |
|---|---|
| Android 版本 | 待记录 |
| Android Auto 版本 | 18.0（用户提供） |
| 车机系统 | Linux 多媒体系统 |
| USB Android Auto 连接 | 已确认可进入界面 |
| 目标 APK 包名/版本 | 待测试 |
| Manifest 解析 | 待实现 |
| 应用类别/平台准入 | 待分析 |
| 车机显示结果 | 待实测 |
| 输入回传结果 | 待实测 |
| 日志与复现步骤 | 待实现 |

## 非目标

- 不开发或刷写 Linux 车机固件。
- 不把车机误判为 Android 设备。
- 不要求 Root、KernelSU、Zygisk 或 Shizuku 才能运行手机端分析器。
- 不承诺通过简单修改 Manifest 就能将任意应用变成 Android Auto 应用。
