# Upstream resolution integration findings

Pinned source: `Nitsuya/AADisplay@494dcc4909e8765e8798be4e7b8031fefd90a4eb`

This note records source-verified integration points. It is not a claim that custom width/height has been implemented.

## Verified Android Auto path

1. `CoreManagerService.onCreateSplitDisplay(width, height, densityDpi, ...)` receives the head-unit profile in system_server.
2. `resolveDisplayProfile(...)` settles the negotiated dimensions (including Coolwalk rail handling) and resolves the effective density.
3. `mLockedDisplayProfile` stores the current profile.
4. `SplitDisplayController.onConnected/onReconnected(width, height, densityDpi)` owns the live split session.
5. `SplitVdLifecycle.computePaneSizes()` derives primary and secondary pane buffer sizes from profile dimensions, split ratio, orientation, and fullscreen state.
6. `SplitVdLifecycle.resizePanesInternal()` calls `VirtualDisplay.resize(width, height, densityDpi)` for both panes when needed.

The current DPI override is applied in `CoreManagerService.applyDensityOverrideLive()`, which copies only density into `mLockedDisplayProfile` and calls `onReconnected()`. `DisplayDpiStore` persists DPI only. There is no corresponding user width/height override in this pinned source.

## UI and Binder

- Settings UI: `aa-display/src/main/java/io/github/nitsuya/aa/display/ui/main/MainActivity.kt`.
- API facade: `aa-display/src/main/java/io/github/nitsuya/aa/display/CoreApi.kt`.
- Binder interface: `aa-display/src/main/aidl/io/github/nitsuya/aa/display/xposed/ICoreManager.aidl`.
- Binder implementation: `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/CoreManagerService.kt`.
- Existing DPI Binder methods are near the end of the AIDL interface and implemented by `setVdDensityDpi/getVdDensityDpi/getEffectiveVdDensityDpi/getReportedHostDensityDpi`.

Any new Binder methods must be appended to the AIDL interface to preserve existing transaction ordinals. The app-side facade delegates to `CoreManagerService.instance` only when the system_server hooks are ready; otherwise it uses `CoreManager`.

## Safe width/height implementation constraints

- Keep the raw HU-negotiated profile distinct from the configured render override. Do not feed a custom width back into HU/Coolwalk rail observation as if it were a newly negotiated width.
- Apply custom dimensions after HU profile settling, then preserve the negotiated orientation/aspect policy explicitly.
- Route the effective dimensions through the existing `onReconnected()` path so both VDs resize through `SplitVdLifecycle`.
- Review `SplitPane` ratio calculations and `SplitInputRecents` touch mapping before accepting arbitrary aspect ratios. A size-only UI patch is insufficient.
- Persist width/height alongside DPI using a dedicated store and validate bounds before Binder calls.
- On failed resize, keep or restore the last known-good profile; do not leave one pane updated and the other stale.
- Preserve the existing CarPlay path: its encoder canvas is independently negotiated and must not accidentally inherit stale Android Auto HU dimensions.
- Never use `wm size` or `wm density`; those mutate the handset's physical display.

## Build workspace status

The hybrid repository currently contains the core JVM scaffold and an Android baseline workflow that checks out the pinned upstream project separately. The baseline artifact is explicitly named `NOT-OPTIMIZED`; it is not built from integrated hybrid Android source. The next integration milestone is to bring the pinned Android project into the hybrid build workspace (with its GPL-3.0 notices and required dependencies) before wiring the settings, AIDL, store, and live resize path.
