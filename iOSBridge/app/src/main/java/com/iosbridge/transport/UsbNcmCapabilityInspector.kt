package com.iosbridge.transport

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Read-only inspection of device-side USB networking prerequisites. */
class UsbNcmCapabilityInspector(private val commandRunner: (String) -> String = ::runRoot) {
    data class Evidence(
        val gadgetFunctions: String,
        val loadedModules: String,
        val kernelConfig: String,
        val netInterfaces: String,
        val udc: String,
        val configfsMounted: Boolean
    )

    enum class GadgetNcmSupport { PRESENT, KERNEL_ENABLED, NOT_CONFIRMED }

    data class Assessment(val support: GadgetNcmSupport, val evidence: Evidence) {
        fun render(): String = buildString {
            appendLine("iOSBridge · USB 网络功能能力检查（只读）")
            appendLine("设备端 NCM 结论：" + when (support) {
                GadgetNcmSupport.PRESENT -> "发现现有 ncm.* Gadget function。"
                GadgetNcmSupport.KERNEL_ENABLED -> "内核/模块存在 NCM 支持线索，但尚未创建或验证 Gadget function。"
                GadgetNcmSupport.NOT_CONFIRMED -> "未找到设备端 NCM 支持证据。"
            })
            appendLine("UDC：${evidence.udc.ifBlank { "未发现" }}")
            appendLine("ConfigFS：${if (evidence.configfsMounted) "已挂载" else "未确认挂载"}")
            appendLine("现有 Gadget functions：${evidence.gadgetFunctions.ifBlank { "无" }}")
            appendLine("已加载模块：${evidence.loadedModules.ifBlank { "无相关模块" }}")
            appendLine("内核配置：${evidence.kernelConfig.ifBlank { "未读取到相关配置" }}")
            appendLine("网络接口：${evidence.netInterfaces.ifBlank { "未读取到" }}")
            appendLine("\n判定边界：usbnet/rndis 等网络接口或主机侧驱动不能证明手机具备设备端 NCM Gadget function；即使内核支持 NCM，也不代表车机接受该接口组合。")
            appendLine("安全边界：本检查只读取 /sys、/proc 和 getprop；不加载模块、不创建接口、不挂载、不切换 USB role、不写 ConfigFS/UDC。")
        }
    }

    fun inspect(): Assessment {
        val raw = commandRunner(SCRIPT)
        val fields = raw.lineSequence().filter { it.contains('=') }.associate { line ->
            val index = line.indexOf('=')
            line.substring(0, index) to line.substring(index + 1)
        }
        val evidence = Evidence(
            gadgetFunctions = fields["GADGET_FUNCTIONS"].orEmpty(),
            loadedModules = fields["LOADED_MODULES"].orEmpty(),
            kernelConfig = fields["KERNEL_CONFIG"].orEmpty(),
            netInterfaces = fields["NET_INTERFACES"].orEmpty(),
            udc = fields["UDC"].orEmpty(),
            configfsMounted = fields["CONFIGFS_MOUNTED"] == "yes"
        )
        val support = when {
            Regex("(^|\\s)ncm\\.[A-Za-z0-9_.-]+(\\s|$)").containsMatchIn(evidence.gadgetFunctions) -> GadgetNcmSupport.PRESENT
            evidence.loadedModules.contains("usb_f_ncm") || evidence.kernelConfig.lineSequence().any {
                it.trim() in setOf("CONFIG_USB_F_NCM=y", "CONFIG_USB_F_NCM=m", "CONFIG_USB_CONFIGFS_NCM=y", "CONFIG_USB_CONFIGFS_NCM=m")
            } -> GadgetNcmSupport.KERNEL_ENABLED
            else -> GadgetNcmSupport.NOT_CONFIRMED
        }
        return Assessment(support, evidence)
    }

    companion object {
        private const val SCRIPT = """
printf 'GADGET_FUNCTIONS='
for p in /sys/kernel/config/usb_gadget/*/functions/* /config/usb_gadget/*/functions/*; do [ -e "${'$'}p" ] && printf '%s ' "${'$'}(basename "${'$'}p")"; done
printf '\nLOADED_MODULES='
for m in usb_f_ncm usb_f_rndis usbnet rndis_host cdc_ncm; do [ -d /sys/module/${'$'}m ] && printf '%s ' "${'$'}m"; done
printf '\nKERNEL_CONFIG='
(zcat /proc/config.gz 2>/dev/null || cat /vendor/etc/kernel.config /proc/config 2>/dev/null) | grep -E '^(CONFIG_USB_F_NCM|CONFIG_USB_CONFIGFS_NCM|CONFIG_USB_CONFIGFS|CONFIG_USB_GADGET)=' | tr '\n' ';'
printf '\nNET_INTERFACES='
for p in /sys/class/net/*; do [ -e "${'$'}p" ] || continue; n=${'$'}(basename "${'$'}p"); d=${'$'}(readlink -f "${'$'}p/device/driver" 2>/dev/null); printf '%s:%s ' "${'$'}n" "${'$'}{d##*/}"; done
printf '\nUDC='
for p in /sys/class/udc/*; do [ -e "${'$'}p" ] && printf '%s ' "${'$'}(basename "${'$'}p")"; done
printf '\nCONFIGFS_MOUNTED='
grep -qw configfs /proc/mounts && printf yes || printf no
"""

        private fun runRoot(command: String): String = try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = ByteArrayOutputStream()
            val reader = Thread { try { process.inputStream.use { it.copyTo(output) } } catch (_: Exception) {} }
                .apply { isDaemon = true; start() }
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "PROBE_ERROR=timeout"
            } else {
                reader.join(500)
                output.toString(Charsets.UTF_8.name()).take(16_384)
            }
        } catch (t: Throwable) { "PROBE_ERROR=${t.javaClass.simpleName}" }
    }
}