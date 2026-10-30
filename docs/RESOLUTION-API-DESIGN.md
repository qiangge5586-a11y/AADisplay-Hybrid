# Custom virtual-display resolution: implementation contract

基于上游 `Nitsuya/AADisplay` 固定版本
`494dcc4909e8765e8798be4e7b8031fefd90a4eb` 的源码核查。

## 已确认的调用链

- AIDL: `aa-display/src/main/aidl/io/github/nitsuya/aa/display/xposed/ICoreManager.aidl`
- Binder 代理: `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/CoreManager.kt`
- system_server 服务: `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/CoreManagerService.kt`
- 虚拟屏生命周期: `aa-display/src/main/java/io/github/nitsuya/aa/display/ui/aa/split/SplitDisplayController.kt`
- 双 pane buffer 尺寸: `aa-display/src/main/java/io/github/nitsuya/aa/display/ui/aa/split/SplitVdLifecycle.kt`
- 设置界面: `aa-display/src/main/java/io/github/nitsuya/aa/display/ui/main/MainActivity.kt`
- 设置布局: `aa-display/src/main/res/layout/activity_main.xml`

## AIDL 兼容规则

现有 AIDL 已有大量历史方法。新方法只能追加到 `ICoreManager.aidl` 末尾，以保留旧 Stub transaction ordinals。至少需要：

```aidl
void setVdResolution(int width, int height);
int[] getVdResolution();
int[] getEffectiveVdResolution();
```

`CoreManager.kt` 实现了 `ICoreManager`，因此必须同步实现三个方法并转发到远端服务；不能只改 AIDL 或 Service。

## 服务端语义

建议 `[0,0]` 表示自动/跟随 HU；宽高必须同时为 0 或同时有效。拒绝单边为 0、负数、过小尺寸、超出安全像素预算的配置。DPI 继续沿用现有 `DisplayDpiStore`，不要重复造 DPI 存储。

分辨率覆盖必须与原始 HU profile 分离：
- 保留 HU 原始 width/height、Coolwalk rail 与 facet profile。
- 只在 HU profile settle 后生成 render profile。
- 不得把手动尺寸写回 HU negotiated profile，否则会破坏 rail 宽度、比例与触控映射。
- 手动配置持久化后，活动会话通过 controller reconnect/resize 路径应用；断连时只保存，不伪造显示已生效。

## 双 pane resize 的安全性

`SplitVdLifecycle.computePaneSizes()` 按横竖屏、分隔条、分屏比例和 fullscreen 状态分别计算两个 pane buffer。修改分辨率后仍必须走该计算函数，不能把同一个宽高直接塞给两个 pane。

当前 `resizePanesInternal()` 对两个 `VirtualDisplay.resize()` 独立 try/catch，仅记录异常，没有双屏一致性保护。实现时需要：
1. 先计算并校验 primary/secondary 两个目标尺寸。
2. 保存两个 pane 当前尺寸。
3. resize 两个 pane；任一失败时尝试恢复已成功调整的 pane。
4. 只有双 pane 都成功后才提交 last-size 缓存；失败时不把缓存标记成新尺寸。
5. 记录失败原因并允许后续 reconnect 重试。

## UI 与状态

在现有 DPI 设置卡片附近增加宽、高输入项、应用/恢复自动按钮，以及已保存/车机协商/当前生效状态。校验应与服务端一致。不要用 `wm size` 或 `wm density`，它们会改变手机物理显示配置，不是虚拟屏配置。

## Shizuku 与 LSPosed 边界

原版核心路径依赖 system_server 和 Android Auto 进程 Hook。Shizuku shell UID 无法替代这两个进程的 Hook 注入，也不能获得平台签名权限。因此：
- LSPosed 仍是原版 AA 双虚拟屏功能的必要激活路径。
- Shizuku 只用于确实可由 shell UID 执行的辅助操作。
- 不得宣传 Shizuku-only 可完整替代 LSPosed。

## 构建验收

只有当 CI 对带有上述 Android 主工程补丁的源码成功构建并上传 APK，才能标记为 Hybrid APK。上游基线 APK 不算完成版本。