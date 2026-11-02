package com.iosbridge.transport

import java.util.concurrent.TimeUnit

/** Root-backed read-only USB ConfigFS snapshot. Never mutates USB state. */
class RootConfigFsSnapshot(private val timeoutMs: Long = 8_000L) {
    data class Result(val success: Boolean, val message: String, val snapshot: ConfigFsSafetyManager.Snapshot? = null)

    init { require(timeoutMs in 1_000L..20_000L) }

    fun capture(): Result {
        val process = try {
            ProcessBuilder("su", "-c", SCRIPT).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return Result(false, "无法启动 Root 只读采集：${t.message ?: t.javaClass.simpleName}")
        }
        return try {
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(2048)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            synchronized(output) {
                                if (output.length < 32_000) output.append(buf, 0, n)
                            }
                        }
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                reader.join(500)
                return Result(false, "ConfigFS 快照采集超时；未执行写操作。")
            }
            reader.join(1_000)
            val text = synchronized(output) { output.toString() }
            if (process.exitValue() != 0) return Result(false, "只读采集命令失败：$text")
            Result(true, "ConfigFS 快照采集完成（只读）。", parse(text))
        } catch (t: Throwable) {
            process.destroyForcibly()
            Result(false, "解析快照失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun parse(output: String): ConfigFsSafetyManager.Snapshot {
        var mounted = false
        var root: String? = null
        var role: String? = null
        var requested: String? = null
        var active: String? = null
        var legacy = false
        val udcs = linkedSetOf<String>()
        output.lineSequence().forEach { line ->
            val p = line.split('\t')
            when (p.firstOrNull()) {
                "MOUNTED" -> mounted = p.getOrNull(1) == "1"
                "ROOT" -> root = p.getOrNull(1)?.takeIf { it != "-" }
                "UDC" -> p.getOrNull(1)?.takeIf { it.isNotBlank() && it != "-" }?.let(udcs::add)
                "ROLE" -> role = p.getOrNull(1)?.takeIf { it != "-" }
                "REQUESTED" -> requested = p.getOrNull(1)?.takeIf { it != "-" }
                "ACTIVE" -> active = p.getOrNull(1)?.takeIf { it != "-" }
                "LEGACY" -> legacy = p.getOrNull(1) == "1"
            }
        }
        return ConfigFsSafetyManager.Snapshot(
            mounted, root, udcs, role, requested, active, legacy,
            emptyList(), System.currentTimeMillis()
        )
    }

    companion object {
        // Keep this shell script deliberately small and read-only. No ConfigFS writes.
        private val SCRIPT = """
set +e
if grep -qw configfs /proc/mounts; then printf 'MOUNTED\t1\\n'; else printf 'MOUNTED\t0\\n'; fi
if [ -d /sys/kernel/config/usb_gadget ]; then printf 'ROOT\t/sys/kernel/config/usb_gadget\\n'; elif [ -d /config/usb_gadget ]; then printf 'ROOT\t/config/usb_gadget\\n'; else printf 'ROOT\t-\\n'; fi
for x in /sys/class/udc/*; do
  [ -e "${'$'}x" ] && { printf 'UDC\t'; basename "${'$'}x"; }
done
for x in /sys/class/usb_role/*/role /sys/class/typec/*/data_role; do
  if [ -r "${'$'}x" ]; then printf 'ROLE\t'; cat "${'$'}x"; break; fi
done
printf 'REQUESTED\t%s\\n' "$(getprop sys.usb.config 2>/dev/null)"
printf 'ACTIVE\t%s\\n' "$(getprop sys.usb.state 2>/dev/null)"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
        """.trimIndent()
    }
}
