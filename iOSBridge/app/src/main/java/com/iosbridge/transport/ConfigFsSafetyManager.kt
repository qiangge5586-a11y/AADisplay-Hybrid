package com.iosbridge.transport

/** Pure decision layer. It never executes or emits an unconditional mutation sequence. */
object ConfigFsSafetyManager {
    data class Gadget(
        val name: String,
        val vendorId: String?,
        val productId: String?,
        val boundUdc: String?,
        val functions: Set<String>,
        val configLinks: Map<String, String>
    )
    data class Snapshot(
        val configFsMounted: Boolean,
        val gadgetRoot: String?,
        val udcs: Set<String>,
        val currentDataRole: String?,
        val requestedUsbConfig: String?,
        val activeUsbConfig: String?,
        val legacyAndroidUsbPresent: Boolean,
        val gadgets: List<Gadget>,
        val capturedAtEpochMs: Long
    )
    enum class Readiness { BLOCKED, REVIEW_REQUIRED, NO_SAFE_AUTOMATION }
    data class Assessment(
        val readiness: Readiness,
        val blockers: List<String>,
        val warnings: List<String>,
        val snapshotSummary: List<String>,
        val permittedNextSteps: List<String>
    ) {
        fun render(): String = buildString {
            appendLine("iOSBridge · ConfigFS 安全评估")
            appendLine("结论：${when (readiness) {
                Readiness.BLOCKED -> "阻塞：当前不应创建 Gadget"
                Readiness.REVIEW_REQUIRED -> "需要人工审阅：不允许自动变更"
                Readiness.NO_SAFE_AUTOMATION -> "环境线索可用，但仍不执行 USB 配置变更"
            }}")
            if (blockers.isNotEmpty()) {
                appendLine("\n阻塞项：")
                blockers.forEach { appendLine("- $it") }
            }
            if (warnings.isNotEmpty()) {
                appendLine("\n风险提示：")
                warnings.forEach { appendLine("- $it") }
            }
            appendLine("\n快照摘要：")
            snapshotSummary.forEach { appendLine("- $it") }
            appendLine("\n允许的下一步：")
            permittedNextSteps.forEachIndexed { index, value -> appendLine("${index + 1}. $value") }
            appendLine("\n安全边界：本评估不创建/删除 ConfigFS 对象，不改属性、不改 USB role、不绑定/解绑 UDC，也不保证 ADB/MTP 可恢复。")
        }
    }

    fun assess(snapshot: Snapshot, targetGadgetName: String = "iosbridge"): Assessment {
        require(NAME.matches(targetGadgetName)) { "Invalid gadget name" }
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val target = snapshot.gadgets.firstOrNull { it.name == targetGadgetName }
        if (!snapshot.configFsMounted || snapshot.gadgetRoot == null)
            blockers += "没有确认 ConfigFS 已挂载且 USB Gadget 根目录存在。"
        if (snapshot.udcs.isEmpty()) blockers += "没有发现可用 UDC。"
        if (snapshot.currentDataRole.equals("host", ignoreCase = true)) {
            blockers += "当前 USB data role 为 host，不能作为 USB Gadget 设备端。"
        } else if (snapshot.currentDataRole.isNullOrBlank()) {
            warnings += "当前 USB data role 未知，不能确认手机处于 device 角色。"
        }
        if (snapshot.legacyAndroidUsbPresent)
            warnings += "检测到 legacy Android USB gadget 节点；不要与 ConfigFS Gadget 并行改写。"
        if (!snapshot.requestedUsbConfig.isNullOrBlank() || !snapshot.activeUsbConfig.isNullOrBlank())
            warnings += "Android USB config 属性存在（requested=${snapshot.requestedUsbConfig ?: "空"}, active=${snapshot.activeUsbConfig ?: "空"}）；修改 Gadget 可能影响系统 USB 管理服务。"
        if (snapshot.gadgets.any { it.boundUdc?.isNotBlank() == true })
            warnings += "至少一个现有 Gadget 已绑定 UDC；当前不得抢占或解绑。"
        if (target != null)
            blockers += "目标 Gadget '$targetGadgetName' 已存在，无法证明其归本项目所有；禁止覆盖或接管。"
        if (snapshot.gadgets.any { it.name != targetGadgetName })
            warnings += "系统已有其他 ConfigFS Gadget；必须保留其所有属性、链接和绑定状态。"
        if (snapshot.gadgets.any { g -> g.functions.any { it.startsWith("ffs.") } })
            warnings += "已有 FunctionFS function；不能假设它对应本项目的 FunctionFS 挂载实例。"
        if (snapshot.gadgets.any { g -> g.configLinks.values.any { it.contains("ffs.") } })
            warnings += "现有配置链接引用 FunctionFS；更改 ep0 描述符可能影响已连接主机。"

        val summary = buildList {
            add("采集时间：${snapshot.capturedAtEpochMs}")
            add("ConfigFS：${if (snapshot.configFsMounted) "已挂载" else "未确认"}；根目录：${snapshot.gadgetRoot ?: "不可用"}")
            add("UDC：${snapshot.udcs.sorted().joinToString().ifBlank { "未发现" }}")
            add("USB data role：${snapshot.currentDataRole ?: "未知"}")
            add("现有 Gadget 数量：${snapshot.gadgets.size}")
            snapshot.gadgets.forEach { g ->
                add("${g.name}: id=${g.vendorId ?: "?"}:${g.productId ?: "?"}, UDC=${g.boundUdc ?: "未绑定/未知"}, functions=${g.functions.sorted().joinToString().ifBlank { "无/未知" }}")
                g.configLinks.forEach { (link, targetPath) -> add("  config link $link -> $targetPath") }
            }
        }
        val readiness = when {
            blockers.isNotEmpty() -> Readiness.BLOCKED
            warnings.isNotEmpty() -> Readiness.REVIEW_REQUIRED
            else -> Readiness.NO_SAFE_AUTOMATION
        }
        val next = when (readiness) {
            Readiness.BLOCKED -> listOf("只解决列出的阻塞项，并重新采集快照。", "保持当前 USB 配置不变；不要尝试写入 UDC。")
            Readiness.REVIEW_REQUIRED -> listOf("人工确认系统 USB 管理服务、现有 Gadget 和恢复通道。", "在独立测试设备/可恢复环境中验证；本应用仍不自动执行变更。")
            Readiness.NO_SAFE_AUTOMATION -> listOf("先导出并人工核对系统原始 USB 配置及恢复方式。", "下一阶段再实现带所有权标记的事务式变更；仍需独立确认后才考虑执行。")
        }
        return Assessment(readiness, blockers, warnings, summary, next)
    }
    private val NAME = Regex("^[A-Za-z0-9_-]{1,32}$")
}