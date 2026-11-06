package com.qiangge.diautocivic.aap;

import org.junit.Test;
import static org.junit.Assert.*;

public class AapUsbVersionHandshakeTest {
    private static final class FakeTransport implements AapUsbVersionHandshake.Transport {
        byte[] response;
        int responseOffset;
        int writes;
        boolean connected = true;
        int maxWrite = Integer.MAX_VALUE;

        FakeTransport(byte[] response) { this.response = response; }

        @Override public int write(byte[] data, int offset, int length, int timeoutMs) {
            writes++;
            return Math.min(length, maxWrite);
        }

        @Override public int read(byte[] out, int offset, int length, int timeoutMs) {
            if (responseOffset >= response.length) return 0;
            int n = Math.min(length, response.length - responseOffset);
            System.arraycopy(response, responseOffset, out, offset, n);
            responseOffset += n;
            return n;
        }

        @Override public boolean isConnected() { return connected; }
    }

    @Test public void acceptsOnlyChannelZeroVersionResponse() {
        byte[] bytes = new byte[] {
            0,3,0,4,0,4,8,0, // unrelated status: encoded length = type(2) + payload(2)
            0,3,0,4,0,2,1,0  // version response: encoded length = type(2) + payload(2)
        };
        FakeTransport transport = new FakeTransport(bytes);
        assertEquals(AapUsbVersionHandshake.Result.SUCCESS,
                new AapUsbVersionHandshake().perform(transport));
        assertEquals(1, transport.writes);
    }

    @Test public void handlesPartialWrites() {
        FakeTransport transport = new FakeTransport(new byte[] {0,3,0,4,0,2,1,0});
        transport.maxWrite = 3;
        assertEquals(AapUsbVersionHandshake.Result.SUCCESS,
                new AapUsbVersionHandshake().perform(transport));
        assertTrue(transport.writes > 1);
    }

    @Test public void returnsTransportErrorWhenDisconnected() {
        FakeTransport transport = new FakeTransport(new byte[0]);
        transport.connected = false;
        assertEquals(AapUsbVersionHandshake.Result.INVALID_TRANSPORT,
                new AapUsbVersionHandshake().perform(transport));
    }
}
