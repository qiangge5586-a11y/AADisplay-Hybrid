package com.iosbridge.transport

import java.util.concurrent.TimeUnit

/** Root-backed read-only ConfigFS snapshot. No writes, mounts, links or UDC operations. */
class RootConfigFsSnapshot(private val timeoutMs: Long = 8_000L) {
    data class Result(val success: Boolean, val message: String, val snapshot: ConfigFsSafetyManager.Snapshot? = null)
    init { require(timeoutMs in 1_000L..20_000L) }

    fun capture(): Result {
        val process = try { ProcessBuilder("su", "-c", SCRIPT).redirectErrorStream(true).start() }
        catch (t: Throwable) { return Result(false, "无法启动 Root 只读采集：${t.message ?: t.javaClass.simpleName}") }
        return try {
            // Drain output concurrently to avoid pipe-buffer deadlock.
            val output = StringBuilder()
            val reader = Thread {
                try { process.inputStream.bufferedReader().use { r -> val buf = CharArray(2048); var n: Int; while (r.read(buf).also { n = it } >= 0) synchronized(output) { if (output.length < 32_000) output.append(buf, 0, n) } } }
                catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly(); reader.join(500)
                return Result(false, "ConfigFS 快照采集超时；未执行写操作。")
            }
            reader.join(1000)
            val text = synchronized(output) { output.toString() }
            if (process.exitValue() != 0) return Result(false, "只读采集命令失败：$text")
            Result(true, "ConfigFS 快照采集完成（只读）。", parse(text))
        } catch (t: Throwable) {
            process.destroyForcibly()
            Result(false, "解析快照失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun parse(output: String): ConfigFsSafetyManager.Snapshot {
        var mounted = false; var root: String? = null; var role: String? = null
        var requested: String? = null; var active: String? = null; var legacy = false
        val udcs = linkedSetOf<String>()
        val gadgets = linkedMapOf<String, MutableGadget>()
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
                "GADGET" -> p.getOrNull(1)?.let { gadgets.getOrPut(it) { MutableGadget(it) } }
                "VID" -> if (p.size >= 3) gadgets.getOrPut(p[2]) { MutableGadget(p[2]) }.vid = p[1]
                "PID" -> if (p.size >= 3) gadgets.getOrPut(p[2]) { MutableGadget(p[2]) }.pid = p[1]
                "BOUND" -> if (p.size >= 3) gadgets.getOrPut(p[2]) { MutableGadget(p[2]) }.udc = p[1].takeIf(String::isNotBlank)
                "FUNCTION" -> if (p.size >= 3) gadgets.getOrPut(p[2]) { MutableGadget(p[2]) }.functions += p[1]
                "LINK" -> if (p.size >= 4) gadgets.getOrPut(p[3]) { MutableGadget(p[3]) }.links[p[1]] = p[2]
            }
        }
        return ConfigFsSafetyManager.Snapshot(mounted, root, udcs, role, requested, active, legacy,
            gadgets.values.map { ConfigFsSafetyManager.Gadget(it.name, it.vid, it.pid, it.udc, it.functions, it.links) },
            System.currentTimeMillis())
    }

    private class MutableGadget(val name: String) {
        var vid: String? = null; var pid: String? = null; var udc: String? = null
        val functions = linkedSetOf<String>()
        val links = linkedMapOf<String, String>()
    }

    companion object {
        /** Kernel-generated names/paths are emitted as tab-separated fields. */
        private val SCRIPT = """
set +e
if grep -qw configfs /proc/mounts; then printf 'MOUNTED\t1\\n'; else printf 'MOUNTED\t0\\n'; fi
R=''; for x in /sys/kernel/config/usb_gadget /config/usb_gadget; do if [ -d "${'x" ]; then R="$x"; break; fi; done
printf 'ROOT\t%s\\n' "${'$'}{R:--}"
for x in /sys/class/udc/*; do [ -e "$x" ] && printf 'UDC\t%s\\n' "$(basename "$x")"; done
ROLE=''; for x in /sys/class/usb_role/*/role /sys/class/typec/*/data_role; do if [ -r "$x" ]; then ROLE="$(cat "$x" 2>/dev/null)"; [ -n "$ROLE" ] && break; fi; done
printf 'ROLE\t%s\\n' "${'$'}{ROLE:--}"
printf 'REQUESTED\t%s\\n' "$(getprop sys.usb.config | tr '\\t\\n' '  ')"
printf 'ACTIVE\t%s\\n' "$(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "$R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }x" ]; then R="${'x"; break; fi; done
printf 'ROOT\t%s\\n' "${'$'}{R:--}"
for x in /sys/class/udc/*; do [ -e "$x" ] && printf 'UDC\t%s\\n' "$(basename "$x")"; done
ROLE=''; for x in /sys/class/usb_role/*/role /sys/class/typec/*/data_role; do if [ -r "$x" ]; then ROLE="$(cat "$x" 2>/dev/null)"; [ -n "$ROLE" ] && break; fi; done
printf 'ROLE\t%s\\n' "${'$'}{ROLE:--}"
printf 'REQUESTED\t%s\\n' "$(getprop sys.usb.config | tr '\\t\\n' '  ')"
printf 'ACTIVE\t%s\\n' "$(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "$R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }x"; break; fi; done
printf 'ROOT\t%s\\n' "${'${''}{R:--}"
for x in /sys/class/udc/*; do [ -e "$x" ] && printf 'UDC\t%s\\n' "$(basename "$x")"; done
ROLE=''; for x in /sys/class/usb_role/*/role /sys/class/typec/*/data_role; do if [ -r "$x" ]; then ROLE="$(cat "$x" 2>/dev/null)"; [ -n "$ROLE" ] && break; fi; done
printf 'ROLE\t%s\\n' "${'$'}{ROLE:--}"
printf 'REQUESTED\t%s\\n' "$(getprop sys.usb.config | tr '\\t\\n' '  ')"
printf 'ACTIVE\t%s\\n' "$(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "$R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }'}{R:--}"
for x in /sys/class/udc/*; do [ -e "${'x" ] && printf 'UDC\t%s\\n' "$(basename "$x")"; done
ROLE=''; for x in /sys/class/usb_role/*/role /sys/class/typec/*/data_role; do if [ -r "$x" ]; then ROLE="$(cat "$x" 2>/dev/null)"; [ -n "$ROLE" ] && break; fi; done
printf 'ROLE\t%s\\n' "${'$'}{ROLE:--}"
printf 'REQUESTED\t%s\\n' "$(getprop sys.usb.config | tr '\\t\\n' '  ')"
printf 'ACTIVE\t%s\\n' "$(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "$R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }x" ] && printf 'UDC\t%s\\n' "${'(basename "$x")"; done
ROLE=''; for x in /sys/class/usb_role/*/role /sys/class/typec/*/data_role; do if [ -r "$x" ]; then ROLE="$(cat "$x" 2>/dev/null)"; [ -n "$ROLE" ] && break; fi; done
printf 'ROLE\t%s\\n' "${'$'}{ROLE:--}"
printf 'REQUESTED\t%s\\n' "$(getprop sys.usb.config | tr '\\t\\n' '  ')"
printf 'ACTIVE\t%s\\n' "$(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "$R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }(basename "${'x")"; done
ROLE=''; for x in /sys/class/usb_role/*/role /sys/class/typec/*/data_role; do if [ -r "$x" ]; then ROLE="$(cat "$x" 2>/dev/null)"; [ -n "$ROLE" ] && break; fi; done
printf 'ROLE\t%s\\n' "${'$'}{ROLE:--}"
printf 'REQUESTED\t%s\\n' "$(getprop sys.usb.config | tr '\\t\\n' '  ')"
printf 'ACTIVE\t%s\\n' "$(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "$R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }x")"; done
ROLE=''; for x in /sys/class/usb_role/*/role /sys/class/typec/*/data_role; do if [ -r "${'x" ]; then ROLE="$(cat "$x" 2>/dev/null)"; [ -n "$ROLE" ] && break; fi; done
printf 'ROLE\t%s\\n' "${'$'}{ROLE:--}"
printf 'REQUESTED\t%s\\n' "$(getprop sys.usb.config | tr '\\t\\n' '  ')"
printf 'ACTIVE\t%s\\n' "$(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "$R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }x" ]; then ROLE="${'(cat "$x" 2>/dev/null)"; [ -n "$ROLE" ] && break; fi; done
printf 'ROLE\t%s\\n' "${'$'}{ROLE:--}"
printf 'REQUESTED\t%s\\n' "$(getprop sys.usb.config | tr '\\t\\n' '  ')"
printf 'ACTIVE\t%s\\n' "$(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "$R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }(cat "${'x" 2>/dev/null)"; [ -n "$ROLE" ] && break; fi; done
printf 'ROLE\t%s\\n' "${'$'}{ROLE:--}"
printf 'REQUESTED\t%s\\n' "$(getprop sys.usb.config | tr '\\t\\n' '  ')"
printf 'ACTIVE\t%s\\n' "$(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "$R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }x" 2>/dev/null)"; [ -n "${'ROLE" ] && break; fi; done
printf 'ROLE\t%s\\n' "${'$'}{ROLE:--}"
printf 'REQUESTED\t%s\\n' "$(getprop sys.usb.config | tr '\\t\\n' '  ')"
printf 'ACTIVE\t%s\\n' "$(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "$R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }ROLE" ] && break; fi; done
printf 'ROLE\t%s\\n' "${'${''}{ROLE:--}"
printf 'REQUESTED\t%s\\n' "$(getprop sys.usb.config | tr '\\t\\n' '  ')"
printf 'ACTIVE\t%s\\n' "$(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "$R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }'}{ROLE:--}"
printf 'REQUESTED\t%s\\n' "${'(getprop sys.usb.config | tr '\\t\\n' '  ')"
printf 'ACTIVE\t%s\\n' "$(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "$R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }(getprop sys.usb.config | tr '\\t\\n' '  ')"
printf 'ACTIVE\t%s\\n' "${'(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "$R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }(getprop sys.usb.state | tr '\\t\\n' '  ')"
if [ -e /sys/class/android_usb/android0 ] || [ -e /sys/class/android_usb ]; then printf 'LEGACY\t1\\n'; else printf 'LEGACY\t0\\n'; fi
if [ -n "${'R" ]; then
 for g in "$R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }R" ]; then
 for g in "${'R"/*; do
  [ -d "$g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }R"/*; do
  [ -d "${'g" ] || continue
  N="$(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }g" ] || continue
  N="${'(basename "$g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }(basename "${'g")"; printf 'GADGET\t%s\\n' "$N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }g")"; printf 'GADGET\t%s\\n' "${'N"
  [ -r "$g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }N"
  [ -r "${'g/idVendor" ] && printf 'VID\t%s\t%s\\n' "$(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }g/idVendor" ] && printf 'VID\t%s\t%s\\n' "${'(cat "$g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }(cat "${'g/idVendor")" "$N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }g/idVendor")" "${'N"
  [ -r "$g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }N"
  [ -r "${'g/idProduct" ] && printf 'PID\t%s\t%s\\n' "$(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }g/idProduct" ] && printf 'PID\t%s\t%s\\n' "${'(cat "$g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }(cat "${'g/idProduct")" "$N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }g/idProduct")" "${'N"
  [ -r "$g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }N"
  [ -r "${'g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "$(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }g/UDC" ] && printf 'BOUND\t%s\t%s\\n' "${'(cat "$g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }(cat "${'g/UDC")" "$N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }g/UDC")" "${'N"
  for f in "$g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }N"
  for f in "${'g"/functions/*; do [ -e "$f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }g"/functions/*; do [ -e "${'f" ] && printf 'FUNCTION\t%s\t%s\\n' "$(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }f" ] && printf 'FUNCTION\t%s\t%s\\n' "${'(basename "$f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }(basename "${'f")" "$N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }f")" "${'N"; done
  for l in "$g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }N"; done
  for l in "${'g"/configs/*/*; do [ -L "$l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }g"/configs/*/*; do [ -L "${'l" ] && printf 'LINK\t%s\t%s\t%s\\n' "$(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }l" ] && printf 'LINK\t%s\t%s\t%s\\n' "${'(basename "$l")" "$(readlink "$l")" "$N"; done
 done
fi
        }(basename "${'l")" "$(readlink "$l")" "$N"; done
 done
fi
        }l")" "${'(readlink "$l")" "$N"; done
 done
fi
        }(readlink "${'l")" "$N"; done
 done
fi
        }l")" "${'N"; done
 done
fi
        }N"; done
 done
fi
        """.trimIndent()
    }
}