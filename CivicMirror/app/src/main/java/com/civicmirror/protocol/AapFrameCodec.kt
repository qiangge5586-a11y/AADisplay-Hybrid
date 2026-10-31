package com.civicmirror.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * AAP wire-frame codec. This layer frames bytes only; TLS, message reassembly,
 * version negotiation and service discovery are separate protocol layers.
 */
data class AapWireFrame(
    val channel: Int,
    val flags: Int,
    val payload: ByteArray,
    val declaredMessageSize: Int? = null
) {
    val isFirst get() = flags and AapFrameCodec.FLAG_FIRST != 0
    val isLast get() = flags and AapFrameCodec.FLAG_LAST != 0
    val isControl get() = flags and AapFrameCodec.FLAG_CONTROL != 0
    val isEncrypted get() = flags and AapFrameCodec.FLAG_ENCRYPTED != 0
}

object AapFrameCodec {
    const val FLAG_FIRST = 0x01
    const val FLAG_LAST = 0x02
    const val FLAG_CONTROL = 0x04
    const val FLAG_ENCRYPTED = 0x08
    const val MAX_FRAME_PAYLOAD = 16120
    const val MAX_MESSAGE_SIZE = 8 * 1024 * 1024
    private const val KNOWN_FLAGS = FLAG_FIRST or FLAG_LAST or FLAG_CONTROL or FLAG_ENCRYPTED

    /** Split a plaintext AAP message (2-byte message id + body) into consecutive frames. */
    fun writePlainMessage(
        output: OutputStream,
        channel: Int,
        control: Boolean,
        message: ByteArray,
        maxPayload: Int = MAX_FRAME_PAYLOAD
    ) {
        require(channel in 0..255) { "channel must be in 0..255" }
        require(message.size >= 2) { "AAP message must contain a 2-byte message id" }
        require(message.size <= MAX_MESSAGE_SIZE) { "AAP message exceeds size limit" }
        require(maxPayload in 1..MAX_FRAME_PAYLOAD) { "invalid frame payload limit" }

        var offset = 0
        do {
            val length = minOf(maxPayload, message.size - offset)
            val first = offset == 0
            val last = offset + length == message.size
            var flags = if (control) FLAG_CONTROL else 0
            if (first) flags = flags or FLAG_FIRST
            if (last) flags = flags or FLAG_LAST

            output.write(channel)
            output.write(flags)
            writeU16(output, length)
            if (first && !last) writeU32(output, message.size)
            output.write(message, offset, length)
            offset += length
        } while (offset < message.size)
        output.flush()
    }

    /** Read one raw frame. Encrypted payloads are returned untouched for the TLS layer. */
    fun readFrame(input: InputStream): AapWireFrame {
        val channel = input.read()
        if (channel < 0) throw EOFException("end of AAP stream")
        val flags = requiredByte(input, "flags")
        require(flags and KNOWN_FLAGS.inv() == 0) {
            "reserved AAP frame flags are set: 0x" + flags.toString(16)
        }
        val payloadSize = (requiredByte(input, "payload length") shl 8) or
            requiredByte(input, "payload length")
        require(payloadSize <= MAX_FRAME_PAYLOAD) { "AAP frame payload exceeds limit" }

        val hasTotal = flags and FLAG_FIRST != 0 && flags and FLAG_LAST == 0
        val declaredSize = if (hasTotal) readU32(input) else null
        if (declaredSize != null) {
            require(declaredSize in 2..MAX_MESSAGE_SIZE) { "AAP declared message size out of range" }
        }
        val payload = ByteArray(payloadSize)
        var offset = 0
        while (offset < payload.size) {
            val count = input.read(payload, offset, payload.size - offset)
            if (count < 0) throw EOFException("truncated AAP frame payload")
            if (count > 0) offset += count
        }
        return AapWireFrame(channel, flags, payload, declaredSize)
    }

    private fun requiredByte(input: InputStream, field: String): Int {
        val value = input.read()
        if (value < 0) throw EOFException("truncated AAP frame $field")
        return value
    }

    private fun readU32(input: InputStream): Int {
        val a = requiredByte(input, "extended length")
        val b = requiredByte(input, "extended length")
        val c = requiredByte(input, "extended length")
        val d = requiredByte(input, "extended length")
        return (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    private fun writeU16(output: OutputStream, value: Int) {
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    private fun writeU32(output: OutputStream, value: Int) {
        output.write((value ushr 24) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }
}
