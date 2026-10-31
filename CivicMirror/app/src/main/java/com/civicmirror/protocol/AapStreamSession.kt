package com.civicmirror.protocol

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns one bidirectional AAP byte stream.
 *
 * This is deliberately a transport/framing layer: it does not perform TLS,
 * protobuf dispatch, version negotiation, or Android Auto service discovery.
 * The caller must supply streams from an already-authorized transport.
 */
class AapStreamSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val onMessage: (channel: Int, control: Boolean, message: ByteArray) -> Unit,
    private val onClosed: (cause: Throwable?) -> Unit = {}
) : Closeable {
    enum class State { NEW, RUNNING, CLOSED }

    private val state = AtomicReference(State.NEW)
    private val closeNotified = AtomicBoolean(false)
    private val writeLock = Any()
    private val assembler = AapMessageAssembler()
    @Volatile private var readerThread: Thread? = null

    fun state(): State = state.get()

    /** Starts exactly one reader loop. Calling start more than once is an error. */
    @Synchronized
    fun start() {
        check(state.compareAndSet(State.NEW, State.RUNNING)) {
            "AAP stream session can only be started once"
        }
        readerThread = Thread({ readLoop() }, "CivicMirror-AAP-Reader").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Writes one complete plaintext message. TLS-encrypted application messages
     * must be produced by the TLS layer before being framed; this method is not
     * a substitute for TLS record processing.
     */
    fun send(channel: Int, control: Boolean, message: ByteArray) {
        check(state.get() == State.RUNNING) { "AAP stream session is not running" }
        synchronized(writeLock) {
            check(state.get() == State.RUNNING) { "AAP stream session is closed" }
            AapFrameCodec.writePlainMessage(output, channel, control, message)
        }
    }

    override fun close() {
        finish(null)
    }

    private fun readLoop() {
        var failure: Throwable? = null
        try {
            while (state.get() == State.RUNNING) {
                val frame = AapFrameCodec.readFrame(input)
                val message = assembler.accept(frame) ?: continue
                onMessage(frame.channel, frame.isControl, message)
            }
        } catch (error: Throwable) {
            if (state.get() == State.RUNNING) failure = error
        } finally {
            finish(failure)
        }
    }

    private fun finish(cause: Throwable?) {
        state.getAndSet(State.CLOSED)
        runCatching { input.close() }
        runCatching { output.close() }
        assembler.reset()
        if (closeNotified.compareAndSet(false, true)) onClosed(cause)
    }
}
