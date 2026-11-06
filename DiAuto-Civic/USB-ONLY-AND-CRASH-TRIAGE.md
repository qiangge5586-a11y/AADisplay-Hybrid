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
## Build configuration and dependency-floor audit (2026-11-06)

### Confirmed Gradle configuration

Upstream app/build.gradle.kts declares compileSdk 36, default minSdk 16, targetSdk 36, NDK 29.0.14206865, and Java/Kotlin bytecode target 1.8. The github flavor inherits minSdk 16; the playstore flavor explicitly raises minSdk to 21. Version is 0.3.12.

Declared runtime dependencies include AndroidX Activity 1.8.2, Fragment 1.6.2, Media 1.6.0, RecyclerView 1.3.2, Material 1.10.0, AppCompat 1.6.1, Startup Runtime 1.1.1, Nearby 19.3.0, Core KTX 1.12.0, Lifecycle ViewModel KTX 2.6.2, Kotlin stdlib 1.9.0, Multidex 2.0.1, Navigation 2.3.5, Glide 4.16.0, Shizuku 13.1.5 and libsu 6.0.0. Conscrypt is flavor-specific: 2.5.3 for github and 2.6.1 for playstore.

### What this does and does not prove

- The github flavor's minSdk=16 is a build declaration, not proof that all resolved AAR manifests, bytecode, resources and native libraries are API-16/17 compatible.
- The playstore flavor is explicitly not a candidate for the Civic API-17 target because its minSdk is 21.
- The source build declares compileSdk/targetSdk 36 and a recent NDK, so the Civic variant should not simply inherit the upstream build wholesale.
- The full resolved dependency graph and each AAR's merged manifest have not been produced in this source-only inspection. Do not claim a particular AndroidX artifact is definitely the crash cause until its actual resolved manifest and bytecode/API usage are inspected.
- CMake defines usbhelper, imported libusb1.0, hur_soft_hevc and local_hotspot_radio. The presence of these targets does not itself prove they load at startup; map Java/Kotlin load sites and inspect packaged ARMv7 ELF files before attributing a crash.

### Required build-system changes for Civic

1. Add a dedicated civicUsb flavor with minSdk 17 and ABI filter armeabi-v7a; do not reuse playstore or github behavior without checking merged manifests.
2. Remove Nearby, Wi-Fi, hotspot and wireless-only dependencies from the Civic variant when source separation makes them unnecessary.
3. Keep Conscrypt only if the AAP TLS path requires it on Android 4.2.2; validate the exact 2.5.3 ARMv7 native binary and TLS provider behavior on API 17.
4. Generate and inspect the Civic merged manifest and dependency report; treat any dependency whose manifest minSdk exceeds 17 as a blocker unless replaced or proven safely optional.
5. Build native USB support with an API-17-compatible NDK platform target and inspect each shipped ARMv7 shared object for ELF class, machine, SONAME, NEEDED entries and symbol/API requirements.
6. Exclude local_hotspot_radio and optional HEVC/FFmpeg binaries from the USB-only package if the selected USB AAP path does not use them; retain only libraries required by the actual code path.

Evidence references (upstream main snapshot):
- app/build.gradle.kts: 611d79938bcb6367e2d9703449d4583a338023cb
- root build.gradle.kts: bb6c2d599836a8c0f9ca4bc7a6889207abc945db
- gradle/wrapper/gradle-wrapper.properties: 9fc21c1d990bac6f7c4ae99895c1fed38cceb3ce
- app/src/main/cpp/CMakeLists.txt: 834937263243e4b2d09ec10a65d9b038962b3eb5
- app/src/main/res/values/styles.xml: 8a7355e1c374f37ac4a4cf6b60dd59fbf8b9de9a

This audit records declared configuration only. No dependency resolution, merged-manifest build, native ELF inspection, APK build or device test was performed.
## USB transport call-site audit (2026-11-06)

### Confirmed USB transport selection

CommManager selects the transport using Settings.useLibusb:
- false (the default in Settings.kt): UsbAccessoryConnection, using Android UsbManager / UsbDeviceConnection / claimInterface / bulkTransfer APIs.
- true: LibusbAccessoryConnection, which instantiates UsbNative and uses JNI/libusb.

Settings.useLibusb reads the persisted preference use-libusb and defaults to false. UsbAttachedActivity also falls back to false when settings are unavailable. Therefore the ordinary default AOA/USB path does not require UsbNative or libusb.

### Native load call sites are now mapped

- UsbNative companion initializer loads usb1.0 and usbhelper. UsbNative instance initialization then calls initContext().
- UsbNative construction is present in UsbAccessoryMode only when useLibusb=true, and in LibusbAccessoryConnection.connect().
- FfmpegHevcDecoder companion initialization loads hur_soft_hevc, but that class is only needed for optional software HEVC decoding; remove it from the USB-only Civic package if the selected video path does not require software HEVC.
- LegacyHotspotNative loads local_hotspot_radio; this is unrelated to USB AOA/Bulk transport and should be excluded from the Civic USB variant unless another retained feature demonstrably calls it.

Consequently, it would be inaccurate to claim libusb is unconditionally loaded at Application startup based on UsbNative alone. The correct Civic design is to force the standard Android USB host transport and remove the libusb preference/branch from the Civic flavor, then omit libusb JNI binaries if no remaining code references them.

### Standard Android USB transport is suitable as the baseline

The non-libusb path performs AOA control transfers through UsbDeviceConnection.controlTransfer(), then opens the re-enumerated accessory with UsbManager.openDevice(), claims the USB interface, selects IN/OUT endpoints and exchanges data using bulkTransfer(). These Android USB Host APIs predate API 17 (USB Host API support was introduced before Android 4.2), so the Java USB path is the correct compatibility baseline. This does not prove the Civic head unit's physical USB port is in host mode; that remains a hardware prerequisite.

### AOA and connection lifecycle details

- AOA switch opens the device with UsbManager.openDevice(), requests protocol version 51, sends the six identification strings with request 52, then sends request 53 to trigger accessory re-enumeration.
- The source currently uses a fixed 500 ms delay after the START request and returns success even if the control transfer result is negative, because it treats immediate disconnect as a possible re-enumeration. This is a heuristic, not proof the phone actually re-enumerated. Civic code should verify the subsequent USB attach/accessory-mode event and use a bounded retry/deadline rather than treating the START attempt alone as a complete connection.
- The standard USB transport claims interface 0 and picks the first IN and OUT endpoints without checking that both are BULK endpoints. Add explicit endpoint-type validation and report interface/endpoint descriptors when the device is unsupported.
- UsbAccessoryConnection disconnect() closes the UsbDeviceConnection before attempting releaseInterface(); the later release may fail because the connection is already closed. Prefer releaseInterface() before close(), with cleanup guarded so disconnect remains idempotent.
- The USB device filter includes a catch-all <usb-device /> entry, so the system may offer the app for unrelated USB devices. Runtime filtering rejects non-Android devices, but a Civic variant should narrow the filter to appropriate Android/AOA interface matches where practical.

### USB-only Civic implementation decisions

1. Use UsbAccessoryMode's standard controlTransfer path; remove the libusb switch from Civic UI/settings and hard-code the Civic transport to UsbAccessoryConnection.
2. Keep USB transport objects out of Application startup. Instantiate them only after USB attach, permission grant and AOA re-enumeration.
3. Validate interface class/subclass/protocol and bulk endpoint type before claiming/opening a session; do not assume interface index 0 is always the AOA data interface.
4. Treat AOA START as a transition request only. Wait for the new USB device identity/attach event, obtain permission for the re-enumerated device, and then open the accessory transport.
5. Remove LibusbAccessoryConnection, UsbNative, libusb1.0.so and usbhelper from the Civic source set/package only after verifying no retained source references them.
6. Remove local_hotspot_radio and hur_soft_hevc from Civic only after confirming the USB H.264 path and retained settings do not load them.
7. Keep the upstream branch untouched; apply these changes only under DiAuto-Civic.

Evidence references (upstream main snapshot):
- UsbAccessoryMode.kt: eeeb2f7ff08adfd033c95483b43e9e615f943ad0
- LibusbAccessoryConnection.kt: 281dd73cf7fc38d92338b30c223939f95002b2bf
- UsbAccessoryConnection.kt: retrieved during this pass; verify blob SHA in the next implementation commit
- UsbReceiver.kt: 077c23e5b0d09904f7c1e201d2d0459d7bbe5ddb
- UsbDeviceCompat.kt: 7d58b1e7b45f2842ff66d1b010580196da05cd77
- FfmpegHevcDecoder.kt: 863de1bc7aaedc9274eecaf4d238cb3873a47aa1

Static source audit only. No USB phone, Honda head unit, API-17 runtime or APK build was available for validation.
## AOA string framing and USB reconnect audit (2026-11-06)

### Confirmed AOA SEND_STRING framing defect

Upstream UsbAccessoryMode.initStringControlTransfer() sends string.toByteArray() with wLength=string.length. Android Open Accessory reference implementations send strlen(string)+1 (including the trailing NUL terminator). The Android Open Accessory Display Sink reference implementation and multiple independent AOA host implementations agree on the +1 length.

This is a concrete protocol-level defect in the current standard Java AOA switch path. It is separate from the reported app-install/startup crash, but it can prevent the phone from accepting the AOA identity strings and switching into accessory mode.

Civic correction:
- Encode explicitly as US-ASCII (the six identity strings are ASCII), append one 0x00 byte, and pass the resulting byte array length as wLength.
- Validate the controlTransfer result against the expected payload length; do not accept an arbitrary non-negative result as a successful complete string transfer.
- Send all six strings in protocol order and abort before ACCESSORY_START if any transfer is incomplete.
- Add a pure unit test for each payload: byte sequence ends in exactly one NUL; wLength equals byteArray.size; no platform USB object is needed by the test.

### USB connection state and retry behavior

- AapService.checkAlreadyConnectedUsb() checks currently enumerated devices, requests permission for the re-enumerated AOA device, and only then calls connectUsbWithRetry(). This is the correct broad lifecycle ordering: the post-switch device is a new USB enumeration and needs its own permission grant.
- onUsbPermission() distinguishes normal-mode devices (perform AOA switch) from accessory-mode devices (connect the bulk transport). Preserve this distinction in Civic.
- connectUsbWithRetry() calls CommManager.connect(device) up to four times because its loop condition is retryCount <= maxRetries with maxRetries=3. The retry log says attempt n/3, so the displayed denominator is off by one; make attempts and logs consistent.
- UsbAccessoryMode currently returns true after sending ACCESSORY_START and sleeping 500 ms. That only means the transition request was issued; it does not prove the device re-enumerated. The Civic flow should wait for the matching accessory-mode attach event with a deadline, then request permission and open the data transport.
- checkAlreadyConnectedUsb() contains policy branches for last-session, single-device and auto-start behavior. The Civic USB-only build should simplify this to one deterministic USB attach/permission/AOA/re-enumeration/connect state machine, rather than carrying the entire multi-mode auto-connect policy.

### Bulk endpoint validation

UsbAccessoryConnection.initEndpoint() currently chooses the first endpoint whose direction is IN and the first endpoint in the other direction. It does not check UsbEndpoint.getType() == USB_ENDPOINT_XFER_BULK. The USB data interface must be validated as bulk IN + bulk OUT before the connection is accepted.

Also correct disconnect cleanup ordering: release the claimed interface while the UsbDeviceConnection is still open, then close it. The current implementation closes first and then attempts releaseInterface(), which can fail. Keep cleanup idempotent and tolerate detach races.

### AOA evidence references

- DiAuto UsbAccessoryMode.kt: eeeb2f7ff08adfd033c95483b43e9e615f943ad0
- DiAuto UsbAccessoryConnection.kt: af778eb1a743d7393db93187b7974c1579b7d044
- Android Open Accessory Display Sink reference: https://github.com/binbin594738977/AndroidFramework-SourceCode/blob/ff78ded623b13cbf9a47abb1d8cae6646fff9ee8/android23/src/com/android/accessorydisplay/sink/SinkActivity.java
- Independent AOA host reference (AndroidAccessory.cpp): https://github.com/aoabook/Arduino/blob/e087d73cf2ce6bd667643704854e83e5d7949c7c/libraries/UsbHost/AndroidAccessory.cpp
- Independent AOA implementation: https://github.com/RT-Thread/rt-thread/blob/7395c5d7f08611390033b6e7fc683e827ea198ea/components/drivers/usb/cherryusb/class/aoa/usbh_aoa.c

These references support the required NUL-terminated SEND_STRING framing. They do not constitute a test against the user's phone or Civic head unit.
## First USB-only source milestone and CI validation (2026-11-06)

A standalone minimal Android application has now been added under DiAuto-Civic/app. This is intentionally a diagnostic shell and not yet a complete Android Auto receiver.

Implemented source:
- app/src/main/java/com/qiangge/diautocivic/usb/AoaProtocol.java: API-17 Android USB Host AOA negotiation; ASCII validation; NUL-terminated identity strings; exact SEND_STRING transfer-length checks; distinct START acknowledgement vs disconnect/error result; USB runtime exceptions contained.
- app/src/main/java/com/qiangge/diautocivic/usb/UsbBulkTransport.java: scans interfaces for both BULK IN and BULK OUT endpoints, claims only a suitable interface, tolerates detach/open races and releases the interface before closing the connection.
- app/src/main/java/com/qiangge/diautocivic/MainActivity.java: manual device enumeration, USB permission request, AOA negotiation, re-enumeration rescan and bulk-interface opening.
- app/src/test/java/com/qiangge/diautocivic/usb/AoaProtocolTest.java: unit tests for exact NUL framing, empty strings, non-ASCII rejection, embedded NUL rejection and request slot constants.
- app/build.gradle.kts: standalone API-17 application configuration, compileSdk 35, targetSdk 28, no AndroidX/Kotlin/Nearby/Conscrypt dependencies.
- .github/workflows/diauto-civic-build.yml: CI unit-test and debug-APK build workflow.

CI history:
- Early workflow attempts failed during Gradle script configuration because an invalid unitTests.returnDefaultValues DSL property was added. That property has been removed.
- Workflow run 42231768276, commit 63b481cbd5946d96ff0b71c22ade343b6e60a466, completed successfully: AOA protocol unit tests passed, debug APK assembled, and artifact diauto-civic-usb-debug uploaded. This run predates the final API-17 theme correction and its APK must not be treated as the current API-17 candidate.
- The manifest theme was then changed from Theme.Material.Light.NoActionBar (API 21+) to Theme.Holo.Light.NoActionBar (available before API 17). A new workflow run 42231949204 was started for that corrected manifest; its result must be checked before treating the latest tree as CI-verified.

Scope limitations:
- MainActivity currently requires the user to select the USB device and rescan after AOA re-enumeration; a bounded automatic attach/permission/re-enumeration state machine remains to be implemented.
- No AAP TLS/session handshake, media/control channels, audio, video projection or touch pipeline has been ported yet.
- No physical USB phone or Civic head-unit test has been performed. CI build success proves source compilation/unit tests only, not API-17 runtime or hardware compatibility.
- The initial diagnostic APK is not a usable Android Auto receiver and should not be described as the final installable product.