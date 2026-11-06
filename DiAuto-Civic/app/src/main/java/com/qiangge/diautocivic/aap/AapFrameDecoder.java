package com.qiangge.diautocivic.aap;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental decoder for the AAP USB byte stream.
 *
 * Wire framing follows upstream DiAuto's AapMessageIncoming/AapRead readers:
 * 4-byte header [channel, flags, encrypted-length BE16], followed by an
 * optional 4-byte declared fragment total when flags == 0x09, then the
 * encrypted body. The message type is inside the decrypted body, not this
 * encrypted transport header.
 *
 * This class is transport-only: it does not decrypt TLS or interpret protobuf.
 */
public final class AapFrameDecoder {
    public static final int HEADER_SIZE = 4;
    public static final int FIRST_FRAGMENT_FLAGS = 0x09;
    public static final int MAX_ENCRYPTED_BODY = 0xffff;
    private static final int MAX_DECLARED_FRAGMENT_TOTAL = 16 * 1024 * 1024;

    private final byte[] header = new byte[HEADER_SIZE];
    private final byte[] fragmentTotalBytes = new byte[4];
    private int headerUsed;
    private int fragmentTotalUsed;
    private int bodyUsed;
    private int bodyLength = -1;
    private int declaredFragmentTotal;
    private byte channel;
    private byte flags;
    private byte[] body;

    /** A complete encrypted AAP frame. Payload is a defensive copy. */
    public static final class Frame {
        public final int channel;
        public final int flags;
        public final int declaredFragmentTotal;
        public final byte[] encryptedBody;

        private Frame(int channel, int flags, int declaredFragmentTotal, byte[] body) {
            this.channel = channel;
            this.flags = flags;
            this.declaredFragmentTotal = declaredFragmentTotal;
            this.encryptedBody = body;
        }

        public boolean isFirstFragment() {
            return flags == FIRST_FRAGMENT_FLAGS;
        }
    }

    /**
     * Feed any portion of the USB byte stream. A single call may produce zero,
     * one, or many complete AAP frames.
     */
    public synchronized List<Frame> feed(byte[] input, int offset, int length) {
        if (input == null || offset < 0 || length < 0 || offset > (input == null ? 0 : input.length) - length) {
            throw new IllegalArgumentException("Invalid input range");
        }
        List<Frame> frames = new ArrayList<Frame>();
        int end = offset + length;
        while (offset < end) {
            if (headerUsed < HEADER_SIZE) {
                int count = Math.min(HEADER_SIZE - headerUsed, end - offset);
                System.arraycopy(input, offset, header, headerUsed, count);
                headerUsed += count;
                offset += count;
                if (headerUsed < HEADER_SIZE) {
                    continue;
                }
                channel = header[0];
                flags = header[1];
                bodyLength = ((header[2] & 0xff) << 8) | (header[3] & 0xff);
                if (bodyLength > MAX_ENCRYPTED_BODY) {
                    throw new IllegalStateException("AAP encrypted body exceeds 16-bit wire length");
                }
                if ((flags & 0xff) == FIRST_FRAGMENT_FLAGS) {
                    fragmentTotalUsed = 0;
                    declaredFragmentTotal = 0;
                } else {
                    fragmentTotalUsed = 4;
                    declaredFragmentTotal = 0;
                    beginBody();
                }
            }

            if (fragmentTotalUsed < 4) {
                int count = Math.min(4 - fragmentTotalUsed, end - offset);
                System.arraycopy(input, offset, fragmentTotalBytes, fragmentTotalUsed, count);
                fragmentTotalUsed += count;
                offset += count;
                if (fragmentTotalUsed < 4) {
                    continue;
                }
                long total = ((long) (fragmentTotalBytes[0] & 0xff) << 24)
                        | ((long) (fragmentTotalBytes[1] & 0xff) << 16)
                        | ((long) (fragmentTotalBytes[2] & 0xff) << 8)
                        | (long) (fragmentTotalBytes[3] & 0xff);
                if (total <= 0 || total > MAX_DECLARED_FRAGMENT_TOTAL) {
                    throw new IllegalStateException("Invalid AAP fragment total: " + total);
                }
                declaredFragmentTotal = (int) total;
                beginBody();
            }

            if (bodyLength == 0) {
                frames.add(finishFrame());
                continue;
            }
            int count = Math.min(bodyLength - bodyUsed, end - offset);
            if (count > 0) {
                System.arraycopy(input, offset, body, bodyUsed, count);
                bodyUsed += count;
                offset += count;
            }
            if (bodyUsed == bodyLength) {
                frames.add(finishFrame());
            }
        }
        return frames;
    }

    private void beginBody() {
        body = new byte[bodyLength];
        bodyUsed = 0;
    }

    private Frame finishFrame() {
        Frame frame = new Frame(channel & 0xff, flags & 0xff, declaredFragmentTotal, body);
        headerUsed = 0;
        fragmentTotalUsed = 0;
        bodyUsed = 0;
        bodyLength = -1;
        declaredFragmentTotal = 0;
        body = null;
        return frame;
    }

    /** Builds an unencrypted AAP control message (6-byte header + type + payload). */
    public static byte[] encodeRawMessage(int channel, int flags, int type, byte[] payload) {
        if (channel < 0 || channel > 255 || flags < 0 || flags > 255 ||
                type < 0 || type > 0xffff || payload == null || payload.length > 0xfffd) {
            throw new IllegalArgumentException("AAP raw message field out of range");
        }
        int encodedLength = payload.length + 2;
        byte[] result = new byte[6 + payload.length];
        result[0] = (byte) channel;
        result[1] = (byte) flags;
        result[2] = (byte) (encodedLength >>> 8);
        result[3] = (byte) encodedLength;
        result[4] = (byte) (type >>> 8);
        result[5] = (byte) type;
        System.arraycopy(payload, 0, result, 6, payload.length);
        return result;
    }

    /** True when no partial frame bytes remain buffered. */
    public synchronized boolean isAtFrameBoundary() {
        return headerUsed == 0 && fragmentTotalUsed == 0 && bodyLength == -1;
    }

    /** Discard partial bytes after a physical USB detach or unrecoverable stream error. */
    public synchronized void reset() {
        headerUsed = 0;
        fragmentTotalUsed = 0;
        bodyUsed = 0;
        bodyLength = -1;
        declaredFragmentTotal = 0;
        body = null;
    }
}
