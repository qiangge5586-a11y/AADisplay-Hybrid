package com.iosbridge.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Persists read-only USB snapshots and a human-review report; never replays or rolls back changes. */
class ConfigFsRecoveryStore(private val directory: File) {
    data class CheckResult(val success: Boolean, val message: String, val diff: ConfigFsSnapshotDiff.Result? = null, val pendingReview: Boolean = false)

    @Synchronized fun compareAndStore(current: ConfigFsSafetyManager.Snapshot): CheckResult {\n        return try {
        if (!directory.exists() && !directory.mkdirs()) return CheckResult(false, "无法创建恢复记录目录")
        if (!directory.isDirectory) return CheckResult(false, "恢复记录路径不是目录")
        val baselineFile = File(directory, "last-snapshot.bin")
        if (!baselineFile.isFile) {
            atomicWrite(baselineFile, encode(current))
            return CheckResult(true, "首次采集：已保存只读 USB 状态基线；下次启动可比较。")
        }
        val previous = decode(baselineFile.readBytes())
        val diff = ConfigFsSnapshotDiff.compare(previous, current)
        if (!diff.isIdentical) {
            val report = buildString {
                appendLine("IOSBRIDGE-RECOVERY-REVIEW/1")
                appendLine("created=${System.currentTimeMillis()}")
                appendLine("status=PENDING_HUMAN_REVIEW")
                append(diff.render())
                appendLine("\n禁止自动回滚：快照差异不能证明变更归属，也不能证明删除安全。")
            }
            atomicWrite(File(directory, "pending-review.txt"), report.toByteArray(Charsets.UTF_8))
        }
        atomicWrite(baselineFile, encode(current))
        CheckResult(true, if (diff.isIdentical) "与上次快照一致；基线已刷新。" else "检测到 USB 状态变化，已保存待人工审阅报告；未执行回滚。", diff, !diff.isIdentical)
    } catch (t: Throwable) { CheckResult(false, "恢复检查失败：${t.message ?: t.javaClass.simpleName}") }

    fun readPendingReview(): String? {
        val f = File(directory, "pending-review.txt")
        return if (f.isFile && f.length() <= MAX_BYTES) f.readText(Charsets.UTF_8) else null
    }

    private fun encode(s: ConfigFsSafetyManager.Snapshot): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeBoolean(s.configFsMounted); out.writeNullable(s.gadgetRoot)
            out.writeStrings(s.udcs); out.writeNullable(s.currentDataRole)
            out.writeNullable(s.requestedUsbConfig); out.writeNullable(s.activeUsbConfig)
            out.writeBoolean(s.legacyAndroidUsbPresent); out.writeLong(s.capturedAtEpochMs)
            require(s.gadgets.size <= MAX_ITEMS)
            out.writeInt(s.gadgets.size)
            s.gadgets.forEach { g ->
                out.writeUTF(g.name); out.writeNullable(g.vendorId); out.writeNullable(g.productId); out.writeNullable(g.boundUdc)
                out.writeStrings(g.functions)
                require(g.configLinks.size <= MAX_ITEMS); out.writeInt(g.configLinks.size)
                g.configLinks.toSortedMap().forEach { (k, v) -> out.writeUTF(k); out.writeUTF(v) }
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    private fun decode(bytes: ByteArray): ConfigFsSafetyManager.Snapshot {
        require(bytes.size <= MAX_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION) { "快照格式不支持" }
            val mounted = input.readBoolean(); val root = input.readNullable(); val udcs = input.readStrings()
            val role = input.readNullable(); val requested = input.readNullable(); val active = input.readNullable()
            val legacy = input.readBoolean(); val captured = input.readLong()
            val count = input.readInt().also { require(it in 0..MAX_ITEMS) }
            val gadgets = List(count) {
                val name = input.readUTF(); val vid = input.readNullable(); val pid = input.readNullable(); val bound = input.readNullable()
                val functions = input.readStrings(); val linksCount = input.readInt().also { require(it in 0..MAX_ITEMS) }
                val links = LinkedHashMap<String, String>(); repeat(linksCount) { links[input.readUTF()] = input.readUTF() }
                ConfigFsSafetyManager.Gadget(name, vid, pid, bound, functions, links)
            }
            require(input.available() == 0) { "快照包含多余数据" }
            return ConfigFsSafetyManager.Snapshot(mounted, root, udcs, role, requested, active, legacy, gadgets, captured)
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File(directory, ".${target.name}.tmp")
        temp.outputStream().use { it.write(bytes); it.fd.sync() }
        try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
    private fun DataOutputStream.writeNullable(v: String?) { writeBoolean(v != null); if (v != null) writeUTF(v.take(4096)) }
    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null
    private fun DataOutputStream.writeStrings(values: Set<String>) { require(values.size <= MAX_ITEMS); writeInt(values.size); values.sorted().forEach(::writeUTF) }
    private fun DataInputStream.readStrings(): Set<String> { val n = readInt().also { require(it in 0..MAX_ITEMS) }; return buildSet { repeat(n) { add(readUTF()) } } }
    companion object { private const val MAGIC = 0x4942534E; private const val VERSION = 1; private const val MAX_ITEMS = 512; private const val MAX_BYTES = 256 * 1024 }
}