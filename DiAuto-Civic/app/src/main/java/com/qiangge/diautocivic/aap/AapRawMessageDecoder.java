package com.qiangge.diautocivic.aap;

import java.util.ArrayList;
import java.util.List;

/**
 * Incremental decoder for unencrypted/raw AAP messages used before TLS.
 *
 * Wire layout: channel, flags, big-endian (payload length + 2), big-endian
 * message type, then payload. This is distinct from the 4-byte encrypted
 * post-TLS frame header handled by AapFrameDecoder.
 */
public final class AapRawMessageDecoder {
    public static final int HEADER_SIZE = 6;
    public static final int MAX_PAYLOAD = 0xfffd;

    public static final class Message {
        public final int channel;
        public final int flags;
        public final int type;
        public final byte[] payload;

        Message(int channel, int flags, int type, byte[] payload) {
            this.channel = channel;
            this.flags = flags;
            this.type = type;
            this.payload = payload;
        }
    }

    private final byte[] header = new byte[HEADER_SIZE];
    private int headerUsed;
    private int payloadLength = -1;
    private int payloadUsed;
    private int channel;
    private int flags;
    private int type;
    private byte[] payload;

    public synchronized List<Message> feed(byte[] input, int offset, int length) {
        if (input == null || offset < 0 || length < 0 || offset > input.length - length) {
            throw new IllegalArgumentException("Invalid input range");
        }
        List<Message> messages = new ArrayList<Message>();
        int end = offset + length;
        while (offset < end) {
            if (headerUsed < HEADER_SIZE) {
                int count = Math.min(HEADER_SIZE - headerUsed, end - offset);
                System.arraycopy(input, offset, header, headerUsed, count);
                headerUsed += count;
                offset += count;
                if (headerUsed < HEADER_SIZE) continue;

                channel = header[0] & 0xff;
                flags = header[1] & 0xff;
                int encodedLength = ((header[2] & 0xff) << 8) | (header[3] & 0xff);
                if (encodedLength < 2) {
                    reset();
                    throw new IllegalStateException("AAP raw message length is smaller than its type field");
                }
                payloadLength = encodedLength - 2;
                if (payloadLength > MAX_PAYLOAD) {
                    reset();
                    throw new IllegalStateException("AAP raw payload exceeds the 16-bit wire limit");
                }
                type = ((header[4] & 0xff) << 8) | (header[5] & 0xff);
                payload = new byte[payloadLength];
                payloadUsed = 0;
                if (payloadLength == 0) messages.add(finishMessage());
            }

            if (payloadLength > 0) {
                int count = Math.min(payloadLength - payloadUsed, end - offset);
                if (count > 0) {
                    System.arraycopy(input, offset, payload, payloadUsed, count);
                    payloadUsed += count;
                    offset += count;
                }
                if (payloadUsed == payloadLength) messages.add(finishMessage());
            }
        }
        return messages;
    }

    private Message finishMessage() {
        Message message = new Message(channel, flags, type, payload);
        headerUsed = 0;
        payloadLength = -1;
        payloadUsed = 0;
        payload = null;
        return message;
    }

    public synchronized boolean isAtMessageBoundary() {
        return headerUsed == 0 && payloadLength == -1;
    }

    public synchronized void reset() {
        headerUsed = 0;
        payloadLength = -1;
        payloadUsed = 0;
        payload = null;
    }
}