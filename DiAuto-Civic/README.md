# DiAuto Civic — API 17 USB Android Auto receiver

This is an isolated Android Auto / AAP receiver workstream for the 2019 Honda Civic factory center display.

## Target device
- Vehicle: 2019 Honda Civic, factory center display
- Head-unit OS: Android 4.2.2 / API 17
- CPU: ARMv7
- Root: available, but not required by the USB transport
- Connection: **USB cable only**

## Hard scope
The Civic build must not initialize or expose wireless Android Auto paths: no Wi-Fi Direct/SoftAP, Nearby Connections, Bluetooth RFCOMM credential handoff, wireless discovery/server mode or hotspot configuration.

This is the Android Auto / AAP workstream. Do not mix in DiPlay's iAP2, AirPlay, CSM or MFi code. Do not modify the separate CivicMirror component.

## Current source implementation
Standalone application: DiAuto-Civic/app.

- AoaProtocol.java: API-17 Android USB Host AOA negotiation; sends all six identity strings with the required trailing NUL byte; validates complete control-transfer lengths; distinguishes START acknowledgement from disconnect/error ambiguity.
- UsbBulkTransport.java: opens an accessory-mode USB device, selects an interface only when it has both BULK IN and BULK OUT endpoints, claims it, and releases the interface before closing the connection.
- MainActivity.java: USB device enumeration, single-candidate Android-device auto-selection, explicit selection for ambiguous devices, USB permission flow, AOA switching, bounded 15-second re-enumeration wait, rescan to catch early attach broadcasts, accessory permission and bulk endpoint opening. This is still a diagnostic state machine, not a production receiver.
- AoaProtocolTest.java: AOA NUL framing, empty strings, ASCII validation and request constants.
- aap/AapFrameDecoder.java: incremental post-TLS encrypted AAP frame decoder; handles partial USB reads, multiple frames in one bulk read, the 0x09 first-fragment total field, bounded lengths and detach reset. It does not perform TLS decryption.
- aap/AapFrameDecoderTest.java: frame fragmentation, batching, big-endian fragment total, raw control-message encoding and reset tests.
- AAP-FRAMING-AND-TLS-PORT-AUDIT.md: upstream wire-format findings, TLS/API-17 risks and the next port sequence.

## CI and APK artifact
Latest AAP decoder test/build run: https://github.com/qiangge5586-a11y/AADisplay-Hybrid/actions/runs/42233749889

- AOA and AAP framing unit tests: passed.
- Civic USB debug APK build: passed.
- Artifact: https://github.com/qiangge5586-a11y/AADisplay-Hybrid/actions/runs/42233749889/artifacts/13510073312
- Artifact SHA-256: 4ae30e157eeb3e4a7749a99f46eabb733fb9e57e8683d043d5e6e16031fcabd7

This artifact includes the USB/AOA diagnostic state machine and standalone AAP encrypted-frame decoder. It is **not** a complete Android Auto receiver: the AAP decoder is not yet wired into the USB session, and TLS, control-channel negotiation, protobuf messages, audio, video projection and touch input are not implemented.

A successful CI build proves source compilation and unit-test success only. It does not prove runtime compatibility on the Civic's Android 4.2.2 image or successful phone-to-car connection.

## USB connection sequence
1. Enumerate the connected USB device through UsbManager.
2. Request USB permission.
3. Read AOA protocol version (request 51).
4. Send manufacturer/model/description/version/URI/serial (request 52), each as ASCII plus exactly one trailing NUL.
5. Send AOA START (request 53).
6. Observe phone disconnect and re-enumeration as Google AOA VID/PID 18D1:2D00 or 18D1:2D01.
7. Request permission again for the accessory-mode device.
8. Claim an interface with BULK IN and BULK OUT endpoints and open the transport.
9. Implement the pre-TLS raw AAP control exchange and TLS handshake.
10. After TLS, connect the encrypted AAP frame decoder and message/session layer.

A successful START control-transfer result is not proof that re-enumeration completed. Re-enumeration must be observed separately.

## Build
The standalone project uses Android Gradle Plugin 8.7.3, Gradle 8.9 in CI, JDK 17, compileSdk 35, minSdk 17 and targetSdk 28. The current shell has no AndroidX, Kotlin, Nearby or Conscrypt dependency.

GitHub Actions runs:
- gradle -p DiAuto-Civic :app:testDebugUnitTest
- gradle -p DiAuto-Civic :app:assembleDebug

## Remaining work
1. Implement a bounded pre-TLS raw AAP control-message reader/writer, preserving partial data and rejecting malformed lengths.
2. Implement AAP version request/response with channel/type checks, retries and deadlines.
3. Port the TLS SSLEngine path only after checking client certificate/key resources, Conscrypt 2.5.3 AAR/native ABI/API floor, and API-17 runtime behavior. Upstream currently uses a trust-all server certificate manager; this security behavior must be explicitly evaluated rather than silently copied.
4. Send post-TLS status-OK and connect the encrypted frame decoder to a single USB receive pump.
5. Add AAP control messages and protobuf schemas, then audio/video/touch pipeline with API-17-safe framework calls.
6. Audit all packaged ARMv7 native libraries and resolved dependency API floors.
7. Test on the actual Civic hardware: USB host capability, AOA re-enumeration, TLS handshake, display, audio and sustained session stability.

## Crash triage status
The upstream DiAuto source audit found high-risk API-21/24/26/29 references in startup/service paths and an eager Application dependency graph. These are candidate compatibility risks, not a confirmed exception cause. The original installed APK has not been available for runtime stack-trace inspection, and no claim is made that its crash is fixed.

See API 17 compatibility audit, USB-only crash triage and AAP framing/TLS audit.