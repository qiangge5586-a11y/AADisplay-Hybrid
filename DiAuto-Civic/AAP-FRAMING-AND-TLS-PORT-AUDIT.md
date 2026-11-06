# AAP framing and TLS port audit

Audit date: 2026-11-06
Upstream snapshot: `shihabal3amri/DiAuto` main
Target: Civic Android 4.2.2 / API 17 / ARMv7, USB-only

## Scope boundary

The new `AapFrameDecoder` is the first isolated protocol primitive. It is deliberately not connected to the USB read loop and does not claim to implement TLS, AAP session negotiation, protobuf messages, media channels or Android Auto projection. The USB/AOA shell remains independently testable.

## Confirmed upstream wire formats

### Before TLS: raw AAP control messages

Upstream `aap/protocol/messages/Messages.kt` builds a raw control message as:

- byte 0: channel
- byte 1: flags
- bytes 2-3: big-endian value `payloadLength + 2`
- bytes 4-5: big-endian message type
- bytes 6 onward: payload

The initial version request is channel 0, flags 3, type 1, payload `00 01 00 02`. The response is recognized on channel 0 with type 2. After TLS completes, upstream sends the raw status-OK control message (channel 0, flags 3, type 4, payload `08 00`).

The TLS handshake bytes themselves are wrapped in raw AAP control messages of type 3. Upstream `AapSslContext.performHandshake` drives a JSSE `SSLEngine` with a 15-second overall deadline, reads complete raw AAP messages, unwraps TLS records, and wraps outbound TLS records in type-3 AAP messages.

### After TLS: encrypted stream framing

Upstream `AapMessageIncoming.EncryptedHeader` reads a 4-byte header:

- byte 0: channel
- byte 1: flags
- bytes 2-3: big-endian encrypted-body length

When flags equal `0x09`, the reader consumes an additional 4-byte big-endian declared fragment total before the encrypted body. The message type is in the decrypted plaintext, not in this 4-byte encrypted transport header.

USB bulk reads may contain partial frames or several frames at once. Upstream therefore has a separate multiple-message reader for USB/libusb streams, while its single-message reader is used for socket-backed transports. The new Java decoder handles partial and batched encrypted frames but is not a replacement for the pre-TLS raw-message reader.

## TLS and API-17 findings

- Upstream uses `SSLContext.getInstance("TLS")` and `SSLEngine`; it prefers the Conscrypt provider when `ConscryptInitializer` reports it available.
- Upstream GitHub flavor declares `org.conscrypt:conscrypt-android:2.5.3` while the Play Store flavor uses 2.6.1. This is a deliberate low-minSdk split, but it does not by itself prove that the packaged native library runs on this Civic's Android 4.2.2 ARMv7 image. The selected AAR manifest, transitive dependencies, packaged ABI and ELF/API floor still need inspection.
- Upstream's TLS client identity is loaded from `R.raw.cert` and `R.raw.privkey` through `SingleKeyKeyManager`. These resources and their licensing/provenance must be checked before copying.
- Upstream currently uses `NoCheckTrustManager`, which accepts any peer certificate. That is a compatibility/security behavior, not a safe default. Do not silently inherit it: first establish the actual Android Auto TLS trust requirement and document the risk before choosing a trust policy.
- API 17's platform TLS provider cannot be assumed to offer the same TLS 1.2 behavior as modern Android. Test the exact Conscrypt 2.5.3 ARMv7 build on API 17 before integrating it; avoid loading provider/native classes in the USB diagnostic startup path.

## Version exchange implementation

- `AapVersionNegotiator.java`: emits the upstream Version Request and accepts only channel 0 / type 2.
- `AapUsbVersionHandshake.java`: bounded transport-neutral worker-side exchange with partial-write handling, three 2-second response windows, unrelated-message filtering, and explicit timeout/transport-error results.
- Tests cover exact request bytes, unrelated control messages, partial writes, success, reset and disconnected transport.
- The request header length is 6 (two-byte message type + four-byte payload), matching upstream `Messages.createRawMessage`. A test expectation initially used 4 and was corrected after checking upstream source.

These classes are not yet wired to `UsbBulkTransport` or the Activity lifecycle. The next step is an adapter that maps the bulk endpoint read/write methods to the handshake transport, with a dedicated worker and detach cancellation.

## Implemented in this branch

- `AapFrameDecoder.java`: incremental post-TLS encrypted-frame parser; defensive frame payload copy; optional first-fragment total parsing; bounded 16-bit body length; reset after physical detach; raw AAP control-message encoder for protocol tests and later handshake work.
- `AapFrameDecoderTest.java`: split USB read, multiple frames in one read, first-fragment big-endian total, raw control-message encoding, and reset tests.

## Next implementation sequence

1. Finish CI for the AAP frame decoder and tests.
2. Add a separate bounded raw AAP control-message reader/writer for the pre-TLS exchange. It must preserve incomplete data and reject malformed lengths without consuming the next frame.
3. Implement version request/response with retry/deadline and exact channel/type checks.
4. Port TLS as a separate API-17-tested component: certificate/key resource handling, Conscrypt provider loading, SSLEngine NEED_WRAP/NEED_UNWRAP/NEED_TASK handling, underflow buffering, timeout and close behavior.
5. Only after TLS succeeds, send status-OK and connect the encrypted frame decoder to a single USB receive pump.
6. Add message-type parsing and protobuf only after the framing and TLS tests pass.
7. Keep all wireless discovery, Wi-Fi credential exchange and Bluetooth RFCOMM handoff out of this USB-only build.

No physical Civic test has been performed. A successful unit test or CI build is not proof that the phone switches to AOA, that the head unit USB port is a host, or that the AAP/TLS session works on the car.
