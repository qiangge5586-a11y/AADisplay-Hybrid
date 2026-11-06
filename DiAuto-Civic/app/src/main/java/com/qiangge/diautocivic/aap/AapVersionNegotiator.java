package com.qiangge.diautocivic.aap;

public final class AapVersionNegotiator {
    public static final int CONTROL_CHANNEL = 0;
    public static final int VERSION_REQUEST_TYPE = 1;
    public static final int VERSION_RESPONSE_TYPE = 2;
    public static final int CONTROL_FLAGS = 3;
    private static final byte[] VERSION_PAYLOAD = new byte[] {0, 1, 0, 2};

    public enum State { NOT_STARTED, WAITING_FOR_RESPONSE, COMPLETE, FAILED }
    private State state = State.NOT_STARTED;

    /** Exact upstream request: channel 0, flags 3, type 1, payload 00 01 00 02. */
    public synchronized byte[] createRequest() {
        if (state != State.NOT_STARTED) {
            throw new IllegalStateException("Version exchange has already been started");
        }
        state = State.WAITING_FOR_RESPONSE;
        return AapFrameDecoder.encodeRawMessage(
                CONTROL_CHANNEL, CONTROL_FLAGS, VERSION_REQUEST_TYPE, VERSION_PAYLOAD);
    }

    /** Unrelated messages are ignored; only channel 0 / type 2 completes the exchange. */
    public synchronized boolean accept(AapRawMessageDecoder.Message message) {
        if (message == null) throw new IllegalArgumentException("message == null");
        if (state != State.WAITING_FOR_RESPONSE) return false;
        if (message.channel != CONTROL_CHANNEL || message.type != VERSION_RESPONSE_TYPE) return false;
        state = State.COMPLETE;
        return true;
    }

    public synchronized void fail() {
        if (state == State.WAITING_FOR_RESPONSE) state = State.FAILED;
    }

    public synchronized State getState() { return state; }

    public synchronized void reset() { state = State.NOT_STARTED; }
}
