package com.iosbridge.transport

import java.util.concurrent.TimeUnit

/** Explicit opt-in initializer for an already-mounted FunctionFS instance. Never mounts or binds a UDC. */
class RootFunctionFsInitializer(private val instanceName: String, private val commandTimeoutMs: Long = 5_000L) {
    data class Result(val success: Boolean, val message: String)
    init {
        require(INSTANCE_PATTERN.matches(instanceName)) { "Invalid FunctionFS instance name" }
        require(commandTimeoutMs in 1_000L..15_000L) { "Unsafe timeout" }
    }
    fun initialize(blobs: FunctionFsDescriptorSet.Blobs): Result {
        val mountPath = "/dev/usb-ffs/$instanceName"
        val ep0 = "$mountPath/ep0"
        val preflight = runRoot("test -d '$mountPath' && test -c '$ep0' && grep -F ' $mountPath functionfs ' /proc/mounts >/dev/null")
        if (!preflight.first) return Result(false, "FunctionFS 未挂载或 ep0 不存在；未写入任何数据。"+preflight.second)
        val descriptorWrite = writeEp0(ep0, blobs.descriptors)
        if (!descriptorWrite.first) return Result(false, "写入 FunctionFS 描述符失败："+descriptorWrite.second)
        val stringWrite = writeEp0(ep0, blobs.strings)
        if (!stringWrite.first) return Result(false, "描述符已写入，但字符串写入失败："+stringWrite.second+"。请检查 FunctionFS 状态。")
        return Result(true, "描述符和字符串已提交到现有 ep0；未创建 Gadget，也未绑定 UDC。")
    }
    private fun writeEp0(path: String, bytes: ByteArray): Pair<Boolean, String> {
        val process = try { ProcessBuilder("su", "-c", "cat > '$path'").redirectErrorStream(true).start() }
        catch (t: Throwable) { return false to (t.message ?: t.javaClass.simpleName) }
        return try {
            process.outputStream.use { it.write(bytes); it.flush() }
            if (!process.waitFor(commandTimeoutMs, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); false to "ep0 写入超时" }
            else {
                val output = process.inputStream.bufferedReader().use { it.readText().take(500) }
                (process.exitValue() == 0) to output.ifBlank { "exit="+process.exitValue() }
            }
        } catch (t: Throwable) { process.destroyForcibly(); false to (t.message ?: t.javaClass.simpleName) }
    }
    private fun runRoot(command: String): Pair<Boolean, String> {
        val process = try { ProcessBuilder("su", "-c", command).redirectErrorStream(true).start() }
        catch (t: Throwable) { return false to (t.message ?: t.javaClass.simpleName) }
        return try {
            if (!process.waitFor(commandTimeoutMs, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); false to "Root 预检超时" }
            else {
                val output = process.inputStream.bufferedReader().use { it.readText().take(500) }
                (process.exitValue() == 0) to output
            }
        } catch (t: Throwable) { process.destroyForcibly(); false to (t.message ?: t.javaClass.simpleName) }
    }
    companion object { private val INSTANCE_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$") }
}
