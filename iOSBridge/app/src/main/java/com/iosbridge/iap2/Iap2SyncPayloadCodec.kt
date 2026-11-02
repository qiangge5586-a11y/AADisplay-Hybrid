package com.iosbridge.iap2

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Iap2SyncPayloadCodec {
    fun encode(fields: Map<Int, ByteArray>): ByteArray {
        val size = fields.entries.sumOf { 3 + it.value.size }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        fields.toSortedMap().forEach { (id, value) ->
            require(id in 0..255)
            require(value.size <= 65535)
            buffer.put(id.toByte())
            buffer.putShort(value.size.toShort())
            buffer.put(value)
        }
        return buffer.array()
    }

    fun decode(payload: ByteArray): Map<Int, ByteArray> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val result = linkedMapOf<Int, ByteArray>()
        while (buffer.hasRemaining()) {
            require(buffer.remaining() >= 3) { "Truncated sync field header" }
            val id = buffer.get().toInt() and 0xff
            val length = buffer.short.toInt() and 0xffff
            require(length <= buffer.remaining()) { "Truncated sync field value" }
            val value = ByteArray(length)
            buffer.get(value)
            require(result.put(id, value) == null) { "Duplicate sync field: $id" }
        }
        return result
    }
}
