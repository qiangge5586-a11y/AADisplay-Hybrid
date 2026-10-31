package com.civicmirror.protocol

/**
 * Reassembles AAP messages independently per channel.
 * Frame type is encoded in flags bits 0..1: MIDDLE=0, FIRST=1, LAST=2, BULK=3.
 * FIRST carries total message size in its extended header.
 *
 * Call in wire order from one reader thread. TLS decryption and protobuf parsing
 * are deliberately handled by other layers.
 */
class AapMessageAssembler(
    private val maxMessageSize: Int = AapFrameCodec.MAX_MESSAGE_SIZE
) {
    private data class Pending(val expectedSize: Int, val output: java.io.ByteArrayOutputStream)
    private val pendingByChannel = mutableMapOf<Int, Pending>()

    init {
        require(maxMessageSize in 2..AapFrameCodec.MAX_MESSAGE_SIZE) {
            "maxMessageSize must be between 2 and ${AapFrameCodec.MAX_MESSAGE_SIZE}"
        }
    }

    /** Returns a complete message including its 2-byte message ID, or null while incomplete. */
    fun accept(frame: AapWireFrame): ByteArray? {
        require(frame.channel in 0..255) { "invalid channel" }
        val frameType = frame.flags and FRAME_TYPE_MASK
        val pending = pendingByChannel[frame.channel]
        return when (frameType) {
            FRAME_BULK -> {
                require(pending == null) { "BULK while channel ${frame.channel} has a fragmented message" }
                require(frame.declaredMessageSize == null) { "BULK must not declare extended size" }
                require(frame.payload.size in 2..maxMessageSize) { "BULK message size out of range" }
                frame.payload.copyOf()
            }
            FRAME_FIRST -> {
                require(pending == null) { "duplicate FIRST on channel ${frame.channel}" }
                require(pendingByChannel.size < MAX_CONCURRENT_MESSAGES) { "too many concurrent messages" }
                val expected = frame.declaredMessageSize
                    ?: throw IllegalArgumentException("FIRST frame is missing total message size")
                require(expected in 2..maxMessageSize) { "declared message size out of range" }
                require(frame.payload.size in 1 until expected) { "invalid FIRST fragment size" }
                val output = java.io.ByteArrayOutputStream(expected)
                output.write(frame.payload)
                pendingByChannel[frame.channel] = Pending(expected, output)
                null
            }
            FRAME_MIDDLE -> {
                val current = pending ?: throw IllegalArgumentException("MIDDLE without FIRST on channel ${frame.channel}")
                appendChecked(frame.channel, current, frame.payload, false)
                null
            }
            FRAME_LAST -> {
                val current = pending ?: throw IllegalArgumentException("LAST without FIRST on channel ${frame.channel}")
                appendChecked(frame.channel, current, frame.payload, true)
                pendingByChannel.remove(frame.channel)
                current.output.toByteArray()
            }
            else -> throw IllegalArgumentException("unknown AAP frame type: $frameType")
        }
    }

    fun reset() = pendingByChannel.clear()

    private fun appendChecked(channel: Int, pending: Pending, payload: ByteArray, isLast: Boolean) {
        val newSize = pending.output.size() + payload.size
        require(newSize <= pending.expectedSize) { "message on channel $channel exceeds declared size" }
        if (isLast) {
            require(newSize == pending.expectedSize) {
                "final fragment on channel $channel ended at $newSize; expected ${pending.expectedSize}"
            }
        } else {
            require(newSize < pending.expectedSize) { "MIDDLE completed message without LAST on channel $channel" }
        }
        pending.output.write(payload)
    }

    private companion object {
        const val FRAME_TYPE_MASK = 0x03
        const val FRAME_MIDDLE = 0x00
        const val FRAME_FIRST = 0x01
        const val FRAME_LAST = 0x02
        const val FRAME_BULK = 0x03
        const val MAX_CONCURRENT_MESSAGES = 16
    }
}
