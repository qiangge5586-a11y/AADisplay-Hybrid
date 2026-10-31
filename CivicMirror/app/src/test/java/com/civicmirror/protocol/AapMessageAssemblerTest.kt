package com.civicmirror.protocol

import org.junit.Assert.*
import org.junit.Test

class AapMessageAssemblerTest {
    @Test fun reassemblesBulkMessage() {
        val message = byteArrayOf(0x80.toByte(), 0x01, 0x11, 0x22)
        assertArrayEquals(message, AapMessageAssembler().accept(frame(2, 0x03, message)))
    }

    @Test fun reassemblesFragmentsInOrder() {
        val message = ByteArray(23) { it.toByte() }
        val frames = encodedFrames(message, 7)
        val assembler = AapMessageAssembler()
        assertNull(assembler.accept(frames[0]))
        assertNull(assembler.accept(frames[1]))
        assertNull(assembler.accept(frames[2]))
        assertArrayEquals(message, assembler.accept(frames[3]))
    }

    @Test fun allowsDifferentChannelsToInterleave() {
        val a = ByteArray(12) { it.toByte() }
        val b = ByteArray(11) { (it + 20).toByte() }
        val af = encodedFrames(a, 5, 1)
        val bf = encodedFrames(b, 5, 3)
        val assembler = AapMessageAssembler()
        assertNull(assembler.accept(af[0]))
        assertNull(assembler.accept(bf[0]))
        assertNull(assembler.accept(af[1]))
        assertNull(assembler.accept(bf[1]))
        assertArrayEquals(a, assembler.accept(af[2]))
        assertArrayEquals(b, assembler.accept(bf[2]))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsContinuationWithoutFirst() {
        AapMessageAssembler().accept(frame(1, 0, byteArrayOf(1, 2)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFinalFragmentWithWrongTotalSize() {
        val assembler = AapMessageAssembler()
        assembler.accept(AapWireFrame(1, 1, byteArrayOf(1, 2), 5))
        assembler.accept(frame(1, 2, byteArrayOf(3, 4)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateFirstOnSameChannel() {
        val assembler = AapMessageAssembler()
        assembler.accept(AapWireFrame(1, 1, byteArrayOf(1, 2), 5))
        assembler.accept(AapWireFrame(1, 1, byteArrayOf(3, 4), 5))
    }

    private fun encodedFrames(message: ByteArray, maxPayload: Int, channel: Int = 0): List<AapWireFrame> {
        val bytes = java.io.ByteArrayOutputStream()
        AapFrameCodec.writePlainMessage(bytes, channel, false, message, maxPayload)
        val input = java.io.ByteArrayInputStream(bytes.toByteArray())
        val frames = mutableListOf<AapWireFrame>()
        while (input.available() > 0) frames += AapFrameCodec.readFrame(input)
        return frames
    }

    private fun frame(channel: Int, flags: Int, payload: ByteArray) = AapWireFrame(channel, flags, payload)
}
