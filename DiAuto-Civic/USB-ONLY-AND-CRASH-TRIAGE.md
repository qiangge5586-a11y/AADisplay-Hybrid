# DiAuto Civic — USB-only scope and API 17 crash triage

Audit date: 2026-11-06
Target: 2019 Honda Civic factory head unit, Android 4.2.2 / API 17 / ARMv7
Upstream: `shihabal3amri/DiAuto`, source snapshot inspected during this pass

## User-selected product scope

The Civic build is **USB-only**. Do not ship or expose wireless Android Auto connection modes in the Civic UI or startup flow.

Keep:
- USB host device discovery and attach/detach handling.
- Android Open Accessory Protocol (AOA) negotiation and USB re-enumeration.
- USB permission request and grant handling.
- USB bulk IN/OUT endpoint setup and transfer.
- AAP transport/session, TLS and projection pipeline above the USB transport.
- USB reconnect, timeout, cancellation and cleanup.

Remove from the Civic build (not necessarily from upstream):
- Wi-Fi Direct discovery/group creation.
- SoftAP creation, credentials exchange and wireless network routing.
- Nearby Connections discovery/transport.
- Bluetooth RFCOMM wireless AA handshake and Wi-Fi credential handoff.
- Wireless auto-connect settings, wireless-only onboarding screens and background wireless service startup.

Do not remove Bluetooth permissions or phone-call/audio routing blindly until the service dependencies are separated; the wireless connection handshake can be removed while unrelated Bluetooth audio features are evaluated independently.

## Confirmed USB implementation in upstream

The upstream contains a usable starting USB path:
- `UsbAttachedActivity.kt`: receives `USB_DEVICE_ATTACHED`, identifies Android devices, checks permission, and invokes AOA mode switching.
- `UsbAccessoryMode.kt`: performs AOA control transfers (GET_PROTOCOL, SEND_STRING, START) and waits for re-enumeration.
- `UsbAccessoryConnection.kt`: opens the device, claims the interface, locates bulk IN/OUT endpoints and transfers data.
- `UsbReceiver.kt`: handles USB attach/detach and permission broadcasts.
- `UsbDeviceCompat.kt`: classifies devices and detects accessory mode.
- `CommManager.kt`: owns USB connection and AAP transport lifecycle.

Important target check: the Civic's physical USB port must enumerate the phone as a USB host. If the vehicle port is device/peripheral-only, app changes cannot turn it into a host; verify host-controller/OTG wiring and kernel support before protocol work.

## Crash findings from source inspection

The inspected upstream `app/build.gradle.kts` declares:
- `minSdk = 16` for the GitHub flavor, but `targetSdk = 36`.
- AndroidX Activity 1.8.2, Fragment 1.6.2, Core KTX 1.12.0, AppCompat 1.6.1, Material 1.10.0, Lifecycle 2.6.2 and other modern dependencies.
- Google Play Services Nearby 19.3.0, plus Shizuku and libsu.
- Conscrypt Android 2.5.3 for the GitHub flavor.
- NDK 29 native build and packaged libusb/FFmpeg native libraries.

The Manifest uses `tools:overrideLibrary` for several Google/Shizuku libraries. This only suppresses manifest minimum-SDK merge checks; it does **not** backport missing Android framework APIs or make unsupported bytecode/native code safe on API 17.

### High-priority crash suspects (not yet confirmed by device log)

1. **Dependency/API floor mismatch.** Several AndroidX versions in the graph are newer than the API-17 baseline. The resolved AAR manifests and transitive dependency graph must be inspected. Do not assume `minSdk=16` proves runtime compatibility.
2. **API-level classes in startup/service paths.** `App.kt` imports `UserManager` and `NotificationChannel`; it gates the newer calls, but class verification on Dalvik must be tested. `AapService.kt` contains API-21 `Network` / `NetworkCapabilities` / `NetworkRequest` references and an API-26 `AudioFocusRequest` field/type. Move these into SDK-specific helper classes so API 17 never loads them.
3. **New Activity APIs.** `MainActivity.kt` uses AndroidX Activity APIs such as `enableEdgeToEdge`, Activity Result and newer lifecycle integrations. The selected library implementation and runtime API floor must be compatible with API 17 or replaced with a legacy Activity path.
4. **Native library loading.** CMake builds `usbhelper`, `hur_soft_hevc` and `local_hotspot_radio`; libusb and FFmpeg .so files are imported from ABI-specific jniLibs. Inspect every packaged ELF for ARMv7 ABI and minimum Android API; a single incompatible .so can crash when loaded.
5. **Wireless startup side effects.** `MainActivity.onCreate` starts `AapService` and calls wireless setup. For the Civic build, service startup must initialize USB-only components and must not instantiate Wi-Fi Direct / Nearby / SoftAP code.

The exact crash cause cannot be confirmed without the failing APK's `logcat` stack trace. The findings above are source-based suspects, not a diagnosis of a particular runtime exception.

## Required USB-only architecture changes

1. Introduce a `CivicUsbOnly` build flavor/build config and a single transport policy returning USB-only.
2. Make USB device attach, permission, AOA switch and accessory re-enumeration the only connection entry points.
3. Ensure `AapService` and `CommManager` create only USB transport on this flavor; wireless classes must not be referenced during app/service initialization.
4. Keep AAP protocol, TLS, decoder and projection layers transport-agnostic wherever possible.
5. Remove wireless UI/settings from this flavor and disable wireless boot receivers.
6. Replace or pin dependencies to versions demonstrably compatible with API 17. Isolate API 21+ and API 26+ framework references in separate classes.
7. Produce ARMv7-only APK first; verify DEX and native libraries, then run API-17 emulator/device smoke tests.
8. Verify physical USB host capability on the Civic before attempting phone AOA enumeration.

## Diagnostic evidence required for the current crash

Capture immediately after launching the app:

```sh
adb logcat -c
adb shell am force-stop com.andrerinas.headunitrevived
adb shell monkey -p com.andrerinas.headunitrevived 1
adb logcat -d -v time | grep -E "FATAL EXCEPTION|AndroidRuntime|VerifyError|NoClassDefFoundError|ClassNotFoundException|UnsatisfiedLinkError|dlopen failed|Fatal signal"
```

If ADB is unavailable, export the app's internal log plus the system crash report. The key evidence is the first `FATAL EXCEPTION` block, including exception type, message and first app stack frame.

## Status

- USB-only requirement recorded.
- Upstream USB source path identified.
- Initial API-17 startup/dependency/native crash suspects recorded.
- No source code has yet been ported or modified.
- No APK has been rebuilt or tested on the Civic.
- The exact current crash remains unconfirmed until the runtime stack trace is available.
