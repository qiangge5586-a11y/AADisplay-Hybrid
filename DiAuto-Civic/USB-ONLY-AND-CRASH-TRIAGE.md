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

The USB service path is present, but the service is not currently USB-only: its startup still initializes wireless handshake / Wi-Fi Direct / hotspot credential managers. This is a verified source-level reason to separate transport initialization before the Civic build.

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
5. **Wireless startup side effects.** `AapService.onCreate` currently constructs `NativeAaHandshakeManager`, `WifiDirectManager`, `LocalHotspotCredentialsProvider` and conditionally `NearbyManager` (upstream lines around 765–783). This is a concrete separation point: the Civic USB flavor must not construct these wireless managers at service startup. `MainActivity.onCreate` also starts `AapService` and calls wireless setup, which must be disabled in the USB-only flavor.

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


## Deeper source-level startup crash analysis (2026-11-06)

The launch path was followed from the launcher Activity into Application initialization and the foreground service. This narrows the highest-risk areas without needing a device log.

### Startup sequence observed

1. Android creates `.App` before the launcher Activity.
2. `App.onCreate()` calls `isUserUnlocked()`, initializes Conscrypt, constructs `AppComponent` through root registration/settings paths, and registers a broadcast receiver.
3. `MainActivity.onCreate()` calls `enableEdgeToEdge()`, inflates the Material 3 app theme/layout, constructs `Intent(this, AapService::class.java)`, then starts the foreground service.
4. `AapService.onCreate()` registers receivers and media session, invokes `WifiAutoStartReceiver.checkAndStart(this)`, constructs wireless managers, and enters Wi-Fi initialization.

### Highest-priority API-17 startup risks

| Priority | Source evidence | Why it is risky on Android 4.2.2 | Required fix |
|---|---|---|---|
| P0 | `AapService.kt` imports and stores fields of types `Network`, `NetworkCapabilities`, `NetworkRequest`, `AudioFocusRequest`; also references `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` | These framework classes/constants were introduced after API 17. The service is instantiated on every MainActivity launch, so it is not safe to leave modern framework types in this always-loaded service class. Dalvik verification/class resolution may fail before SDK-gated branches execute. | Move all API 21/26/29+ types and calls into separate version-specific helper classes. The API-17 service class must contain only API-17 framework types. |
| P0 | `App.kt` imports `NotificationChannel` and `UserManager`; `onCreate()` invokes `isUserUnlocked()`, and the method contains a `UserManager` cast; notification-channel code is gated at API 26 | API 17 has neither class. Guarding code paths is not a sufficient verifier-isolation strategy for old Dalvik. | Move each newer type into an isolated API-specific class; avoid newer framework class references in `App` methods loaded at process startup. |
| P0 | `MainActivity.kt` calls `enableEdgeToEdge()` and extends `BaseActivity` / `AppCompatActivity`; `styles.xml` sets `AppTheme` parent to `Theme.Material3.DayNight.NoActionBar` | The launcher must inflate this theme before reaching USB. Any unsupported Material/AndroidX resource or runtime API can cause an immediate theme inflation crash. | Replace the launcher theme for Civic with a minimal API-17-compatible AppCompat/platform theme; remove edge-to-edge and unsupported Material3-only theme attributes from the USB build. |
| P1 | `MainActivity.kt` unconditionally calls `setupWifiDirectInfo()`; it starts `AapService` immediately | Wireless discovery and service startup are still on the mandatory launcher path, despite the user's USB-only requirement. | Remove Wi-Fi setup calls and start the service in explicit USB-only mode. |
| P1 | `AapService.onCreate()` invokes `WifiAutoStartReceiver.checkAndStart()`, constructs `NativeAaHandshakeManager`, `WifiDirectManager`, `SoftApCredentialsProvider`, `LocalHotspotCredentialsProvider`, and optionally `NearbyManager`; then calls `initWifiModeWithOptionalWait()` | Wireless stack initialization is unconditional or partially unconditional. It creates needless API/permission/driver interactions on the target and may fail independently of USB. | Compile out or dependency-inject a no-op wireless transport for the Civic USB flavor; do not instantiate wireless managers. |
| P1 | `VideoDecoder.kt` refers to `MediaCodecList.ALL_CODECS`, `MediaCodecInfo` and API-31 `Build.SOC_MANUFACTURER` | HEVC capability probing must not run on API 17; API 21+ MediaCodecList usage is only partly gated. | Keep API-17 codec path limited to `MediaCodec` / `MediaFormat` APIs available on API 16/17; put API-21+ codec enumeration and API-31 SoC probing in isolated helpers. |
| P1 | `UsbReceiver.kt` and `DeviceIntent.kt` contain API-33 typed `getParcelableExtra` overloads behind SDK checks; `UsbReceiver` also references modern PendingIntent flags | These methods/flags are newer than API 17 and sit in USB code that will be loaded on the target. | Keep typed-extra and mutable PendingIntent operations in API-33 helper; use legacy Parcelable overload and flags on API 17. |
| P2 | `CMakeLists.txt` always declares imported `libusb1.0.so` for `${ANDROID_ABI}`; also packages FFmpeg and builds `usbhelper`, `hur_soft_hevc`, `local_hotspot_radio` | A missing/wrong ARMv7 shared object or a binary built for a newer Android API can produce `UnsatisfiedLinkError` / `dlopen failed` when loaded. | Audit every packaged ARMv7 ELF, SONAME, NEEDED entries and Android API floor. For USB-only flavor, exclude `local_hotspot_radio` and unused HEVC native library from the package if not required. |

### Important distinction: install succeeds vs app crashes

The GitHub source does not prove the exact exception from the user's installed APK. However, because the app's first launch always passes through `App`, `MainActivity`, Material 3 theme inflation and `AapService`, the first three P0 items are the strongest source-based candidates for an immediate post-launch crash. The wireless initialization is a separate confirmed design defect for USB-only mode, not yet proven to be the crash trigger.

### USB-specific path checks

- The manifest declares `android.hardware.usb.host` as optional and registers `UsbAttachedActivity` for USB device attach.
- `UsbAccessoryMode` performs AOA GET_PROTOCOL / SEND_STRING / START control transfers and expects the phone to re-enumerate in Google accessory VID/PID mode.
- `UsbAccessoryConnection` opens the re-enumerated device, claims its interface, selects bulk IN/OUT endpoints, and transfers bytes.
- `CommManager.connect(UsbDevice)` is the USB entry into the AAP transport.
- `UsbNative` loads `usb1.0` and `usbhelper`; this must be lazy and must not be initialized during Application or launcher startup.
- The head unit's physical USB port and kernel must actually expose USB host mode. A software port cannot repair a peripheral-only port.

### Revised repair order

1. Make the launcher Application, launcher theme and foreground service load safely on API 17.
2. Split all API-21/26/29/31+ framework references into separately loaded helpers.
3. Add a Civic USB-only transport policy and stop all wireless manager construction/startup.
4. Keep the AOA + USB bulk transport + AAP session path; lazy-load USB native libraries only when a compatible phone is attached.
5. Strip Material 3 / modern navigation and optional features from the first API-17 smoke-test build; add features back only after the basic launcher and USB transport are stable.
6. Validate the ARMv7 native dependency set and then test AOA enumeration, AAP handshake, video/audio/touch.

This analysis is source-derived. It does not claim a confirmed crash exception, successful build, APK, or vehicle validation.

## Application object graph and Manifest audit (2026-11-06)

This pass inspected the complete upstream App.kt, AppComponent.kt, launcher MainActivity.kt, USB attach activity/receiver, and AndroidManifest.xml.

### Newly confirmed eager initialization chain

App.component is declared by lazy, but App.onCreate() immediately evaluates component.suExecutor.register().

That access constructs the entire AppComponent, whose non-lazy properties eagerly instantiate:
- Settings(app)
- VideoDecoder(settings, DeviceMemoryProfile.readWithOverride(...))
- AudioDecoder()
- CommManager(app, settings, audioDecoder, videoDecoder)
- CarKeysManager()

Only clusterVideoDecoder is lazy.

This means decoder and communication-manager construction happens during Application.onCreate(), before MainActivity is displayed and before any USB session. This expands the startup crash surface beyond the previously listed App.kt framework references.

Required Civic change:
1. Split root registration from the general application dependency graph; do not access a property that forces all of AppComponent to initialize.
2. Make VideoDecoder, AudioDecoder, CommManager, and other session components lazy or construct them from a USB-session component only.
3. Keep Application.onCreate() limited to API-17-safe logging/settings bootstrap and essential root setup.
4. Verify that CommManager and decoder constructors do not call System.loadLibrary, MediaCodec enumeration, or API-21+ framework APIs before USB attach.

### Manifest findings

The upstream manifest still includes:
- WifiAutoStartReceiver for android.net.wifi.STATE_CHANGE.
- AutoStartReceiver for Bluetooth ACL connection.
- Boot/quick-boot receiver declarations and broad automatic-start behavior.
- Wi-Fi, Nearby Wi-Fi, Bluetooth scan/connect/advertise, foreground-service-type and notification permissions.
- <queries> package/intent visibility declarations.
- Modern activity/service attributes including directBootAware, resizeableActivity, supportsPictureInPicture, localeConfig, foregroundServiceType and exported.

These declarations are not all equally problematic: some are newer manifest attributes or elements that older platform parsers may ignore, while newer permission names may simply be ineffective on API 17. The source alone does not prove that any single manifest item is the launch crash. They do prove the upstream manifest is not a clean USB-only/API-17 manifest.

Required Civic manifest work:
1. Create a dedicated USB-only manifest overlay rather than editing upstream behavior globally.
2. Remove Wi-Fi state and Bluetooth ACL auto-start receivers from the Civic variant; retain only boot/USB receivers that the USB product actually needs.
3. Remove permissions/features used only by wireless AA and modern-only services after call-site dependency analysis.
4. Remove modern service attributes and unsupported component metadata from the API-17 variant where not needed; use API-17-compatible service startup/notification behavior instead of foreground-service APIs introduced later.
5. Keep android.hardware.usb.host optional until the Civic hardware USB-host capability is confirmed; app manifest declarations cannot create host-controller support.

### USB path API-17 observations

- UsbReceiver uses the legacy getParcelableExtra(String) branch below API 33 and legacy PendingIntent.FLAG_UPDATE_CURRENT below API 31. Those branches are appropriate for API 17; the newer symbols should still be isolated if Dalvik verifier testing shows class/method resolution issues.
- UsbAttachedActivity references UserManager but guards its use behind API 24. Because this Activity is a declared USB attach entry point, isolate the Direct-Boot check in an API-24 helper so the USB Activity class itself stays API-17-safe.
- UsbAttachedActivity calls ContextCompat.startForegroundService for accessory-mode devices. Foreground-service startup is a modern API; API 17 requires a legacy startService path and a notification strategy supported by the old platform.
- MainActivity constructs a service Intent and uses ContextCompat.startForegroundService on every launch. This is not suitable as the only service-start path for API 17.
- UsbDeviceCompat uses API-21 USB product/manufacturer string accessors only inside an SDK check and falls back to VID/PID on older releases; retain that fallback.

### Revised next implementation slice

Before importing the full upstream app, implement a small API-17-safe Civic shell:
1. A minimal Application with no eager decoder/communication graph.
2. A legacy launcher Activity and API-17 theme/resources.
3. A plain API-17 service-start path with no foreground-service-type dependency.
4. A minimal manifest containing launcher, USB attach Activity, USB receiver and only required permissions.
5. A USB-session factory that constructs the USB transport and AAP layers only after the phone is attached and AOA mode is established.

Evidence references (upstream main snapshot):
- App.kt: eec34526a46c6105f800a7b53987a92f4effd14a
- AppComponent.kt: 5ed1a1e731b76bfcd35044f4bcb5f3e8254f766c
- MainActivity.kt: 13ad14b0154a5fd21d48c1a4555efafd08bdd8c3
- UsbAttachedActivity.kt: dafc81db1ec82e42fa91c9cbe13954b5d1b0a432
- UsbReceiver.kt: 077c23e5b0d09904f7c1e201d2d0459d7bbe5ddb
- AndroidManifest.xml: d1036e60ceec08d51624405e69951dfeaed836d6

This remains a static source audit. No runtime exception has been observed, and no APK has been built or tested in this pass.
## Constructor-level follow-up and compatibility correction (2026-11-06)

### What constructor inspection established

- CommManager's constructor initializes an AapSslContext and SingleKeyKeyManager as property initializers. Its constructor does not visibly instantiate UsbNative or call System.loadLibrary in the inspected class prefix. The eager object graph remains undesirable, but native USB loading has not been demonstrated on the Application path from this evidence.
- UsbNative loads usb1.0 and usbhelper in its companion-object initializer, and each UsbNative instance calls initContext() in its instance initializer. Therefore the critical requirement is to avoid loading/instantiating UsbNative before the USB path actually needs libusb; confirm all construction call sites before changing it.
- VideoDecoder's constructor has no explicit init block in the inspected class prefix. Its HEVC probe uses MediaCodecList only after an API-21 guard, and its API-31 SOC_MANUFACTURER access is guarded. This reduces the evidence that merely constructing VideoDecoder immediately calls MediaCodecList; however the class still contains newer framework references and its full call graph must be checked before API-17 approval.
- BaseActivity extends AppCompatActivity and its onCreate reads Configuration.UI_MODE_NIGHT_MASK and observes AppThemeManager.themeVersion. It also wraps the base context through LocaleHelper. The selected AndroidX/AppCompat dependency and theme/resource graph remain a compatibility risk; this source alone does not prove BaseActivity is the crash cause.

### Important correction on service-start compatibility

ContextCompat.startForegroundService is an AndroidX compatibility API and may fall back to startService on platforms before API 26. Do not classify this call alone as a confirmed API-17 crash. The actual API-17 concerns are the platform types/constants referenced directly by AapService, the API-29 foreground-service type overload, and whether the AndroidX Core version selected for the build can run on API 17. The Civic implementation should still provide an explicit legacy service lifecycle and avoid relying on modern foreground-service semantics.

### Refined priorities

1. Highest risk: direct API-21/26/29 framework references retained in AapService and API-24 UserManager references in the declared USB attach Activity; isolate these into version-specific classes.
2. High risk: Application eagerly creates the full AppComponent graph, including CommManager and decoder objects. Split root registration and session graph construction, then audit constructors and static initializers.
3. High risk: launcher uses modern AndroidX Activity APIs and Material 3 theme/resources; establish a minimal API-17 launcher before restoring features.
4. Confirmed product-scope defect: AapService always initializes Wi-Fi Direct/SoftAP/Bluetooth handshake-related managers and calls Wi-Fi auto-start logic, contrary to USB-only requirements.
5. Not yet established as a crash source: UsbNative loading. The class does load two native libraries when initialized, but the inspected CommManager constructor does not itself instantiate it; map all call sites before asserting it loads at process startup.

All conclusions remain static-source findings. The exact installed APK crash remains undiagnosed without runtime evidence.