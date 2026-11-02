package com.iosbridge.transport

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** Durable, atomic journal storage. It stores audit data only and never runs commands. */
class ConfigFsJournalStore(private val directory: File) {
    data class StoreResult(val success: Boolean, val message: String, val file: File? = null)

    fun save(journal: ConfigFsTransaction.Journal): StoreResult {
        return try {
        if (!directory.exists() && !directory.mkdirs()) return StoreResult(false, "无法创建事务日志目录")
        if (!directory.isDirectory) return StoreResult(false, "事务日志路径不是目录")
        val target = File(directory, "${journal.transactionId}.ibj")
        val temp = File(directory, ".${journal.transactionId}.tmp")
        val bytes = encode(journal).toByteArray(StandardCharsets.UTF_8)
        temp.outputStream().use { it.write(bytes); it.fd.sync() }
        try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        StoreResult(true, "事务日志已原子保存", target)
    } catch (t: Throwable) { StoreResult(false, "保存事务日志失败：${t.message ?: t.javaClass.simpleName}") }
    }

    fun load(transactionId: String): ConfigFsTransaction.Journal? {
        require(transactionId.matches(Regex("^[A-Za-z0-9_-]{1,48}$"))) { "Invalid transaction id" }
        val file = File(directory, "$transactionId.ibj")
        if (!file.isFile || file.length() > MAX_BYTES) return null
        return decode(file.readText(StandardCharsets.UTF_8))
    }

    fun listIds(): List<String> = directory.listFiles()
        ?.filter { it.isFile && it.extension == "ibj" && it.length() <= MAX_BYTES }
        ?.map { it.nameWithoutExtension }
        ?.filter { it.matches(Regex("^[A-Za-z0-9_-]{1,48}$")) }
        ?.sorted() ?: emptyList()

    private fun encode(j: ConfigFsTransaction.Journal): String = buildString {
        appendLine("IOSBRIDGE-JOURNAL/1")
        appendLine("${j.version}\t${e(j.transactionId)}\t${e(j.ownerMarker)}\t${j.createdAtEpochMs}\t${j.phase}\t${e(j.failure ?: "")}")
        j.steps.forEach { s ->
            appendLine(listOf("S", e(s.id), s.kind.name, e(s.path), e(s.value ?: ""), e(s.expectedBefore ?: ""), s.state.name, e(s.observedAfter ?: "")).joinToString("\t"))
        }
    }

    private fun decode(raw: String): ConfigFsTransaction.Journal {
        val lines = raw.lineSequence().filter { it.isNotEmpty() }.toList()
        require(lines.size >= 2 && lines[0] == "IOSBRIDGE-JOURNAL/1") { "Unknown journal format" }
        val h = lines[1].split('\t'); require(h.size == 6)
        val steps = lines.drop(2).map { line ->
            val p = line.split('\t'); require(p.size == 8 && p[0] == "S")
            ConfigFsTransaction.Step(d(p[1]), ConfigFsTransaction.StepKind.valueOf(p[2]), d(p[3]),
                d(p[4]).ifEmpty { null }, d(p[5]).ifEmpty { null }, ConfigFsTransaction.StepState.valueOf(p[6]), d(p[7]).ifEmpty { null })
        }
        val journal = ConfigFsTransaction.Journal(h[0].toInt(), d(h[1]), d(h[2]), h[3].toLong(),
            ConfigFsTransaction.Phase.valueOf(h[4]), steps, d(h[5]).ifEmpty { null })
        require(journal.version == ConfigFsTransaction.JOURNAL_VERSION)
        require(journal.steps.map { it.id }.toSet().size == journal.steps.size)
        return journal
    }

    private fun e(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun d(value: String): String = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    companion object { private const val MAX_BYTES = 256 * 1024 }
}

/** Semantic diff between two read-only ConfigFS snapshots; it never applies changes. */
object ConfigFsSnapshotDiff {
    data class Change(val kind: String, val key: String, val before: String?, val after: String?)
    data class Result(val changes: List<Change>) {
        val isIdentical: Boolean get() = changes.isEmpty()
        fun render(): String = if (isIdentical) "两个快照没有可见差异。" else buildString {
            appendLine("ConfigFS 快照差异（只读，不代表可安全回滚）：")
            changes.forEach { appendLine("${it.kind} ${it.key}: ${it.before ?: "<无>"} -> ${it.after ?: "<无>"}") }
        }
    }
    fun compare(before: ConfigFsSafetyManager.Snapshot, after: ConfigFsSafetyManager.Snapshot): Result {
        val a = flatten(before); val b = flatten(after)
        return Result((a.keys + b.keys).toSortedSet().mapNotNull { key ->
            val old = a[key]; val new = b[key]
            if (old == new) null else Change(key.substringBefore(':'), key.substringAfter(':'), old, new)
        })
    }
    private fun flatten(s: ConfigFsSafetyManager.Snapshot): Map<String, String> = buildMap {
        put("mount:configfs", s.configFsMounted.toString()); put("root:gadget", s.gadgetRoot ?: "")
        put("role:data", s.currentDataRole ?: ""); put("usb:requested", s.requestedUsbConfig ?: "")
        put("usb:active", s.activeUsbConfig ?: ""); put("legacy:android_usb", s.legacyAndroidUsbPresent.toString())
        s.udcs.sorted().forEach { put("udc:$it", "present") }
        s.gadgets.sortedBy { it.name }.forEach { g ->
            val p = "gadget:${g.name}"
            put("$p:vid", g.vendorId ?: ""); put("$p:pid", g.productId ?: ""); put("$p:bound", g.boundUdc ?: "")
            g.functions.sorted().forEach { put("$p:function:$it", "present") }
            g.configLinks.toSortedMap().forEach { (k, v) -> put("$p:link:$k", v) }
        }
    }
}