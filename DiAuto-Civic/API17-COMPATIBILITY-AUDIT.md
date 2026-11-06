# DiAuto Civic API 17 compatibility audit — initial pass

Audit date: 2026-11-06  
Upstream inspected: `shihabal3amri/DiAuto`, default branch snapshot fetched during this audit  
Target: 2019 Honda Civic factory head unit, Android 4.2.2 / API 17 / ARMv7, rooted

## Executive result

**The upstream APK is not presently a credible drop-in install for the Civic.** The Gradle flavor advertises `minSdk 16`, but that is only the manifest floor. It does not establish that the complete app, dependency graph, native libraries, startup path, or wireless networking path can run on API 17.

The current upstream source is designed and tested primarily for BYD DiLink 5.1 / Android 13. A Civic port needs a dedicated legacy platform layer and a dependency/build reduction before device testing.

## Confirmed high-risk incompatibilities

| Area | Evidence in upstream | API 17 impact | Required action |
|---|---|---|---|
| Build toolchain | AGP 8.13.2, Gradle 8.13, compileSdk 36, JDK 17, NDK 29 | Modern build host/toolchain; not itself proof of runtime incompatibility, but cannot be built with an old Android toolchain | Keep modern build host; verify generated DEX, native ABI and minimum API separately |
| Target SDK | targetSdk 36 | Modern behavior and permission assumptions; old OS ignores some newer behavior but this does not solve API references | Create a Civic-specific build flavor with a deliberate target SDK and manifest |
| AndroidX / Google libraries | Activity 1.8.2, Fragment 1.6.2, Core KTX 1.12.0, AppCompat 1.6.1, Material 1.10.0, Play Services Nearby 19.3.0, Lifecycle 2.6.2, Navigation 2.3.5, Multidex 2.0.1 | Several are newer than the API-17 compatibility baseline or carry transitive minimum-SDK constraints; manifest `tools:overrideLibrary` suppresses install-time checks but does not backport APIs | Resolve the full dependency manifest/minSdk graph; replace or pin incompatible libraries, remove Nearby if not required for Civic path |
| API 21 network classes | `AapService.kt` imports and references `Network`, `NetworkCapabilities`, `NetworkRequest`; guarded calls appear around API 21+ | API 17 does not provide these classes. Direct references in a large service class are a verifier/class-loading risk even if individual call sites are guarded | Move API 21+ network monitoring into a separate API-specific class loaded only on API 21+; provide API-17 ConnectivityManager/WifiManager fallback |
| API 26 audio focus | `AapService.kt` has a field typed as `AudioFocusRequest` and guarded API-26 calls | The type itself does not exist on API 17; class verification risk | Remove API-26 types from the API-17-loaded service class; isolate in API-26 helper and use legacy `requestAudioFocus` / `abandonAudioFocus` on API 17 |
| New broadcast helper | `ContextCompat.registerReceiver` is used in app/service paths | Compatibility depends on resolved AndroidX Core implementation and its actual minSdk; cannot assume it works because the app minSdk says 16 | Prefer platform `registerReceiver` on API 17; wrap exported/not-exported flags behind an SDK adapter |
| Hotspot implementation | `HotspotManager` prioritizes `TetheringManager` / newer tethering APIs, then reflection fallbacks including `setWifiApEnabled`; it checks modern WRITE_SETTINGS behavior | The old hidden `WifiManager.setWifiApEnabled(WifiConfiguration, boolean)` path is the relevant legacy route; newer APIs are absent | Build a dedicated API-17 ROOT adapter using only verified legacy framework/service interfaces; read back state/config after writes and implement rollback |
| SoftAP credentials | `SoftApCredentialsProvider` uses modern SoftAP configuration reader as one credential source and receiver state broadcasts | Modern `getSoftApConfiguration` does not exist on API 17; interface/IP inference alone cannot prove the AP is actually running | Implement API-17 `WifiConfiguration` reader via supported/verified reflection or root shell; verify SSID/security/PSK/interface/IP and AP state before publishing credentials |
| Conscrypt | GitHub flavor uses Conscrypt Android 2.5.3; initializer loads provider reflectively for TLS 1.2 on pre-Lollipop | Must verify that the selected artifact's native ABI and bytecode work on API 17 / ARMv7 | Inspect AAR manifest, native .so ABI/API floor and TLS handshake on device; retain system TLS fallback only if it negotiates required AAP TLS |
| Native code | NDK 29 / CMake project is enabled | Runtime support depends on each built .so's min API and ABI, not just app minSdk | Inspect CMake `ANDROID_PLATFORM`, all packaged .so ELF ABI/API requirements; build ARMv7 and test load on API 17 |

## Positive findings

- `minSdk = 16` is set in `defaultConfig`; the GitHub flavor inherits it. The Play Store flavor explicitly raises minSdk to 21.
- `App.kt` calls `MultiDex.install(this)` in `attachBaseContext`, which is the correct general pattern for legacy Dalvik multidex.
- The legacy Wi-Fi AP reflection method exists in `HotspotManager`, so there is a starting point for an API-17 implementation.
- The Bluetooth permission checks in `NativeAaHandshakeManager` are gated at API 31; API 17 should use the legacy manifest Bluetooth permissions and classic Bluetooth APIs.
- `CommManager` owns the AAP transport lifecycle and should be preserved; adapt the platform/network bootstrap around it rather than replacing the AAP protocol engine.

## Protocol path: what still needs verification

This first pass has not proven that the Civic can complete the Android Auto connection. The following must be traced independently:

1. Bluetooth discovery/pairing and RFCOMM bootstrap on the actual Honda Bluetooth stack.
2. The selected wireless route: phone hotspot/client, head-unit SoftAP/server, or Wi-Fi Direct. Do not assume the BYD mode maps to the Civic's Wi-Fi driver.
3. Actual SSID, passphrase, security type, BSSID, IP address, subnet and listening port as observed on the head unit.
4. TCP socket establishment and AAP TLS handshake, including TLS version/cipher support on API 17.
5. AAP message negotiation, video codec/profile, audio channel setup and touch input.
6. Recovery from Wi-Fi loss, phone sleep, service restart and partial handshake.

Do not reuse DiPlay's iAP2/AirPlay handshake or tunnel code for Android Auto; those are different protocols.

## Recommended implementation order

1. Freeze an upstream commit and copy only the required DiAuto source into the isolated `DiAuto-Civic/` workstream.
2. Produce a complete dependency/minSdk and native ABI inventory; remove libraries/features not needed for the Civic receiver.
3. Split API 21+ network and API 26+ audio-focus code out of API-17-loaded classes.
4. Implement and unit-test the API-17 SoftAP configuration/readback adapter, preserving the user's root-capable head-unit requirement with reversible operations.
5. Validate Bluetooth → Wi-Fi → TCP → TLS → AAP in stages with timestamped logs.
6. Adapt H.264 decode, audio routing, display dimensions and touch mapping; then build and test on the actual car head unit.

## Status and limitations

This is a source-level initial audit, not a successful build or device test. The dependency metadata, CMake configuration, packaged native libraries, full manifest merge, and all API references across the source tree have not yet been exhaustively enumerated. No APK has been ported or validated on the Civic.

## Upstream files examined

- `README.md`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/andrerinas/openheadunit/App.kt`
- `app/src/main/java/com/andrerinas/openheadunit/aap/AapService.kt`
- `app/src/main/java/com/andrerinas/openheadunit/connection/CommManager.kt`
- `app/src/main/java/com/andrerinas/openheadunit/connection/NativeAaHandshakeManager.kt`
- `app/src/main/java/com/andrerinas/openheadunit/connection/SoftApCredentialsProvider.kt`
- `app/src/main/java/com/andrerinas/openheadunit/utils/HotspotManager.kt`
- `app/src/main/java/com/andrerinas/openheadunit/utils/AppPermissions.kt`
- `app/src/main/java/com/andrerinas/openheadunit/ssl/ConscryptInitializer.kt`
- `build.gradle.kts`
- `gradle/wrapper/gradle-wrapper.properties`
