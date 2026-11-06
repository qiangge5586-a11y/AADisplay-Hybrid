package com.qiangge.diautocivic.aap;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

/**
 * AAP TLS handshake over the existing USB byte stream.
 *
 * The SSLContext is injected deliberately: provider selection, client identity
 * and peer trust policy must be decided and tested independently. No private
 * key or trust-all policy is embedded in this transport implementation.
 *
 * This component handles only the TLS handshake and the post-handshake AAP
 * Status-OK control message. It does not yet start the encrypted AAP pump.
 */
public final class AapTlsHandshake {
    public enum Result {
        SUCCESS,
        INVALID_ARGUMENT,
        TRANSPORT_ERROR,
        PEER_TIMEOUT,
        TLS_ERROR,
        CANCELLED
    }

    private static final int HANDSHAKE_TIMEOUT_MS = 15000;
    private static final int IO_TIMEOUT_MS = 500;
    private static final int READ_BUFFER_SIZE = 8192;
    private static final int MAX_TLS_BUFFER_SIZE = 1024 * 1024;
    private static final byte[] EMPTY = new byte[0];

    private final AapRawMessageDecoder rawDecoder = new AapRawMessageDecoder();
    private final ArrayDeque<byte[]> pendingTlsMessages = new ArrayDeque<byte[]>();
    private final byte[] wireReadBuffer = new byte[READ_BUFFER_SIZE];

    public Result perform(AapUsbVersionHandshake.Transport transport, SSLContext sslContext) {
        if (transport == null || sslContext == null || !transport.isConnected()) {
            return Result.INVALID_ARGUMENT;
        }
        rawDecoder.reset();
        pendingTlsMessages.clear();

        SSLEngine engine = null;
        try {
            engine = sslContext.createSSLEngine("android-auto", 5277);
            engine.setUseClientMode(true);
            engine.beginHandshake();

            long deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS;
            byte[] pendingNetwork = EMPTY;

            while (true) {
                if (Thread.currentThread().isInterrupted()) return Result.CANCELLED;
                if (!transport.isConnected()) return Result.TRANSPORT_ERROR;
                if (System.currentTimeMillis() >= deadline) return Result.PEER_TIMEOUT;

                SSLEngineResult.HandshakeStatus handshakeStatus = engine.getHandshakeStatus();
                if (handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED ||
                        handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                    byte[] statusOk = AapFrameDecoder.encodeRawMessage(
                            0, 3, 4, new byte[] {8, 0});
                    return writeFully(transport, statusOk, deadline)
                            ? Result.SUCCESS
                            : (Thread.currentThread().isInterrupted()
                                    ? Result.CANCELLED : Result.TRANSPORT_ERROR);
                }

                if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    runDelegatedTasks(engine);
                    continue;
                }

                if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    ByteBuffer networkOut = ByteBuffer.allocate(
                            Math.max(1024, engine.getSession().getPacketBufferSize()));
                    SSLEngineResult wrapped = engine.wrap(ByteBuffer.wrap(EMPTY), networkOut);
                    if (wrapped.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        int capacity = growCapacity(networkOut.capacity());
                        networkOut = ByteBuffer.allocate(capacity);
                        wrapped = engine.wrap(ByteBuffer.wrap(EMPTY), networkOut);
                    }
                    if (wrapped.getStatus() != SSLEngineResult.Status.OK) return Result.TLS_ERROR;
                    runDelegatedTasks(engine);
                    if (wrapped.bytesProduced() > 0) {
                        networkOut.flip();
                        byte[] tlsBytes = new byte[networkOut.remaining()];
                        networkOut.get(tlsBytes);
                        byte[] rawMessage = AapFrameDecoder.encodeRawMessage(0, 3, 3, tlsBytes);
                        if (!writeFully(transport, rawMessage, deadline)) {
                            return Thread.currentThread().isInterrupted()
                                    ? Result.CANCELLED : Result.TRANSPORT_ERROR;
                        }
                    } else if (wrapped.getHandshakeStatus() == handshakeStatus) {
                        return Result.TLS_ERROR;
                    }
                    continue;
                }

                if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                    if (pendingNetwork.length == 0) {
                        byte[] next = readTlsMessage(transport, deadline);
                        if (next == null) {
                            return Thread.currentThread().isInterrupted()
                                    ? Result.CANCELLED
                                    : (transport.isConnected() ? Result.PEER_TIMEOUT : Result.TRANSPORT_ERROR);
                        }
                        pendingNetwork = next;
                    }

                    ByteBuffer networkIn = ByteBuffer.wrap(pendingNetwork);
                    int appCapacity = Math.max(1024, engine.getSession().getApplicationBufferSize());
                    ByteBuffer applicationOut = ByteBuffer.allocate(appCapacity);
                    SSLEngineResult unwrapped = engine.unwrap(networkIn, applicationOut);
                    runDelegatedTasks(engine);

                    if (unwrapped.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        byte[] unconsumed = remaining(networkIn);
                        applicationOut = ByteBuffer.allocate(growCapacity(appCapacity));
                        networkIn = ByteBuffer.wrap(unconsumed);
                        unwrapped = engine.unwrap(networkIn, applicationOut);
                    }

                    if (unwrapped.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        byte[] incomplete = remaining(networkIn);
                        byte[] more = readTlsMessage(transport, deadline);
                        if (more == null) {
                            return Thread.currentThread().isInterrupted()
                                    ? Result.CANCELLED
                                    : (transport.isConnected() ? Result.PEER_TIMEOUT : Result.TRANSPORT_ERROR);
                        }
                        pendingNetwork = append(incomplete, more);
                    } else if (unwrapped.getStatus() == SSLEngineResult.Status.OK) {
                        int consumed = unwrapped.bytesConsumed();
                        if (consumed <= 0 && unwrapped.bytesProduced() <= 0 &&
                                unwrapped.getHandshakeStatus() == handshakeStatus) {
                            return Result.TLS_ERROR;
                        }
                        pendingNetwork = remaining(networkIn);
                    } else {
                        return Result.TLS_ERROR;
                    }
                    continue;
                }

                return Result.TLS_ERROR;
            }
        } catch (Exception error) {
            return Thread.currentThread().isInterrupted() ? Result.CANCELLED : Result.TLS_ERROR;
        } finally {
            if (engine != null) {
                try { engine.closeOutbound(); } catch (RuntimeException ignored) { }
            }
            rawDecoder.reset();
            pendingTlsMessages.clear();
        }
    }

    private byte[] readTlsMessage(AapUsbVersionHandshake.Transport transport, long deadline) {
        while (transport.isConnected() && System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) return null;
            if (!pendingTlsMessages.isEmpty()) return pendingTlsMessages.removeFirst();

            long remaining = deadline - System.currentTimeMillis();
            int count = transport.read(wireReadBuffer, 0, wireReadBuffer.length,
                    (int) Math.min(IO_TIMEOUT_MS, Math.max(1, remaining)));
            if (count < 0 || count > wireReadBuffer.length) return null;
            if (count == 0) continue;

            List<AapRawMessageDecoder.Message> messages =
                    rawDecoder.feed(wireReadBuffer, 0, count);
            for (AapRawMessageDecoder.Message message : messages) {
                // TLS records are raw AAP channel 0 / message type 3.
                // Ignore unrelated control messages rather than passing them to SSLEngine.
                if (message.channel == 0 && message.type == 3 && message.payload.length > 0) {
                    pendingTlsMessages.addLast(message.payload);
                }
            }
        }
        return pendingTlsMessages.isEmpty() ? null : pendingTlsMessages.removeFirst();
    }

    private static boolean writeFully(AapUsbVersionHandshake.Transport transport,
                                      byte[] bytes, long deadline) {
        int offset = 0;
        while (offset < bytes.length && transport.isConnected()) {
            if (Thread.currentThread().isInterrupted()) return false;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return false;
            int count = transport.write(bytes, offset, bytes.length - offset,
                    (int) Math.min(IO_TIMEOUT_MS, remaining));
            if (count <= 0 || count > bytes.length - offset) return false;
            offset += count;
        }
        return offset == bytes.length;
    }

    private static void runDelegatedTasks(SSLEngine engine) {
        if (engine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_TASK) return;
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) task.run();
        if (engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            throw new IllegalStateException("SSLEngine remains in NEED_TASK without a delegated task");
        }
    }

    private static int growCapacity(int current) {
        if (current >= MAX_TLS_BUFFER_SIZE) throw new IllegalStateException("TLS buffer limit exceeded");
        return Math.min(MAX_TLS_BUFFER_SIZE, current * 2);
    }

    private static byte[] append(byte[] first, byte[] second) {
        if (first.length > MAX_TLS_BUFFER_SIZE - second.length) {
            throw new IllegalStateException("TLS record buffer limit exceeded");
        }
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] remaining(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) return EMPTY;
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
}
