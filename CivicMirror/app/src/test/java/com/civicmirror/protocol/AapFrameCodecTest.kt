package com.civicmirror.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class AapFrameCodecTest {
    @Test fun singleFrameRoundTrips() {
        val message = byteArrayOf(0, 1, 0x11, 0x22)
        val output = ByteArrayOutputStream()
        AapFrameCodec.writePlainMessage(output, 0, true, message)
        val frame = AapFrameCodec.readFrame(ByteArrayInputStream(output.toByteArray()))
        assertEquals(0, frame.channel)
        assertTrue(frame.isFirst)
        assertTrue(frame.isLast)
        assertTrue(frame.isControl)
        assertFalse(frame.isEncrypted)
        assertNull(frame.declaredMessageSize)
        assertArrayEquals(message, frame.payload)
    }

    @Test fun fragmentedMessageCarriesTotalLengthOnFirstFrame() {
        val message = ByteArray(23) { it.toByte() }
        val output = ByteArrayOutputStream()
        AapFrameCodec.writePlainMessage(output, 3, false, message, 7)
        val input = ByteArrayInputStream(output.toByteArray())
        val frames = mutableListOf<AapWireFrame>()
        while (input.available() > 0) frames += AapFrameCodec.readFrame(input)
        assertEquals(4, frames.size)
        assertEquals(message.size, frames.first().declaredMessageSize)
        assertTrue(frames.first().isFirst)
        assertFalse(frames.first().isLast)
        assertTrue(frames.last().isLast)
        assertArrayEquals(message.copyOfRange(0, 7), frames[0].payload)
        assertArrayEquals(message.copyOfRange(21, 23), frames[3].payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReservedFlags() {
        AapFrameCodec.readFrame(ByteArrayInputStream(byteArrayOf(0, 0x10, 0, 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedDeclaredMessage() {
        val bytes = byteArrayOf(0, 1, 0, 1, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0)
        AapFrameCodec.readFrame(ByteArrayInputStream(bytes))
    }
}
