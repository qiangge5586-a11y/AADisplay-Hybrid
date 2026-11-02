# iOSBridge 0.2.8 — Rebuilt source baseline

This directory is a freshly reconstructed Android project baseline. It is not a
re-upload of the previous ZIP and does not yet contain the full USB/iAP2 research
implementation from that archive.

## Build

- JDK 17
- Gradle 8.7
- Android Gradle Plugin 8.6.1
- Kotlin Android plugin 2.0.20
- compileSdk 35, minSdk 26, targetSdk 35

Run:

```sh
gradle --no-daemon assembleDebug
```

## Current scope

- Android app module and launcher activity.
- Safe research UI only.
- No USB gadget, ConfigFS, FunctionFS, Bluetooth identity, or network mutation.
- No complete CarPlay sender, Apple authentication, or MFi certification claim.

The next commits must restore and review the individual capability-inspection,
transaction journal, recovery, FunctionFS descriptor, and iAP2 research modules.
A successful APK build does not prove CarPlay interoperability.
