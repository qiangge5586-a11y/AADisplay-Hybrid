package com.iosbridge.transport

import java.util.concurrent.TimeUnit

/** Root-backed read-only USB ConfigFS snapshot. Never mutates USB state. */
class RootConfigFsSnapshot(private val timeoutMs: Long = 8_000L) {
    data class Result(val success: Boolean, val message: String, val snapshot: ConfigFsSafetyManager.Snapshot? = null, val rawOutput: String = "")

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
            if (process.exitValue() != 0) return Result(false, "只读采集命令失败：$text", rawOutput = text)
            Result(true, "ConfigFS 快照采集完成（只读）。", parse(text), text)
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
        data class GadgetBuilder(
            val name: String,
            var vid: String? = null,
            var pid: String? = null,
            var bound: String? = null,
            val functions: MutableSet<String> = linkedSetOf(),
            val links: MutableMap<String, String> = linkedMapOf()
        )
        val builders = linkedMapOf<String, GadgetBuilder>()
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
                "GADGET" -> p.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { builders.putIfAbsent(it, GadgetBuilder(it)) }
                "VID" -> builders[p.getOrNull(1)]?.vid = p.getOrNull(2)?.takeIf { it != "-" }
                "PID" -> builders[p.getOrNull(1)]?.pid = p.getOrNull(2)?.takeIf { it != "-" }
                "BOUND" -> builders[p.getOrNull(1)]?.bound = p.getOrNull(2)?.takeIf { it.isNotBlank() && it != "-" }
                "FUNCTION" -> if (p.size >= 3) builders[p[1]]?.functions?.add(p[2])
                "LINK" -> if (p.size >= 4) builders[p[1]]?.links?.set(p[2], p[3])
            }
        }
        val gadgets = builders.values.map {
            ConfigFsSafetyManager.Gadget(it.name, it.vid, it.pid, it.bound, it.functions, it.links)
        }
        return ConfigFsSafetyManager.Snapshot(
            mounted, root, udcs, role, requested, active, legacy, gadgets, System.currentTimeMillis()
        )
    }

    companion object {
        // Read-only only: no mkdir, mount, property writes, UDC binding, or role changes.
        private val SCRIPT = """
set +e
ROOT=""
if grep -qw configfs /proc/mounts; then printf 'MOUNTED\t1\n'; else printf 'MOUNTED\t0\n'; fi
if [ -d /sys/kernel/config/usb_gadget ]; then ROOT=/sys/kernel/config/usb_gadget
elif [ -d /config/usb_gadget ]; then ROOT=/config/usb_gadget
fi
if [ -n "${'$'}ROOT" ]; then printf 'ROOT\t%s\n' "${'$'}ROOT"; else printf 'ROOT\t-\n'; fi
for x in /sys/class/udc/*; do
  [ -e "${'$'}x" ] && printf 'UDC\t%s\n' "${'$'}(basename "${'$'}x")"
done
for x in /sys/class/usb_role/*/role /sys/class/typec/*/data_role; do
  if [ -r "${'$'}x" ]; then printf 'ROLE\t%s\n' "${'$'}(cat "${'$'}x" 2>/dev/null | tr -d '\r\n')"; break; fi
done
printf 'REQUESTED\t%s\n' "${'$'}(getprop sys.usb.config 2>/dev/null)"
printf 'ACTIVE\t%s\n' "${'$'}(getprop sys.usb.state 2>/dev/null)"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\n'; else printf 'LEGACY\t0\n'; fi
if [ -n "${'$'}ROOT" ]; then
  for g in "${'$'}ROOT"/*; do
    [ -d "${'$'}g" ] || continue
    n="${'$'}(basename "${'$'}g")"
    printf 'GADGET\t%s\n' "${'$'}n"
    if [ -r "${'$'}g/idVendor" ]; then printf 'VID\t%s\t%s\n' "${'$'}n" "${'$'}(cat "${'$'}g/idVendor" 2>/dev/null | tr -d '\r\n')"; fi
    if [ -r "${'$'}g/idProduct" ]; then printf 'PID\t%s\t%s\n' "${'$'}n" "${'$'}(cat "${'$'}g/idProduct" 2>/dev/null | tr -d '\r\n')"; fi
    if [ -r "${'$'}g/UDC" ]; then printf 'BOUND\t%s\t%s\n' "${'$'}n" "${'$'}(cat "${'$'}g/UDC" 2>/dev/null | tr -d '\r\n')"; fi
    for f in "${'$'}g"/functions/*; do
      [ -e "${'$'}f" ] || continue
      printf 'FUNCTION\t%s\t%s\n' "${'$'}n" "${'$'}(basename "${'$'}f")"
    done
    for l in "${'$'}g"/configs/*/*; do
      [ -L "${'$'}l" ] || continue
      printf 'LINK\t%s\t%s\t%s\n' "${'$'}n" "${'$'}(basename "${'$'}l")" "${'$'}(readlink "${'$'}l" 2>/dev/null)"
    done
  done
fi
        """.trimIndent()
    }
}
