package com.iosbridge.transport

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental bridge to already-created FunctionFS bulk endpoints.
 * Does not mount FunctionFS, create ConfigFS objects, or bind/unbind a UDC.
 */
class RootFunctionFsTransport(
    private val outEndpoint: String,
    private val inEndpoint: String,
    private val onBytes: (ByteArray) -> Unit,
    private val onState: (String) -> Unit = {}
) : Closeable {
    private val closed = AtomicBoolean(false)
    @Volatile private var readerProcess: Process? = null
    @Volatile private var writerProcess: Process? = null
    @Volatile private var writer: OutputStream? = null

    init {
        require(isSafeEndpointPath(outEndpoint)) { "Invalid FunctionFS OUT endpoint path" }
        require(isSafeEndpointPath(inEndpoint)) { "Invalid FunctionFS IN endpoint path" }
        require(outEndpoint != inEndpoint) { "IN and OUT endpoints must be different" }
    }

    @Synchronized fun start() {
        check(!closed.get()) { "transport is closed" }
        check(readerProcess == null && writerProcess == null) { "transport already started" }
        onState("Starting FunctionFS endpoint processes")
        val reader = ProcessBuilder("su", "-c", "exec cat '$outEndpoint'").redirectErrorStream(false).start()
        readerProcess = reader
        val output = ProcessBuilder("su", "-c", "exec cat > '$inEndpoint'").redirectErrorStream(false).start()
        writerProcess = output
        writer = BufferedOutputStream(output.outputStream)
        Thread({ pump(reader.inputStream) }, "iOSBridge-FunctionFS-OUT").apply { isDaemon = true; start() }
        Thread({ reportExit(reader, "OUT") }, "iOSBridge-FunctionFS-OUT-watch").apply { isDaemon = true; start() }
        Thread({ reportExit(output, "IN") }, "iOSBridge-FunctionFS-IN-watch").apply { isDaemon = true; start() }
        onState("FunctionFS processes started; waiting for endpoint/host readiness")
    }

    @Synchronized fun send(bytes: ByteArray) {
        check(!closed.get()) { "transport is closed" }
        val stream = writer ?: error("transport has not been started")
        stream.write(bytes); stream.flush()
    }

    private fun pump(source: InputStream) {
        val input = BufferedInputStream(source)
        val buffer = ByteArray(8192)
        try {
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) onBytes(buffer.copyOf(count))
            }
            if (!closed.get()) onState("FunctionFS OUT endpoint reached EOF")
        } catch (t: Throwable) {
            if (!closed.get()) onState("FunctionFS OUT read failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun reportExit(process: Process, label: String) {
        try {
            val code = process.waitFor()
            if (!closed.get()) onState("FunctionFS $label process exited: $code")
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { writer?.close() } catch (_: Throwable) {}
        writer = null
        readerProcess?.destroy(); writerProcess?.destroy()
        readerProcess?.let { if (it.isAlive) it.destroyForcibly() }
        writerProcess?.let { if (it.isAlive) it.destroyForcibly() }
        readerProcess = null; writerProcess = null
        onState("FunctionFS transport closed; gadget configuration was not changed")
    }

    companion object {
        private val endpointPattern = Regex("^/dev/usb-ffs/[A-Za-z0-9_-]+/ep[0-9]+$")
        fun isSafeEndpointPath(path: String): Boolean = endpointPattern.matches(path)
    }
}
