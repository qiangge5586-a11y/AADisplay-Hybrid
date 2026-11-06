package com.qiangge.diautocivic.aap;

/**
 * Bounded pre-TLS AAP version exchange over a byte-stream transport.
 *
 * This class is deliberately transport-neutral. A USB caller supplies bulk
 * endpoint read/write operations; no wireless transport or Android API is
 * referenced here. It must run on a dedicated worker, never the UI thread.
 */
public final class AapUsbVersionHandshake {
    public interface Transport {
        /** Writes up to length bytes and returns the number written, or a negative error. */
        int write(byte[] data, int offset, int length, int timeoutMs);
        /** Reads up to length bytes and returns bytes read, 0 on timeout, or negative on error. */
        int read(byte[] data, int offset, int length, int timeoutMs);
        boolean isConnected();
    }

    public enum Result {
        SUCCESS,
        PEER_TIMEOUT,
        TRANSPORT_ERROR,
        INVALID_TRANSPORT
    }

    private static final int ATTEMPTS = 3;
    private static final int ATTEMPT_TIMEOUT_MS = 2000;
    private static final int READ_BUFFER_SIZE = 8192;

    private final AapVersionNegotiator negotiator = new AapVersionNegotiator();
    private final AapRawMessageDecoder decoder = new AapRawMessageDecoder();

    public Result perform(Transport transport) {
        if (transport == null || !transport.isConnected()) return Result.INVALID_TRANSPORT;

        byte[] request;
        try {
            request = negotiator.createRequest();
        } catch (RuntimeException e) {
            return Result.INVALID_TRANSPORT;
        }

        byte[] buffer = new byte[READ_BUFFER_SIZE];
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (!transport.isConnected()) {
                negotiator.fail();
                return Result.TRANSPORT_ERROR;
            }

            if (!writeFully(transport, request)) {
                negotiator.fail();
                return Result.TRANSPORT_ERROR;
            }

            long deadline = System.currentTimeMillis() + ATTEMPT_TIMEOUT_MS;
            while (transport.isConnected()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;

                int read = transport.read(buffer, 0, buffer.length, (int) Math.min(remaining, 500));
                if (read < 0) {
                    negotiator.fail();
                    return Result.TRANSPORT_ERROR;
                }
                if (read == 0) continue;

                try {
                    for (AapRawMessageDecoder.Message message : decoder.feed(buffer, 0, read)) {
                        if (negotiator.accept(message)) return Result.SUCCESS;
                    }
                } catch (RuntimeException malformed) {
                    negotiator.fail();
                    return Result.TRANSPORT_ERROR;
                }
            }
        }

        negotiator.fail();
        return transport.isConnected() ? Result.PEER_TIMEOUT : Result.TRANSPORT_ERROR;
    }

    private boolean writeFully(Transport transport, byte[] data) {
        int offset = 0;
        long deadline = System.currentTimeMillis() + ATTEMPT_TIMEOUT_MS;
        while (offset < data.length && transport.isConnected()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return false;
            int written = transport.write(data, offset, data.length - offset,
                    (int) Math.min(remaining, 500));
            if (written <= 0 || written > data.length - offset) return false;
            offset += written;
        }
        return offset == data.length;
    }

    /** Discard partial bytes when the underlying USB accessory detaches. */
    public void reset() {
        decoder.reset();
        negotiator.reset();
    }
}
