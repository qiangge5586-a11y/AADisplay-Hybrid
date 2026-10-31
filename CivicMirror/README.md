# CivicMirror

Independent Android Auto USB screen-mirroring experiment for the 2019 Honda Civic factory head unit.

> **Status: 0.1.0 bootstrap only.** This directory is intentionally isolated from the repository's wireless CarPlay work. It is not yet a functioning mirroring application and does not contain a deliverable APK.

## Current scope

- Independent Android application module
- GitHub Actions debug-build workflow
- Explicit implementation milestones for capture, car surface output, rendering and input

## Planned rendering pipeline

`MediaProjection -> ImageReader -> OpenGL ES -> Android Auto Car App Surface`

The pipeline is a design target, not an implemented feature.

## Milestones

1. Implement MediaProjection consent and foreground-service lifecycle.
2. Integrate Android for Cars App Library and validate host Surface access.
3. Implement a stable capture/render pipeline and first-frame diagnostics.
4. Add output profiles (Auto, 720p, 1080p), Fit/Fill and frame pacing.
5. Validate whether the host exposes usable touch input; implement accessibility-based input mapping only where supported.
6. Test with Android Auto Desktop Head Unit (DHU), then the target Civic.

## Limitations

- MediaProjection requires explicit user consent.
- Android 14+ imposes stricter MediaProjection session and VirtualDisplay lifecycle rules.
- DRM and secure windows may produce black frames.
- Output buffer resolution does not change the phone's physical panel resolution.
- A sideloaded debug APK is not guaranteed to appear in a production Android Auto host. Distribution and compatibility must be validated separately.
- No Shizuku or root requirement is planned for the baseline.

## Build

The GitHub Actions workflow builds the debug APK using JDK 17, Gradle 8.9 and Android SDK 35. The expected artifact path is:

`app/build/outputs/apk/debug/app-debug.apk`
