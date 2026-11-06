package com.qiangge.diautocivic.aap;

import org.junit.Test;
import javax.net.ssl.SSLContext;
import static org.junit.Assert.*;

public class AapTlsHandshakeTest {
    private static final class FakeTransport implements AapUsbVersionHandshake.Transport {
        boolean connected = true;
        @Override public int write(byte[] data, int offset, int length, int timeoutMs) {
            return length;
        }
        @Override public int read(byte[] data, int offset, int length, int timeoutMs) {
            return 0;
        }
        @Override public boolean isConnected() { return connected; }
    }

    @Test public void rejectsMissingTransportOrContext() throws Exception {
        AapTlsHandshake handshake = new AapTlsHandshake();
        FakeTransport transport = new FakeTransport();
        assertEquals(AapTlsHandshake.Result.INVALID_ARGUMENT,
                handshake.perform(null, SSLContext.getDefault()));
        assertEquals(AapTlsHandshake.Result.INVALID_ARGUMENT,
                handshake.perform(transport, null));
    }

    @Test public void rejectsDisconnectedTransport() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.connected = false;
        assertEquals(AapTlsHandshake.Result.INVALID_ARGUMENT,
                new AapTlsHandshake().perform(transport, SSLContext.getDefault()));
    }

    @Test public void rawTlsControlMessageUsesChannelZeroTypeThree() {
        byte[] payload = new byte[] {0x16, 0x03, 0x01};
        byte[] wire = AapFrameDecoder.encodeRawMessage(0, 3, 3, payload);
        assertArrayEquals(new byte[] {0, 3, 0, 5, 0, 3, 0x16, 0x03, 0x01}, wire);
    }

    @Test public void statusOkUsesUpstreamRawControlMessage() {
        byte[] wire = AapFrameDecoder.encodeRawMessage(0, 3, 4, new byte[] {8, 0});
        assertArrayEquals(new byte[] {0, 3, 0, 4, 0, 4, 8, 0}, wire);
    }
}
