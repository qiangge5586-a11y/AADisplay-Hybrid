package com.qiangge.diautocivic.aap;

import org.junit.Test;
import static org.junit.Assert.*;

public class AapVersionNegotiatorTest {
    @Test public void requestMatchesUpstreamWireBytes() {
        AapVersionNegotiator n = new AapVersionNegotiator();
        assertArrayEquals(new byte[] {0,3,0,4,0,1,0,1,0,2}, n.createRequest());
        assertEquals(AapVersionNegotiator.State.WAITING_FOR_RESPONSE, n.getState());
    }

    @Test public void ignoresUnexpectedMessageThenAcceptsVersionResponse() {
        AapVersionNegotiator n = new AapVersionNegotiator();
        n.createRequest();
        assertFalse(n.accept(new AapRawMessageDecoder.Message(1,3,4,new byte[0])));
        assertTrue(n.accept(new AapRawMessageDecoder.Message(0,3,2,new byte[] {1,0})));
        assertEquals(AapVersionNegotiator.State.COMPLETE, n.getState());
    }

    @Test public void cannotAcceptBeforeRequestOrAfterFailure() {
        AapVersionNegotiator n = new AapVersionNegotiator();
        AapRawMessageDecoder.Message response = new AapRawMessageDecoder.Message(0,3,2,new byte[0]);
        assertFalse(n.accept(response));
        n.createRequest();
        n.fail();
        assertFalse(n.accept(response));
        assertEquals(AapVersionNegotiator.State.FAILED, n.getState());
    }

    @Test public void resetAllowsANewExchange() {
        AapVersionNegotiator n = new AapVersionNegotiator();
        n.createRequest();
        n.reset();
        assertArrayEquals(new byte[] {0,3,0,4,0,1,0,1,0,2}, n.createRequest());
    }
}
