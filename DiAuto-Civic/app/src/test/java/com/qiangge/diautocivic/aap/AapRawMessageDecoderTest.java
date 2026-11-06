package com.qiangge.diautocivic.aap;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class AapRawMessageDecoderTest {
    @Test public void decodesVersionRequest() {
        AapRawMessageDecoder decoder = new AapRawMessageDecoder();
        byte[] request = new byte[] {0, 3, 0, 6, 0, 1, 0, 1, 0, 2};
        AapRawMessageDecoder.Message message = decoder.feed(request, 0, request.length).get(0);
        assertEquals(0, message.channel);
        assertEquals(3, message.flags);
        assertEquals(1, message.type);
        assertArrayEquals(new byte[] {0, 1, 0, 2}, message.payload);
    }

    @Test public void acceptsSplitHeaderAndPayload() {
        AapRawMessageDecoder decoder = new AapRawMessageDecoder();
        byte[] wire = new byte[] {0, 3, 0, 4, 0, 4, 8, 0};
        assertTrue(decoder.feed(wire, 0, 4).isEmpty());
        assertTrue(decoder.feed(wire, 4, 1).isEmpty());
        AapRawMessageDecoder.Message message = decoder.feed(wire, 5, 3).get(0);
        assertEquals(4, message.type);
        assertArrayEquals(new byte[] {8, 0}, message.payload);
        assertTrue(decoder.isAtMessageBoundary());
    }

    @Test public void decodesSeveralMessagesFromOneRead() {
        AapRawMessageDecoder decoder = new AapRawMessageDecoder();
        byte[] wire = new byte[] {0, 3, 0, 2, 0, 1, 0, 3, 0, 2, 0, 2};
        List<AapRawMessageDecoder.Message> messages = decoder.feed(wire, 0, wire.length);
        assertEquals(2, messages.size());
        assertEquals(1, messages.get(0).type);
        assertEquals(2, messages.get(1).type);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsEncodedLengthShorterThanType() {
        new AapRawMessageDecoder().feed(new byte[] {0, 3, 0, 1, 0, 1}, 0, 6);
    }

    @Test public void resetDropsPartialMessage() {
        AapRawMessageDecoder decoder = new AapRawMessageDecoder();
        decoder.feed(new byte[] {0, 3, 0}, 0, 3);
        decoder.reset();
        assertTrue(decoder.isAtMessageBoundary());
        assertEquals(1, decoder.feed(new byte[] {0, 3, 0, 2, 0, 1}, 0, 6).size());
    }
}