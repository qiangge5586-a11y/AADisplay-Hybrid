# iOSBridge 0.2.8 — Rebuilt source baseline

This is a reconstructed Android research project. It is being restored module by
module; it is not yet a complete CarPlay sender and has not been validated on the
target vehicle.

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

## Restored modules currently present

- Android app module and launcher activity (research shell).
- ConfigFS gadget plan renderer; it only renders a reviewable plan and executes no commands.
- FunctionFS descriptor-set builder, initializer, and transport scaffold.
- iAP2 link packet codec and link-handshake state machine.
- Read-only USB NCM capability inspector.

## Important limitations

- The FunctionFS transport scaffold is not a complete iAP2 or CarPlay transport.
- No Apple authentication, MFi credentials, or certification is implemented or claimed.
- ConfigFS plan output is not an instruction to execute blindly; it does not bind a UDC.
- Root access does not guarantee that the device kernel exposes UDC, ConfigFS, FunctionFS, or NCM.
- A successful APK build does not prove USB interoperability or compatibility with a vehicle head unit.

Further work: restore the ConfigFS transaction journal, safety/recovery logic, full read-only root USB inspector, and then compile and resolve actual build errors.
