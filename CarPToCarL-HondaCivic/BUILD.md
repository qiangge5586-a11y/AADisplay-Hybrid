# Honda Civic integration build

This directory contains the reproducible integration patch for upstream
[jjuugk/CarPToCarL](https://github.com/jjuugk/CarPToCarL), pinned to tag
`v1.2.18` at commit `eb834c4e900c10c569b5942dce87b07dccd3d481`.

The upstream source is checked out into `upstream/` by the repository workflow
rather than copied or rewritten here. This keeps the upstream history and
license notices intact while isolating Civic-specific changes from the
AADisplay-Hybrid root project.

## Build

The workflow `.github/workflows/carptocarl-civic-build.yml` runs the anchored
`apply_civic_patch.py` source transformer, executes `testDebugUnitTest`, builds
`assembleDebug`, and uploads the APK as a 14-day Actions artifact.

The adaptation is intentionally conservative:
- Only match an explicit receiver identity containing `Civic`, `思域`, or
  `MY16ADA`.
- Use 800x480 and 30fps only when the receiver does not provide valid SETUP
  display dimensions or maxFPS.
- Do not alter MFi, iAP2 authentication, Wi-Fi credential exchange, or AirPlay
  pairing based on the vehicle make alone.

## Hardware limitation

The supplied vehicle report identifies an Android 4.2.2 / API 17 factory head
unit with an 800x480 panel. It does not prove that this receiver implements
wireless CarPlay, iAP2 accessory mode, or AirPlay receiver services. This app
is a phone-side sender requiring a compatible receiver. A successful APK
build therefore does not prove compatibility with the Civic head unit; that
requires on-vehicle protocol logs and a connection test.
