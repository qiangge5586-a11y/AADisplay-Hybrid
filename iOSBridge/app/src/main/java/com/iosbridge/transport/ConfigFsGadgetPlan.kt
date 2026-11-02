package com.iosbridge.transport

/** Renders an auditable ConfigFS plan. It NEVER executes shell commands. */
object ConfigFsGadgetPlan {
    data class Plan(val title: String, val prerequisites: List<String>, val createSteps: List<String>, val rollbackSteps: List<String>) {
        fun render(): String = buildString {
            appendLine(title)
            appendLine("模式：仅生成计划；没有执行任何命令。")
            appendLine("\n前置条件：")
            prerequisites.forEachIndexed { i, value -> appendLine("${i + 1}. $value") }
            appendLine("\n人工审阅后才可考虑的创建步骤：")
            createSteps.forEachIndexed { i, value -> appendLine("${i + 1}. $value") }
            appendLine("\n回滚参考（仅删除本计划创建且确认未被占用的对象）：")
            rollbackSteps.forEachIndexed { i, value -> appendLine("${i + 1}. $value") }
            appendLine("\n重要：计划不包含 UDC 绑定/解绑，不代表 Apple/iAP2 描述符，也不会保留或自动恢复现有 ADB/MTP 配置。")
        }
    }

    fun build(gadgetName: String = "iosbridge", functionName: String = "ffs.iosbridge"): Plan {
        require(NAME.matches(gadgetName)) { "Invalid gadget name" }
        require(FUNCTION.matches(functionName)) { "Invalid function name" }
        val root = "/config/usb_gadget/$gadgetName"
        val fn = "$root/functions/$functionName"
        val cfg = "$root/configs/c.1"
        return Plan(
            title = "ConfigFS FunctionFS Gadget 计划（$gadgetName）",
            prerequisites = listOf(
                "内核存在 ConfigFS、USB Gadget、FunctionFS 和可用 UDC；由只读诊断确认。",
                "确认当前 USB 设备角色、ADB/MTP 状态及可用恢复通道；不要在唯一 ADB 通道上试验。",
                "确认 $root 不存在，或由操作者逐项核对其所有权；本计划不覆盖已有 Gadget。",
                "FunctionFS 已按目标内核要求挂载到 /dev/usb-ffs/iosbridge，ep0 描述符已由独立流程提交。",
                "使用非 Apple 的实验 VID/PID，并遵循设备厂商及 USB-IF 分配规则；示例值不得用于冒充 Apple。"
            ),
            createSteps = listOf(
                "mount -t configfs none /config  # 仅在 configfs 尚未挂载时；先检查 /proc/mounts",
                "mkdir '$root'",
                "printf '%s' '0x1d6b' > '$root/idVendor'  # Linux Foundation 示例值，仅供本地实验，勿冒用",
                "printf '%s' '0x0104' > '$root/idProduct'  # 示例产品号，需自行分配/确认",
                "mkdir '$root/strings/0x409' && printf '%s' 'iOSBridge Lab' > '$root/strings/0x409/manufacturer'",
                "printf '%s' 'FunctionFS Test' > '$root/strings/0x409/product'",
                "printf '%s' '00000001' > '$root/strings/0x409/serialnumber'",
                "mkdir '$cfg' && mkdir '$cfg/strings/0x409'",
                "printf '%s' 'FunctionFS test configuration' > '$cfg/strings/0x409/configuration'",
                "printf '%s' '250' > '$cfg/MaxPower'",
                "mkdir '$fn'  # FunctionFS instance名必须与实际挂载名一致",
                "ln -s '$fn' '$cfg/$functionName'",
                "停止在此处：人工核对描述符、UDC、当前 USB 功能与恢复方式；本计划不执行 UDC 绑定。"
            ),
            rollbackSteps = listOf(
                "test -L '$cfg/$functionName' && readlink '$cfg/$functionName'  # 确认链接目标后才删除",
                "rm '$cfg/$functionName'  # 仅删除本计划创建的链接",
                "rmdir '$fn'  # 仅在 FunctionFS 已停止且目录为空时",
                "rmdir '$cfg/strings/0x409' '$cfg'  # 仅确认无其他内容/引用后",
                "rmdir '$root/strings/0x409' '$root'  # 仅确认这是本计划创建的空 Gadget 后"
            )
        )
    }
    private val NAME = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val FUNCTION = Regex("^ffs\\.[A-Za-z0-9_-]{1,24}$")
}
