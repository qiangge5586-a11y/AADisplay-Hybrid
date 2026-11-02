package com.iosbridge.iap2

/** Experimental iAP2 link sync state machine: initial SYN/SYN+ACK/ACK only. */
class Iap2LinkHandshake(
    private val role: Role,
    private val localInitialSequence: Int,
    private val localSyncPayload: ByteArray
) {
    enum class Role { INITIATOR, RESPONDER }
    enum class State { CLOSED, SYN_SENT, SYN_RECEIVED, ESTABLISHED, RESET, SLEEP }
    data class Step(val outbound: Iap2LinkPacketCodec.Packet?, val state: State, val note: String)
    var state: State = State.CLOSED
        private set
    private var peerNextSequence: Int? = null

    init {
        require(localInitialSequence in 0..255)
        require(Iap2SyncPayloadCodec.decode(localSyncPayload).isSuccess) { "local SYN payload invalid" }
    }

    @Synchronized fun start(): Iap2LinkPacketCodec.Packet {
        check(role == Role.INITIATOR && state == State.CLOSED)
        state = State.SYN_SENT
        return packet(Iap2LinkPacketCodec.CONTROL_SYN, localInitialSequence, 0, localSyncPayload)
    }

    @Synchronized fun receive(incoming: Iap2LinkPacketCodec.Packet): Result<Step> = runCatching {
        val c = incoming.control
        if (c and Iap2LinkPacketCodec.CONTROL_RST != 0) {
            state = State.RESET
            return@runCatching Step(null, state, "peer reset the link")
        }
        if (c and Iap2LinkPacketCodec.CONTROL_SLP != 0) {
            state = State.SLEEP
            return@runCatching Step(null, state, "peer entered sleep state")
        }
        require(c and Iap2LinkPacketCodec.CONTROL_EAK == 0) { "EAK not implemented" }
        val syn = c and Iap2LinkPacketCodec.CONTROL_SYN != 0
        val ack = c and Iap2LinkPacketCodec.CONTROL_ACK != 0
        require(incoming.sessionId == 0) { "link handshake packet must use session ID 0" }
        when (state) {
            State.SYN_SENT -> {
                require(syn); validatePeerSync(incoming)
                peerNextSequence = next(incoming.sequence)
                if (ack) {
                    require(incoming.acknowledgement == next(localInitialSequence))
                    state = State.ESTABLISHED
                    Step(ackPacket(), state, "SYN+ACK accepted; final ACK generated")
                } else {
                    state = State.SYN_RECEIVED
                    Step(synAckPacket(), state, "simultaneous SYN received")
                }
            }
            State.CLOSED -> {
                require(role == Role.RESPONDER && syn && !ack) { "responder expected initial SYN" }
                validatePeerSync(incoming)
                peerNextSequence = next(incoming.sequence)
                state = State.SYN_RECEIVED
                Step(synAckPacket(), state, "SYN accepted; SYN+ACK generated")
            }
            State.SYN_RECEIVED -> {
                if (syn && ack) {
                    validatePeerSync(incoming)
                    require(incoming.acknowledgement == next(localInitialSequence))
                    peerNextSequence = next(incoming.sequence)
                    state = State.ESTABLISHED
                    Step(ackPacket(), state, "simultaneous-open SYN+ACK accepted")
                } else {
                    require(!syn && ack && c == Iap2LinkPacketCodec.CONTROL_ACK && incoming.payload == null)
                    require(incoming.acknowledgement == next(localInitialSequence))
                    require(incoming.sequence == peerNextSequence)
                    state = State.ESTABLISHED
                    Step(null, state, "three-packet synchronization completed")
                }
            }
            State.ESTABLISHED -> error("session traffic is outside this module")
            State.RESET, State.SLEEP -> error("link is not active ($state)")
        }
    }

    private fun validatePeerSync(p: Iap2LinkPacketCodec.Packet) {
        require(p.payload != null && Iap2SyncPayloadCodec.decode(p.payload).isSuccess) { "peer SYN payload malformed" }
    }
    private fun synAckPacket() = packet(Iap2LinkPacketCodec.CONTROL_SYN or Iap2LinkPacketCodec.CONTROL_ACK,
        localInitialSequence, peerNextSequence ?: error("peer sequence unavailable"), localSyncPayload)
    private fun ackPacket() = packet(Iap2LinkPacketCodec.CONTROL_ACK, next(localInitialSequence),
        peerNextSequence ?: error("peer sequence unavailable"), null)
    private fun packet(c: Int, s: Int, a: Int, p: ByteArray?) =
        Iap2LinkPacketCodec.Packet(c, s, a, 0, p)
    private fun next(v: Int) = (v + 1) and 0xFF
}
