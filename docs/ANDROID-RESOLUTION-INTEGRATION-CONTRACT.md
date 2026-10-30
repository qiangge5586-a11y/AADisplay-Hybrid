# Android resolution integration contract

Pinned upstream: `Nitsuya/AADisplay@494dcc4909e8765e8798be4e7b8031fefd90a4eb`.

## Binder API (append only)

Append these methods to the end of `ICoreManager.aidl`; never insert them between existing methods because generated Binder transaction ordinals are positional.

```aidl
boolean setVdResolution(int width, int height);
int[] getVdResolution();
int[] getEffectiveVdResolution();
```

The first getter returns the configured override (0,0 means automatic). The effective getter returns the current render-buffer profile actually applied to the two panes. The setter must validate and persist before returning true.

Implement matching overrides in both `CoreManagerService` and the client-side `CoreManager`. The latter should catch `RemoteException` and return false / `intArrayOf(0, 0)` on transport failure.

## Profile separation

There are three distinct values and they must not be conflated:

1. **HU negotiated profile** — source of truth after `resolveDisplayProfile()`, including Coolwalk rail settlement.
2. **Configured render override** — persistent user choice; 0x0 means automatic.
3. **Applied render profile** — dimensions passed to `SplitDisplayController.onConnected/onReconnected`.

Do not write the override back into `mLockedDisplayProfile`. Keep that as the HU-settled profile. A helper should derive the applied profile from the locked HU profile plus the override, then apply the current DPI override. Both the resolution setter and existing DPI setter must call this same helper so changing DPI cannot silently reset the custom dimensions.

When a new HU profile settles or the display reconnects, derive and reapply the render profile again. Rail width and touch geometry continue to use the negotiated HU profile, not the custom render buffer.

## Split resize safety

`SplitVdLifecycle.computePaneSizes()` already converts the controller's render profile into distinct pane buffer sizes based on orientation, ratio, divider and fullscreen state. Feed it the applied render profile; do not resize both panes to the same raw width/height.

In `resizePanesInternal()`, calculate both target pane sizes first. Resize each existing virtual display while retaining old dimensions. Only commit `mLastPrimaryW/H` and `mLastSecondaryW/H` after both resize calls succeed. If the second resize fails, attempt to resize the first pane back to its previous dimensions and leave cached dimensions unchanged. Reconnect can retry.

## Store

`DisplayResolutionStore` uses 0x0 for automatic mode, validates width 320..7680, height 240..4320 and a 20MP pixel budget. Settings.Global is preferred; the system_server properties file is fallback. Empty Settings.Global values must not mask a valid file fallback.

## Build gate

The current Android workflow overlays only the store and compiles the pinned upstream app. That is a compile-check artifact, not a completed hybrid build. Do not label it as the final APK until Binder, service, controller, UI and permission flows are all integrated and the release artifact is built and inspected.
