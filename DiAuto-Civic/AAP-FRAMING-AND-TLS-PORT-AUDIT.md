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

- The upstream TLS implementation is in `AapSslContext.kt`. It creates a client-mode `SSLEngine`, drives `NEED_WRAP`, `NEED_UNWRAP` and `NEED_TASK`, wraps outgoing TLS records in raw AAP type-3 messages, and applies a 15-second overall handshake deadline.
- The upstream raw TLS reader consumes a six-byte AAP header and then the payload. Its helper returns only the payload and does not itself validate the message channel/type before feeding bytes to TLS. The USB port must improve this: accept only the expected control channel and TLS-data message type, use the incremental raw decoder for partial/multiple USB reads, and reject malformed lengths.
- The upstream `SingleKeyKeyManager` loads an X.509 certificate and a PKCS#8 RSA private key from `R.raw.cert` and `R.raw.privkey`. It extends `X509ExtendedKeyManager`, whose Android API level is 1, so this superclass is not itself an API-17 blocker. The upstream public certificate identifies a Google Automotive Link / Google-Android-Reference identity and is valid from 2014-07-08 through 2044-07-07. The matching private key is present in upstream source; do not print it in diagnostics or logs, and do not copy it into a new distribution without confirming the upstream license/provenance and protocol necessity.
- The upstream `NoCheckTrustManager` accepts every peer certificate. This may be part of the compatibility behavior expected by the current AAP flow, but it disables peer authentication and permits a man-in-the-middle. Keep this as an explicit, isolated decision rather than an invisible default; investigate the actual phone-side certificate model before replacing it with pinning or normal PKI validation.
- Upstream `ConscryptInitializer` reflectively loads `org.conscrypt.Conscrypt`, creates a provider and inserts it into the JCA provider list. Upstream initializes it for TLS 1.2 on Android versions below API 21. The USB-only port should load it lazily at TLS start, not during the diagnostic Activity/Application startup.
- The upstream DiAuto GitHub flavor pins `org.conscrypt:conscrypt-android:2.5.3`. The exact Conscrypt 2.5.3 source tag's Android Gradle build declares `minSdkVersion 9` and ABI filters including `armeabi-v7a`; therefore API 17 and ARMv7 are within its declared build targets. This is stronger evidence than the current Conscrypt documentation, which describes newer releases and their newer Android minimums. It still does not replace inspection of the resolved 2.5.3 AAR/JNI ELF dependencies and a runtime test on the actual Android 4.2.2 image.
- `SSLEngine` itself and `X509ExtendedKeyManager` are available from API 1 according to Android API references. The major remaining risk is not those Java types but the exact provider implementation, key material, TLS cipher/protocol negotiation and AAP record exchange on this device.

- Upstream uses `SSLContext.getInstance("TLS")` and `SSLEngine`; it prefers the Conscrypt provider when `ConscryptInitializer` reports it available.
- Upstream GitHub flavor declares `org.conscrypt:conscrypt-android:2.5.3` while the Play Store flavor uses 2.6.1. This is a deliberate low-minSdk split, but it does not by itself prove that the packaged native library runs on this Civic's Android 4.2.2 ARMv7 image. The selected AAR manifest, transitive dependencies, packaged ABI and ELF/API floor still need inspection.
- Upstream's TLS client identity is loaded from `R.raw.cert` and `R.raw.privkey` through `SingleKeyKeyManager`. These resources and their licensing/provenance must be checked before copying.
- Upstream currently uses `NoCheckTrustManager`, which accepts any peer certificate. That is a compatibility/security behavior, not a safe default. Do not silently inherit it: first establish the actual Android Auto TLS trust requirement and document the risk before choosing a trust policy.
- API 17's platform TLS provider cannot be assumed to offer the same TLS 1.2 behavior as modern Android. Test the exact Conscrypt 2.5.3 ARMv7 build on API 17 before integrating it; avoid loading provider/native classes in the USB diagnostic startup path.

## Version exchange implementation

- `AapVersionNegotiator.java`: emits the upstream Version Request and accepts only channel 0 / type 2.
- `AapUsbVersionHandshake.java`: bounded transport-neutral exchange with partial-write handling, three 2-second response windows, unrelated-message filtering, explicit timeout/transport-error results, and cooperative thread interruption.
- `UsbAapTransport.java`: API-17 adapter for USB bulk read/write. It copies byte slices because the API-17 `bulkTransfer` overload has no offset parameter.
- `UsbBulkTransport.java`: exposes connection state, treats runtime USB I/O exceptions as transport errors, and closes safely after detach.
- `MainActivity.java`: runs the AAP version exchange on a dedicated worker after AOA accessory endpoints open; USB detach and Activity destruction close the transport and interrupt the worker. UI completion is ignored for stale/destroyed Activity state.
- Unit tests cover exact request bytes, valid raw response framing, unrelated status and nonzero-channel filtering, partial writes, success, reset and disconnected transport.

The version exchange is now wired end-to-end in source from the Activity to USB bulk endpoints. This only validates the pre-TLS version negotiation stage; TLS, status-OK after TLS, encrypted AAP receive pump, protobuf and projection remain unimplemented. CI and API-17 runtime validation are required before calling this build usable.

## Implemented in this branch

- `AapFrameDecoder.java`: incremental post-TLS encrypted-frame parser; defensive frame payload copy; optional first-fragment total parsing; bounded 16-bit body length; reset after physical detach; raw AAP control-message encoder for protocol tests and later handshake work.
- `AapFrameDecoderTest.java`: split USB read, multiple frames in one read, first-fragment big-endian total, raw control-message encoding, and reset tests.

## Next implementation sequence

2. Verify the USB-bound version request/response on CI and an API-17 runtime.
3. Port TLS as a separate API-17-tested component: certificate/key resource handling, Conscrypt provider loading, SSLEngine NEED_WRAP/NEED_UNWRAP/NEED_TASK handling, underflow buffering, timeout and close behavior.
5. Only after TLS succeeds, send status-OK and connect the encrypted frame decoder to a single USB receive pump.
6. Add message-type parsing and protobuf only after the framing and TLS tests pass.
7. Keep all wireless discovery, Wi-Fi credential exchange and Bluetooth RFCOMM handoff out of this USB-only build.

No physical Civic test has been performed. A successful unit test or CI build is not proof that the phone switches to AOA, that the head unit USB port is a host, or that the AAP/TLS session works on the car.

## TLS handshake module port (2026-11-07)

- Added `AapTlsHandshake.java`, a transport-neutral Java implementation that accepts an injected `SSLContext`; it does not bundle a certificate, private key, Conscrypt provider, or trust-all manager.
- The worker drives client-mode `SSLEngine` through `NEED_WRAP`, `NEED_UNWRAP`, and `NEED_TASK`, with a 15-second overall deadline and cooperative thread interruption.
- Outbound TLS records are wrapped as raw AAP channel 0 / flags 3 / type 3 messages. Inbound data is incrementally decoded as raw AAP messages; only non-empty channel 0 / type 3 payloads are delivered to the TLS engine. Other control messages are ignored.
- TLS underflow preserves only the unconsumed network bytes before appending the next TLS payload. This is important when a USB bulk read splits a TLS record.
- On TLS completion, the module sends the upstream Status-OK message (channel 0 / flags 3 / type 4 / payload `08 00`).
- Added boundary/wire-format unit tests for null/disconnected inputs, TLS type-3 framing and Status-OK framing.

**Not yet wired into MainActivity:** the version-exchange success callback still reports that TLS is the next stage. A caller must supply a vetted `SSLContext`; provider selection, client certificate/key provenance and peer trust policy remain open. The module does not yet establish the encrypted AAP message pump. Passing its unit tests is not evidence of a successful phone TLS handshake.
