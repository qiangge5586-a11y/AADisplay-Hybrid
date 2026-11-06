# DiAuto Civic — Android Auto receiver adaptation

This directory is an isolated workstream for adapting the DiAuto Android Auto head-unit receiver to the 2019 Honda Civic factory head unit.

## Target device baseline

- Vehicle: 2019 Honda Civic, factory center display
- Head-unit OS: Android 4.2.2 (API 17)
- CPU ABI: ARMv7
- Privileges: root access is available on the head unit
- Intended role: Android Auto receiver running on the head unit
- Phone-side Android Auto projection: supplied by a compatible Android phone

The hardware/firmware baseline is based on the user's vehicle report. Exact board, SoC, display dimensions, audio routing, Wi-Fi chipset/driver and Bluetooth stack still require on-device inventory.

## Selected Civic connection mode

**USB only.** The Civic build must use a wired USB host/AOA connection path. Do not expose or initialize wireless Android Auto discovery, Wi-Fi Direct, SoftAP, Nearby Connections, Bluetooth RFCOMM credential handoff, or wireless auto-connect. Keep the AAP session/projection pipeline above the USB transport. See [USB-only scope and crash triage](USB-ONLY-AND-CRASH-TRIAGE.md).

## Project boundaries

- This is the **Android Auto / AAP** workstream. Do not reuse DiPlay's iAP2, AirPlay type-130 tunnel, CSM or MFi implementation as if it were Android Auto.
- Keep this work isolated from the existing AADisplay Hybrid legacy Android Auto virtual-display component and wireless CarPlay sender.
- Keep DiPlay reverse-engineering and receiver code in its existing repository/workstream.
- Root is an optional head-unit-side compatibility mechanism only. Any privileged Wi-Fi or system configuration must be narrowly scoped, reversible, backed up, and logged. Do not change MCU/CAN, reverse-camera, DSP or factory projection services without a verified rollback path.

## Compatibility strategy

1. **Source baseline and dependency audit**
   - Pin the selected upstream DiAuto revision and record its commit.
   - Inventory minSdk, targetSdk, Gradle/AGP/Kotlin versions, AndroidX and Play Services dependencies.
   - Identify API references unavailable on API 17, including references that can fail during class verification or static initialization.
   - Produce an API-17-compatible build strategy before changing protocol behavior.

2. **USB-only Civic transport**
   - Use USB host discovery, Android Open Accessory Protocol (AOA) negotiation, permission handling, accessory re-enumeration and bulk endpoint transport as the only connection path.
   - Remove wireless discovery, Wi-Fi Direct, SoftAP and Nearby connection startup from the Civic build.
   - Verify that the factory USB port and kernel expose USB host capability; software cannot convert a peripheral-only port into a host.

3. **Legacy Android platform layer**
   - Isolate API-level-specific calls behind compatibility interfaces.
   - Use classic Bluetooth/RFCOMM APIs available on the target platform, with runtime permission handling appropriate to API 17.
   - Implement a legacy SoftAP adapter using the platform's available Wi-Fi APIs and narrowly scoped reflection/root fallback where necessary.
   - Read back SSID, security mode, passphrase and network address from the actual head unit; never assume a configuration write succeeded.
   - Add timeout, cleanup, rollback and state verification to privileged operations.

4. **Android Auto protocol path**
   - Preserve the AAP transport/session state machine and audit its USB/TCP/Bluetooth bootstrap paths separately.
   - Validate Bluetooth discovery/pairing, Wi-Fi handoff, socket setup, TLS/session negotiation and AAP framing against real phone-side logs.
   - Add bounded retries, cancellation, reconnect and resource cleanup. Do not substitute guessed handshake bytes or claim compatibility without a trace-backed test.

5. **Legacy media/rendering path**
   - Validate H.264 hardware decode and supported stream profiles on the head unit.
   - Use API-17-compatible rendering and audio APIs; measure actual resolution, frame pacing, touch mapping and audio route behavior.
   - Disable unsupported codecs/features explicitly rather than allowing a crash or black screen.

6. **Build and vehicle validation**
   - Build an ARMv7 APK with an API-17-compatible toolchain/dependency set.
   - Run static/API compatibility checks and protocol tests before installing it.
   - Test on the actual Civic head unit, record logs and outcomes, and retain a known-good rollback APK/configuration.

## Acceptance gates

- APK installs and launches on Android 4.2.2 / API 17 without missing-class or unsupported-method failures.
- Head-unit hotspot configuration is read back and verified, not merely reported as successful.
- Bluetooth bootstrap and Wi-Fi handoff complete with a supported Android phone.
- AAP session establishes and recovers from timeout/disconnect without leaked sockets or stuck services.
- Video, audio and touch work on the factory display for a sustained test session.
- Privileged changes can be reverted and factory vehicle functions remain intact.

## Current status

**USB-only scope selected; crash triage and API-17 source audit in progress.** The upstream USB/AOA path has been identified, and likely API-17 dependency/class-loading/native-library risks are documented. The exact crash still requires a logcat stack trace. No USB-only source port or API-17 APK build has been completed, and no Civic test is claimed.

Next implementation gate: inspect and pin the upstream DiAuto source revision, then create a concrete API-17 incompatibility inventory before modifying application code.
