package com.qiangge.diautocivic.aap;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class AapFrameDecoderTest {
    @Test public void decodesOneFrameSplitAcrossUsbReads() {
        AapFrameDecoder decoder = new AapFrameDecoder();
        byte[] wire = new byte[] { 0, 0x0b, 0, 3, 10, 11, 12 };
        assertTrue(decoder.feed(wire, 0, 2).isEmpty());
        assertTrue(decoder.feed(wire, 2, 3).isEmpty());
        List<AapFrameDecoder.Frame> frames = decoder.feed(wire, 5, 2);
        assertEquals(1, frames.size());
        assertEquals(0, frames.get(0).channel);
        assertEquals(0x0b, frames.get(0).flags);
        assertArrayEquals(new byte[] {10, 11, 12}, frames.get(0).encryptedBody);
        assertTrue(decoder.isAtFrameBoundary());
    }

    @Test public void decodesMultipleFramesFromOneBulkRead() {
        AapFrameDecoder decoder = new AapFrameDecoder();
        byte[] wire = new byte[] { 2, 0x0b, 0, 1, 7, 3, 0x0b, 0, 2, 8, 9 };
        List<AapFrameDecoder.Frame> frames = decoder.feed(wire, 0, wire.length);
        assertEquals(2, frames.size());
        assertArrayEquals(new byte[] {7}, frames.get(0).encryptedBody);
        assertArrayEquals(new byte[] {8, 9}, frames.get(1).encryptedBody);
    }

    @Test public void parsesFirstFragmentDeclaredTotalAsBigEndian() {
        AapFrameDecoder decoder = new AapFrameDecoder();
        byte[] wire = new byte[] { 2, 0x09, 0, 2, 0, 0, 1, 0, 44, 45 };
        AapFrameDecoder.Frame frame = decoder.feed(wire, 0, wire.length).get(0);
        assertTrue(frame.isFirstFragment());
        assertEquals(256, frame.declaredFragmentTotal);
        assertArrayEquals(new byte[] {44, 45}, frame.encryptedBody);
    }

    @Test public void rawControlMessageUsesSixByteHeaderAndBigEndianLength() {
        byte[] wire = AapFrameDecoder.encodeRawMessage(0, 3, 3, new byte[] {1, 2});
        assertArrayEquals(new byte[] {0, 3, 0, 4, 0, 3, 1, 2}, wire);
    }

    @Test public void resetDropsPartialFrame() {
        AapFrameDecoder decoder = new AapFrameDecoder();
        decoder.feed(new byte[] {0, 0x0b, 0}, 0, 3);
        decoder.reset();
        assertTrue(decoder.isAtFrameBoundary());
        assertEquals(1, decoder.feed(new byte[] {1, 0x0b, 0, 1, 99}, 0, 5).size());
    }
}
