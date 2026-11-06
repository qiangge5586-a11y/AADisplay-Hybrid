# DiAuto Civic — API 17 USB Android Auto receiver

This is an isolated Android Auto / AAP receiver workstream for the 2019 Honda Civic factory center display.

## Target device

- Vehicle: 2019 Honda Civic, factory center display
- Head-unit OS: Android 4.2.2 / API 17
- CPU: ARMv7
- Root: available, but not required by the USB transport
- Connection: **USB cable only**

## Hard scope

The Civic build must not initialize or expose wireless Android Auto paths:
- no Wi-Fi Direct or SoftAP connection mode
- no Nearby Connections
- no Bluetooth RFCOMM credential handoff
- no wireless discovery/server mode
- no hotspot configuration in this DiAuto USB build

This is the Android Auto / AAP workstream. Do not mix in DiPlay's iAP2, AirPlay, CSM or MFi code. Do not modify the separate CivicMirror component.

## Current source implementation

A small standalone Android application now exists under `DiAuto-Civic/app`:

- `AoaProtocol.java`: API-17 Android USB Host AOA negotiation; sends all six identity strings with the required trailing NUL byte; validates complete control-transfer lengths; distinguishes START acknowledgement from disconnect/error ambiguity.
- `UsbBulkTransport.java`: opens an accessory-mode USB device, selects an interface only when it has both BULK IN and BULK OUT endpoints, claims it, and releases the interface before closing the connection.
- `MainActivity.java`: minimal diagnostic UI to enumerate USB devices, request permission, issue AOA negotiation, rescan after re-enumeration, and open the bulk interface.
- `AoaProtocolTest.java`: unit tests for NUL framing, empty strings, ASCII validation and AOA request constants.
- `.github/workflows/diauto-civic-build.yml`: CI workflow to run unit tests and build a debug APK.

**CI status:** workflow run [42231949204](https://github.com/qiangge5586-a11y/AADisplay-Hybrid/actions/runs/42231949204) passed the AOA protocol unit tests and built the current API-17-themed diagnostic APK. The artifact is available from the run's Artifacts section.

**This is currently a USB/AOA diagnostic shell, not yet a complete Android Auto receiver.** It does not yet implement AAP TLS/session handshake, message channels, audio, video projection or touch input.

## USB connection sequence

1. Enumerate the connected USB device through `UsbManager`.
2. Request the Android USB permission for that device.
3. Read AOA protocol version (request 51).
4. Send manufacturer/model/description/version/URI/serial (request 52), each as ASCII plus exactly one trailing NUL.
5. Send AOA START (request 53).
6. Wait for the phone to disconnect and re-enumerate as Google AOA VID/PID 18D1:2D00 or 18D1:2D01.
7. Request permission again for the newly enumerated accessory device.
8. Claim an interface with BULK IN and BULK OUT endpoints and open the transport.
9. Only after this transport is stable, attach the AAP protocol/session layer.

A successful START control-transfer result is not proof that step 6 completed. Re-enumeration must be observed separately.

## Build

The standalone project uses Android Gradle Plugin 8.7.3, Gradle 8.9 in CI, JDK 17, compileSdk 35, minSdk 17 and targetSdk 28. It deliberately has no AndroidX, Kotlin, Nearby or Conscrypt dependency in the initial USB shell.

GitHub Actions runs:
- `gradle -p DiAuto-Civic :app:testDebugUnitTest`
- `gradle -p DiAuto-Civic :app:assembleDebug`

The build result must be checked in the Actions run before describing the APK as built. A successful CI build does not prove runtime compatibility on the Android 4.2.2 Civic head unit.

## Known remaining work

1. Run CI and fix any compile/test failures.
2. Add a bounded USB attach → permission → AOA → re-enumeration state machine; the current diagnostic shell requires manual rescan/selection.
3. Add USB detach cancellation, permission denial recovery, retry deadlines and endpoint descriptor diagnostics.
4. Port only the upstream AAP framing/TLS/session components required by USB; exclude Wi-Fi, Bluetooth handoff and Nearby dependencies.
5. Audit API-17 bytecode and every resolved dependency before importing AndroidX or Conscrypt.
6. Add the AAP control/media/video/audio pipeline and API-17-compatible rendering.
7. Test on the actual Civic hardware; verify USB host capability, ARMv7 ABI, display, audio and sustained session stability.

## Crash triage status

The upstream DiAuto source audit found high-risk API-21/24/26/29 references in startup/service paths and an eager Application dependency graph. These are candidate compatibility risks, not a confirmed exception cause. The installed APK has not been available for runtime stack-trace inspection, and no claim is made that the original crash is fixed.

See [API 17 compatibility audit](API17-COMPATIBILITY-AUDIT.md) and [USB-only crash triage](USB-ONLY-AND-CRASH-TRIAGE.md).
