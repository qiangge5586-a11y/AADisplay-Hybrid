# Build status and release gates

## Current repository stage

The repository now contains a standalone Kotlin/JVM core module and a GitHub Actions verification workflow. The core deliberately has no Android framework dependency, so resolution math, touch mapping, Shizuku state classification and override transaction behavior can be checked independently.

## What this build verifies

- Auto/custom width, height and DPI profile resolution
- Landscape split and fullscreen pane sizing
- Touch coordinate scaling into the virtual-display buffer
- Shizuku unavailable / permission-required / shell / root state classification
- Head-unit reconnect behavior while retaining a custom render profile
- Backend rejection without persisting an invalid override
- Best-effort display rollback when persistence fails

## What is not yet an APK

This repository is not yet the complete upstream Android application. The Shizuku Android Binder adapter, Android manifest/provider, settings UI and the concrete VirtualDisplay/Surface/input backend still need to be integrated into the upstream `:aa-display` module. A passing Kotlin core workflow is not an Android APK build and does not prove compatibility with a physical head unit.

## Required remaining integration

1. Import the pinned upstream AADisplay Android project and preserve its license/notices.
2. Wire `ResolutionOverrideCoordinator` to the existing display-control lifecycle.
3. Implement a backend that resizes both VirtualDisplays, backing surfaces and touch transforms together.
4. Add persistent settings for Auto/custom width/height/DPI.
5. Add Shizuku API/provider and an application-scoped Binder/permission lifecycle.
6. Gate LSPosed-dependent features honestly; Shizuku shell UID cannot replace injected hooks.
7. Build debug APK with Android SDK and run install, reconnect, resolution and touch tests on the target phone/head unit.
