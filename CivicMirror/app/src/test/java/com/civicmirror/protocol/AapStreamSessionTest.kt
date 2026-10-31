package com.civicmirror.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AapStreamSessionTest {
    @Test
    fun readsAndReassemblesIncomingMessageThenClosesOnEof() {
        val inboundWriter = PipedOutputStream()
        val inbound = PipedInputStream(inboundWriter)
        val outbound = ByteArrayOutputStream()
        val received = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val messageRef = AtomicReference<ByteArray>()
        val causeRef = AtomicReference<Throwable?>()

        val session = AapStreamSession(
            inbound,
            outbound,
            onMessage = { channel, control, message ->
                assertEquals(3, channel)
                assertTrue(!control)
                messageRef.set(message)
                received.countDown()
            },
            onClosed = {
                causeRef.set(it)
                closed.countDown()
            }
        )

        session.start()
        val wire = ByteArrayOutputStream().apply {
            AapFrameCodec.writePlainMessage(this, 3, false, byteArrayOf(0x12, 0x34, 0x56))
        }.toByteArray()
        inboundWriter.write(wire)
        inboundWriter.flush()

        assertTrue(received.await(2, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56), messageRef.get())

        inboundWriter.close()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertEquals(AapStreamSession.State.CLOSED, session.state())
        assertTrue(causeRef.get() == null || causeRef.get() is java.io.EOFException)
    }

    @Test
    fun serializesOutgoingMessageAsAapFrame() {
        val inboundWriter = PipedOutputStream()
        val inbound = PipedInputStream(inboundWriter)
        val outbound = ByteArrayOutputStream()
        val session = AapStreamSession(inbound, outbound, onMessage = { _, _, _ -> })
        session.start()

        val message = byteArrayOf(0x01, 0x02, 0x03)
        session.send(channel = 0, control = true, message = message)

        val decoded = AapFrameCodec.readFrame(outbound.toInputStream())
        assertEquals(0, decoded.channel)
        assertTrue(decoded.isControl)
        assertArrayEquals(message, decoded.payload)

        session.close()
        inboundWriter.close()
        assertEquals(AapStreamSession.State.CLOSED, session.state())
    }

    @Test
    fun cannotRestartClosedSession() {
        val session = AapStreamSession(
            java.io.ByteArrayInputStream(byteArrayOf()),
            ByteArrayOutputStream(),
            onMessage = { _, _, _ -> }
        )
        session.start()
        Thread.sleep(50)
        assertEquals(AapStreamSession.State.CLOSED, session.state())
        try {
            session.start()
            throw AssertionError("expected start to fail")
        } catch (_: IllegalStateException) {
            // Expected: a session instance is single-use.
        }
    }
}
