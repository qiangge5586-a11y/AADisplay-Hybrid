package com.qiangge.diautocivic.usb;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AoaProtocolTest {
    @Test
    public void identityPayloadIncludesExactlyOneTrailingNul() {
        byte[] payload = AoaProtocol.encodeIdentityString("Android Auto");
        assertEquals(13, payload.length);
        assertEquals(0, payload[payload.length - 1]);
        assertEquals('A', payload[0]);
        assertEquals('o', payload[payload.length - 2]);
    }

    @Test
    public void emptyIdentityIsOneNulByte() {
        byte[] payload = AoaProtocol.encodeIdentityString("");
        assertEquals(1, payload.length);
        assertEquals(0, payload[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonAsciiIdentity() {
        AoaProtocol.encodeIdentityString("本田");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmbeddedNul() {
        AoaProtocol.encodeIdentityString("bad" + ((char) 0) + "value");
    }

    @Test
    public void allSixIdentitySlotsAreDefined() {
        assertEquals(0, AoaProtocol.STRING_MANUFACTURER);
        assertEquals(5, AoaProtocol.STRING_SERIAL);
        assertTrue(AoaProtocol.REQUEST_START > AoaProtocol.REQUEST_SEND_STRING);
    }
}
